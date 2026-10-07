package com.jetbrains.teamcity.jenkinsbridge.teamcity;

import com.jetbrains.teamcity.jenkinsbridge.connection.JenkinsConnectionConstants;
import com.jetbrains.teamcity.jenkinsbridge.feature.BridgeBuildFeatureConstants;
import com.jetbrains.teamcity.jenkinsbridge.http.BridgeHttpException;
import com.jetbrains.teamcity.jenkinsbridge.jenkins.JenkinsClient;
import com.jetbrains.teamcity.jenkinsbridge.jenkins.JenkinsDataException;
import com.jetbrains.teamcity.jenkinsbridge.model.JenkinsArtifact;
import com.jetbrains.teamcity.jenkinsbridge.model.JenkinsArtifacts;
import com.jetbrains.teamcity.jenkinsbridge.persistence.BuildMirror;
import com.jetbrains.teamcity.jenkinsbridge.persistence.PipelineChainMirror;
import com.jetbrains.teamcity.jenkinsbridge.persistence.PipelineChainNodeMirror;
import com.jetbrains.teamcity.jenkinsbridge.persistence.BuildMirrorStore;
import com.jetbrains.teamcity.jenkinsbridge.persistence.SyncState;
import com.jetbrains.teamcity.jenkinsbridge.model.JenkinsBuildInfo;
import com.jetbrains.teamcity.jenkinsbridge.model.JenkinsLogChunk;
import com.jetbrains.teamcity.jenkinsbridge.model.JenkinsPipelineGraph;
import com.jetbrains.teamcity.jenkinsbridge.model.JenkinsPipelineGraphNode;
import com.jetbrains.teamcity.jenkinsbridge.model.JenkinsPipelineNodeStatus;
import com.jetbrains.teamcity.jenkinsbridge.model.JenkinsTestReport;
import com.jetbrains.teamcity.jenkinsbridge.model.JenkinsVcsInfo;
import com.jetbrains.teamcity.jenkinsbridge.settings.JenkinsBridgeSettingsProvider;
import com.jetbrains.teamcity.jenkinsbridge.vcs.VcsBuildCustomization;
import com.jetbrains.teamcity.jenkinsbridge.vcs.VcsSyncResult;
import jetbrains.buildServer.serverSide.BuildPromotion;
import jetbrains.buildServer.serverSide.BuildPromotionEx;

import java.io.IOException;
import java.time.Instant;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import com.intellij.openapi.diagnostic.Logger;

import static com.jetbrains.teamcity.jenkinsbridge.util.Utilities.describeException;
import static com.jetbrains.teamcity.jenkinsbridge.util.Utilities.nullToEmpty;

public class TeamCityBuildMirrorService {
  private static final Logger LOG = Logger.getInstance(TeamCityBuildMirrorService.class.getName());
  /** ensureTeamCityBuild returns this when native-chain creation is deferred until the run stabilizes. */
  private static final Set<SyncState> RUNNING_DATA_ALREADY_SENT_STATES = EnumSet.of(
      SyncState.RUNNING_SENT,
      SyncState.LOG_SYNCING,
      SyncState.TEAMCITY_FINISHED
  );

  private final JenkinsBridgeSettingsProvider settingsProvider;
  private final TeamCityRunningBuildLocator teamCityBuildLocator;
  private final TeamCityBuildQueuer teamCityBuildQueuer;
  private final TeamCityBuildStarter teamCityBuildStarter;
  private final TeamCityBuildLogger teamCityBuildLogger;
  private final TeamCityTestReporter teamCityTestReporter;
  private final TeamCityArtifactPublisher teamCityArtifactPublisher;
  private final TeamCityVcsPublisher teamCityVcsPublisher;
  private final TeamCityBuildNumberPublisher teamCityBuildNumberPublisher;
  private final TeamCityBuildFinisher teamCityBuildFinisher;
  private final TeamCityPipelineChainService teamCityPipelineChainService;
  private final BuildMirrorStore mirrorStore;

  public TeamCityBuildMirrorService(
      JenkinsBridgeSettingsProvider settingsProvider,
      TeamCityRunningBuildLocator teamCityBuildLocator,
      TeamCityBuildQueuer teamCityBuildQueuer,
      TeamCityBuildStarter teamCityBuildStarter,
      TeamCityBuildLogger teamCityBuildLogger,
      TeamCityTestReporter teamCityTestReporter,
      TeamCityArtifactPublisher teamCityArtifactPublisher,
      TeamCityVcsPublisher teamCityVcsPublisher,
      TeamCityBuildNumberPublisher teamCityBuildNumberPublisher,
      TeamCityBuildFinisher teamCityBuildFinisher,
      TeamCityPipelineChainService teamCityPipelineChainService,
      BuildMirrorStore mirrorStore
  ) {
    this.settingsProvider = settingsProvider;
    this.teamCityBuildLocator = teamCityBuildLocator;
    this.teamCityBuildQueuer = teamCityBuildQueuer;
    this.teamCityBuildStarter = teamCityBuildStarter;
    this.teamCityBuildLogger = teamCityBuildLogger;
    this.teamCityTestReporter = teamCityTestReporter;
    this.teamCityArtifactPublisher = teamCityArtifactPublisher;
    this.teamCityVcsPublisher = teamCityVcsPublisher;
    this.teamCityBuildNumberPublisher = teamCityBuildNumberPublisher;
    this.teamCityBuildFinisher = teamCityBuildFinisher;
    this.teamCityPipelineChainService = teamCityPipelineChainService;
    this.mirrorStore = mirrorStore;
  }


  // May need better naming
  public long ensureTeamCityBuild(
      BuildMirror mirror,
      String connectionId,
      JenkinsBuildInfo jenkinsInfo,
      JenkinsPipelineGraph graph,
      JenkinsVcsInfo vcsInfo
  )
      throws BridgeHttpException, IOException, TeamCityBuildQueueException {
    if (mirror.getTeamCityBuildId() != null) {
      return mirror.getTeamCityBuildId();
    }

    VcsBuildCustomization vcsCustomization = null;
    if (teamCityVcsPublisher != null && vcsInfo != null && !vcsInfo.repositories().isEmpty()) {
      try {
        vcsCustomization = teamCityVcsPublisher.prepareVcs(mirror, vcsInfo);
        if (vcsCustomization.result().hasErrors()) {
          LOG.warn("Jenkins Bridge: VCS preparation reported errors for "
              + mirror.getJenkinsBuildKey() + ": " + vcsCustomization.result().getErrors());
        }
      } catch (TeamCityVcsOperationalException e) {
        LOG.warn("Jenkins Bridge: failed to prepare VCS before queueing "
            + mirror.getJenkinsBuildKey(), e);
      }
    }

    if (graph != null && teamCityPipelineChainService != null && !jenkinsInfo.isBuilding()) {
      // The native chain is retrospective: while Jenkins runs, the bridge creates and updates one
      // live top mirror. Once Jenkins finishes and the graph is stable, the normal chain path is
      // used only when no top exists yet (Jenkins-first); TeamCity-first builds are retrofitted in
      // ensureRetrospectivePipelineChain after their existing top has finished.
      try {
        PipelineChainMirror chain = teamCityPipelineChainService.ensureChain(mirror, graph, vcsCustomization);
        if (chain != null && chain.getTopPromotionId() != null) {
          mirror.setPipelineGraph(graph);
          mirror.setPipelineChain(chain);
          mirror.setTeamCityBuildId(chain.getTopPromotionId());
          mirror.setVcsDataMissingAtQueue(!hasUsableVcsInfo(vcsInfo));
          mirror.setSyncState(SyncState.TEAMCITY_CREATED);
          mirror.setLastError(null);
          mirrorStore.saveMirror(mirror);
          return chain.getTopPromotionId();
        }
      } catch (TeamCityPipelineChainException e) {
        mirror.setLastError("Pipeline chain creation failed before queueing mirror build: "
            + e.getClass().getSimpleName()
            + (e.getMessage() == null ? "" : ": " + e.getMessage()));
        mirrorStore.saveMirror(mirror);
        LOG.warn("Jenkins Bridge: failed to create native TeamCity Pipeline chain for "
            + mirror.getJenkinsBuildKey() + "; falling back to a single mirror build", e);
      }
    }

    String buildTypeId = mirror.getTeamCityBuildTypeId();
    Long restoredBuildId = teamCityBuildLocator.recoverBuildId(buildTypeId, mirror.getJenkinsBuildKey());
    String legacyBuildKey = BuildMirrorStore.legacyBuildKey(mirror.getJenkinsBuildKey());
    if (restoredBuildId == null && !legacyBuildKey.equals(mirror.getJenkinsBuildKey())) {
      restoredBuildId = teamCityBuildLocator.recoverBuildId(buildTypeId, legacyBuildKey);
    }

    // If there already exists a build with the same Jenkins build key, use it

    if (restoredBuildId != null) {
      mirror.setTeamCityBuildId(restoredBuildId);
      mirror.setSyncState(SyncState.TEAMCITY_CREATED);
      mirror.setLastError(null);
      mirrorStore.saveMirror(mirror);
      return restoredBuildId;
    }

    // Else create a new build (with the build queuer) and return the build ID


    Map<String, String> properties = bridgeBuildParameters(mirror, connectionId, jenkinsInfo);

    long buildId = teamCityBuildQueuer.queueAgentlessBuild(
        mirror.getTeamCityBuildTypeId(),
        properties,
        mirror.getJenkinsBuildParameters(),
        vcsInfo,
        vcsCustomization);
    mirror.setTeamCityBuildId(buildId);
    mirror.setVcsDataMissingAtQueue(!hasUsableVcsInfo(vcsInfo));
    mirror.setSyncState(SyncState.TEAMCITY_CREATED);
    mirror.setLastError(null);
    mirrorStore.saveMirror(mirror);
    return buildId;
  }

  private static boolean hasUsableVcsInfo(JenkinsVcsInfo vcsInfo) {
    return vcsInfo != null && !vcsInfo.repositories().isEmpty();
  }

  Map<String, String> bridgeBuildParameters(BuildMirror mirror, String connectionId, JenkinsBuildInfo jenkinsInfo) {
    Map<String, String> properties = new LinkedHashMap<String, String>();
    properties.put(JenkinsConnectionConstants.BUILD_PARAM_CONNECTION_ID, nullToEmpty(connectionId));
    properties.put("jenkins.job", mirror.getJenkinsJob());
    properties.put("jenkins.build.number", String.valueOf(mirror.getJenkinsBuildNumber()));
    properties.put("jenkins.build.timestamp", String.valueOf(mirror.getJenkinsBuildTimestamp()));
    properties.put("jenkins.build.key", mirror.getJenkinsBuildKey());
    properties.put("jenkins.build.url", nullToEmpty(jenkinsInfo.getUrl()));
    return properties;
  }

  /**
   * Stamps the Jenkins identity onto a promotion that TeamCity created before Jenkins was
   * triggered. The normal queue path supplies these parameters to the customizer, but a
   * TeamCity-first promotion already exists by the time the Jenkins build is resolved.
   */
  public void stampExistingPromotion(
      long teamCityPromotionId,
      BuildMirror mirror,
      String connectionId,
      JenkinsBuildInfo jenkinsInfo
  ) {
    BuildPromotion promotion = teamCityBuildLocator.findPromotion(teamCityPromotionId);
    if (!(promotion instanceof BuildPromotionEx)) {
      throw new IllegalStateException("TeamCity promotion " + teamCityPromotionId
          + " does not support custom parameter updates");
    }

    Map<String, String> parameters = new LinkedHashMap<String, String>(promotion.getCustomParameters());
    parameters.putAll(bridgeBuildParameters(mirror, connectionId, jenkinsInfo));
    ((BuildPromotionEx) promotion).setCustomParameters(parameters);
  }

  public void ensureRunningDataSent(BuildMirror mirror, long teamCityBuildId)
      throws BridgeHttpException, IOException {
    if (RUNNING_DATA_ALREADY_SENT_STATES.contains(mirror.getSyncState())) {
      return;
    }

    String text = "[Jenkins Bridge] Monitoring Jenkins job " + mirror.getJenkinsJob()
        + " build #" + mirror.getJenkinsBuildNumber();

    teamCityBuildStarter.markBuildAsRunning(teamCityBuildId, text);

    mirror.setSyncState(SyncState.RUNNING_SENT);
    mirror.setLastError(null);
    mirrorStore.saveMirror(mirror);
  }

  public void ensureMetadataLogSent(BuildMirror mirror, long teamCityBuildId)
      throws BridgeHttpException, IOException, TeamCityRunningBuildNotFoundException {
    if (mirror.isMetadataLogSent()) {
      return;
    }

    String storageWarning = storageWarning(teamCityBuildId);
    String vcsWarning = mirror.isVcsDataMissingAtQueue()
        ? "WARNING: Jenkins SCM data was not available when TeamCity queued this mirror. "
            + "TeamCity may have selected its default VCS revisions and may show a branch/revision warning. "
            + "The bridge will apply Jenkins revisions before this mirror finishes if Jenkins exposes them; "
            + "otherwise TeamCity's default revisions may not match Jenkins.\n\n"
        : "";
    String text = (storageWarning == null ? "" : "[Jenkins Bridge] WARNING: " + storageWarning + "\n\n")
        + vcsWarning
        + "Monitoring Jenkins job: " + mirror.getJenkinsJob() + "\n"
        + "Jenkins build number: " + mirror.getJenkinsBuildNumber() + "\n"
        + "Jenkins build key: " + mirror.getJenkinsBuildKey() + "\n"
        + "Jenkins build start date: " + formatJenkinsStartDate(mirror.getJenkinsBuildTimestamp()) + "\n"
        + "Jenkins build URL: " + nullToEmpty(mirror.getJenkinsBuildUrl()) + "\n"
        + "\n";

    teamCityBuildLogger.addBridgeLog(teamCityBuildId, text);

    mirror.setMetadataLogSent(true);
    mirror.setSyncState(SyncState.LOG_SYNCING);
    mirror.setLastError(null);
    mirrorStore.saveMirror(mirror);
  }

  private String storageWarning(long teamCityBuildId) {
    BuildPromotion promotion = teamCityBuildLocator.findPromotion(teamCityBuildId);
    if (promotion == null) {
      return null;
    }
    Object warning = promotion instanceof BuildPromotionEx
        ? ((BuildPromotionEx) promotion).getAttribute(
            BridgeBuildFeatureConstants.JENKINS_STORAGE_WARNING_ATTRIBUTE)
        : null;
    return warning instanceof String && !((String) warning).isEmpty() ? (String) warning : null;
  }

  public void syncLogs(BuildMirror mirror, long teamCityBuildId, JenkinsLogChunk logChunk)
      throws BridgeHttpException, IOException, TeamCityRunningBuildNotFoundException {
    String newLog = logChunk.getText();
    if (newLog.length() == 0) {
      // Nothing new since the last poll; do not append or rewrite state.
      return;
    }

    teamCityBuildLogger.addBuildLog(teamCityBuildId, newLog);

    mirror.setLastLogOffset(logChunk.getNextStart());
    mirror.setSyncState(SyncState.LOG_SYNCING);
    mirror.setLastError(null);
    mirrorStore.saveMirror(mirror);
  }

  public void syncPipelineGraph(BuildMirror mirror, long teamCityBuildId, JenkinsPipelineGraph graph)
      throws BridgeHttpException, IOException {
    syncPipelineGraph(mirror, teamCityBuildId, graph, graph);
  }

  public void syncPipelineGraph(BuildMirror mirror, long teamCityBuildId, JenkinsPipelineGraph graph,
                                JenkinsPipelineGraph nativeChainGraph)
      throws BridgeHttpException, IOException {
    if (graph == null) {
      return;
    }

    mirror.setPipelineGraph(graph);
    mirror.setLastError(null);
    mirrorStore.saveMirror(mirror);
    // The live Pipeline Graph tab reads result metadata rather than the active poller state.
    // Keep it current so the tab can appear and refresh before Jenkins finishes.
    mirrorStore.saveResultMetadata(mirror);

    if (teamCityPipelineChainService == null) {
      appendPipelineChainLogOnce(
          mirror,
          teamCityBuildId,
          pipelineChainMessageKey(graph, "disabled"),
          "Native TeamCity Pipeline chain: disabled in this plugin wiring.\n");
      return;
    }

    try {
      if (nativeChainGraph != null && mirror.getPipelineChain() != null
          && mirror.getPipelineChain().matchesQueuedTopology(nativeChainGraph.getTopologyHash())) {
        syncPipelineChainNodeStates(mirror, nativeChainGraph, mirror.getPipelineChain());
        appendPipelineChainLogOnce(
            mirror,
            teamCityBuildId,
            pipelineChainMessageKey(graph, "attached"),
            "Native TeamCity Pipeline chain: attached to this build; "
                + mirror.getPipelineChain().getNodes().size()
                + " node build(s), terminal node(s) "
                + mirror.getPipelineChain().getTerminalNodeIds()
                + ", top promotion id "
                + mirror.getPipelineChain().getTopPromotionId()
                + ".\n");
        return;
      }
      appendPipelineChainLogOnce(
          mirror,
          teamCityBuildId,
          pipelineChainMessageKey(graph, "not-attached"),
          "Native TeamCity Pipeline chain: not attached to this build; graph confidence "
              + graph.getConfidence()
              + ", topology " + graph.getTopologyHash() + ".\n");
    } catch (BridgeHttpException | IOException e) {
      recordPipelineChainFailure(mirror, teamCityBuildId, graph, "Pipeline chain synchronization failed", e);
      return;
    }
    mirror.setLastError(null);
    mirrorStore.saveMirror(mirror);
  }

  private void appendPipelineChainLogOnce(
      BuildMirror mirror,
      long teamCityBuildId,
      String key,
      String text
  ) throws BridgeHttpException, IOException {
    if (key.equals(mirror.getPipelineChainMessageKey())) {
      return;
    }
    teamCityBuildLogger.addBridgeLog(teamCityBuildId, text);
    mirror.setPipelineChainMessageKey(key);
  }

  private String pipelineChainMessageKey(JenkinsPipelineGraph graph, String state) {
    return nullToEmpty(graph.getTopologyHash()) + ":" + graph.getConfidence() + ":" + state;
  }

  private void syncPipelineChainNodeStates(
      BuildMirror mirror,
      JenkinsPipelineGraph graph,
      PipelineChainMirror chain
  ) throws BridgeHttpException, IOException {
    if (chain == null || graph == null) {
      return;
    }

    for (JenkinsPipelineGraphNode graphNode : graph.getNodes()) {
      PipelineChainNodeMirror nodeMirror = chain.getNode(graphNode.getId());
      if (nodeMirror == null || nodeMirror.getPromotionId() == null) {
        continue;
      }

      String status = nullToEmpty(graphNode.getStatus());
      nodeMirror.setLastStatus(status);

      if (!nodeMirror.isRunningSent() && shouldStartPipelineChainNode(status)) {
        teamCityBuildStarter.markBuildAsRunning(
            nodeMirror.getPromotionId(),
            "Mirroring Jenkins flow node " + graphNode.getName()
                + " from " + mirror.getJenkinsBuildKey());
        nodeMirror.setRunningSent(true);
      }

      if (nodeMirror.isRunningSent() && !nodeMirror.isFinished() && isTerminalPipelineNodeStatus(status)) {
        teamCityBuildFinisher.finishBuild(
            nodeMirror.getPromotionId(),
            pipelineNodeFinishTime(graphNode),
            jenkinsResultForPipelineNodeStatus(status));
        nodeMirror.setFinished(true);
      }
    }
  }

  private boolean shouldStartPipelineChainNode(String status) {
    // Do not start a generated build for a QUEUED (not-yet-running) or unclassifiable node.
    return JenkinsPipelineNodeStatus.from(status).isStarted();
  }

  private boolean isTerminalPipelineNodeStatus(String status) {
    return JenkinsPipelineNodeStatus.from(status).isTerminal();
  }

  static String jenkinsResultForPipelineNodeStatus(String status) {
    // Single source of truth in JenkinsPipelineNodeStatus. Note: NOT_EXECUTED (skipped) -> SUCCESS
    // (green, not red) for parity with how Jenkins shows a skipped stage; continuation mode RUN keeps
    // downstream nodes independent of this node's result.
    return JenkinsPipelineNodeStatus.from(status).toTeamCityResult();
  }

  private Date pipelineNodeFinishTime(JenkinsPipelineGraphNode node) {
    long start = node.getStartTimeMillis();
    long duration = Math.max(0L, node.getDurationMillis());
    if (start <= 0L) {
      return new Date();
    }
    return new Date(start + duration);
  }

  public void syncTestsIfNeeded(BuildMirror mirror, long teamCityBuildId, JenkinsTestReport testReport)
      throws IOException,TeamCityRunningBuildNotFoundException {
    if (mirror.isTestsSynced()) {
      return;
    }

    teamCityTestReporter.reportTests(teamCityBuildId, testReport);

    mirror.setTestsSynced(true);
    mirror.setTestSyncError(null);
    mirror.setLastError(null);
    mirrorStore.saveMirror(mirror);
  }

  /** Records a test-report fetch or TeamCity test-reporting failure while preserving retryability. */
  public void recordTestSyncFailure(BuildMirror mirror, Exception failure) throws IOException {
    mirror.setTestsSynced(false);
    mirror.setTestSyncError(describeException(failure));
    mirrorStore.saveMirror(mirror);
  }

  /**
   * Registers Jenkins artifacts as externally stored references in TeamCity, without copying artifact bytes.
   */
  public void syncArtifactMetadataIfNeeded(
      BuildMirror mirror,
      long teamCityBuildId,
      JenkinsArtifacts artifacts
  ) {
    if (mirror.isArtifactsSynced()) {
      return;
    }

    List<String> failures = new ArrayList<String>();
    boolean artifactRegistrationFailed = false;

    List<JenkinsArtifact> safeArtifacts = new ArrayList<JenkinsArtifact>();
    if (artifacts != null) {
      for (JenkinsArtifact artifact : artifacts.getArtifacts()) {
        safeArtifacts.add(artifact);
      }
    }

    try {
      teamCityArtifactPublisher.publishArtifactList(teamCityBuildId, safeArtifacts);
    } catch (IOException e) {
      artifactRegistrationFailed = true;
      failures.add(e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage()));
      LOG.warn("Jenkins Bridge: failed to register artifact list for "
          + mirror.getJenkinsBuildKey(), e);
    }

    mirror.setArtifactsSynced(!artifactRegistrationFailed);
    mirror.setArtifactSyncError(failures.isEmpty() ? null : joinFailures(failures));
    if (failures.isEmpty()) {
      mirror.setLastError(null);
    }
    try {
      mirrorStore.saveMirror(mirror);
    } catch (IOException e) {
      mirror.setArtifactsSynced(false);
      mirror.setArtifactSyncError(e.getClass().getSimpleName()
          + (e.getMessage() == null ? "" : ": " + e.getMessage()));
      LOG.warn("Jenkins Bridge: failed to persist artifact sync state for "
          + mirror.getJenkinsBuildKey(), e);
    }
  }

  /**
   * Mirrors the Jenkins build's VCS information into TeamCity after it finishes on the
   * Jenkins side (since in TeamCity VCS roots must be known before build time,
   * while in Jenkins they can be added dynamically through checkout steps).
   */
  public void syncVcsIfNeeded(BuildMirror mirror, JenkinsVcsInfo vcsInfo)
      throws IOException {
    if (mirror.isVcsSynced()) {
      return;
    }

    if (mirror.isVcsDataMissingAtQueue()
        && (vcsInfo == null || vcsInfo.repositories().isEmpty())) {
      mirror.setVcsSynced(false);
      mirrorStore.saveMirror(mirror);
      return;
    }

    VcsSyncResult result = new VcsSyncResult();
    if (vcsInfo != null && !vcsInfo.repositories().isEmpty()) {
      try {
        result = teamCityVcsPublisher.applyVcsToBuild(mirror, vcsInfo);
      } catch (TeamCityVcsOperationalException e) {
        LOG.warn("VCS mirroring failed for " + mirror.getJenkinsBuildKey(), e);
        result.addError(describeException(e.getCause()));
      }
    }
    mirror.setVcsSynced(result.getErrors().isEmpty());
    mirror.setVcsSyncErrors(result.getErrors());
    mirrorStore.saveMirror(mirror);
  }

  /**
   * Records that Jenkins VCS metadata could not be read. VCS is best-effort, so this does not
   * prevent the mirror build from finishing; keeping {@code vcsSynced} false preserves the option
   * to retry while the mirror remains active.
   */
  public void recordVcsFetchFailure(BuildMirror mirror, Exception failure) throws IOException {
    mirror.setVcsSynced(false);
    mirror.setVcsSyncErrors(Collections.singletonList(describeException(failure)));
    mirrorStore.saveMirror(mirror);
  }

  public void syncBuildNumber(BuildMirror mirror) {
    boolean isPublished = teamCityBuildNumberPublisher.publishBuildNumber(
        mirror.getTeamCityBuildId(),
        mirror.getJenkinsBuildNumber()
    );
    if (!isPublished) {
      LOG.warn("Could not synchronize the build number for " + mirror.getJenkinsBuildKey());
    }
  }

  public void finishBuildIfNeeded(BuildMirror mirror, long teamCityBuildId, JenkinsBuildInfo jenkinsInfo)
      throws BridgeHttpException, IOException, TeamCityBuildFinishException,
      TeamCityRunningBuildNotFoundException {
    if (mirror.getSyncState() == SyncState.TEAMCITY_FINISHED) {
      return;
    }

    if (jenkinsInfo.isBuilding()) {
      return;
    }

    String finalResult = jenkinsInfo.getResult() == null ? "UNKNOWN" : jenkinsInfo.getResult();
    if (!mirror.isSummaryLogSent()) {
      String summary = "\n--- Jenkins build summary ---\n"
          + "Jenkins job: " + mirror.getJenkinsJob() + "\n"
          + "Jenkins build number: " + mirror.getJenkinsBuildNumber() + "\n"
          + "Jenkins build key: " + mirror.getJenkinsBuildKey() + "\n"
          + "Jenkins URL: " + nullToEmpty(jenkinsInfo.getUrl()) + "\n"
          + "Jenkins result: " + finalResult + "\n"
          + "Jenkins duration: " + jenkinsInfo.getDuration() + " ms\n";

      if (mirror.isVcsDataMissingAtQueue() && !mirror.isVcsSynced()) {
        summary += "\nWARNING: Jenkins SCM revisions were not applied before this TeamCity mirror finished. "
            + "TeamCity may still show default VCS revisions that differ from Jenkins.\n";
      }

      String synchronizationExceptions = synchronizationExceptions(mirror);
      if (!synchronizationExceptions.isEmpty()) {
        summary += "\nJenkins Bridge synchronization exceptions\n"
            + "Jenkins remains the source of truth; the following data may be absent from TeamCity:\n"
            + synchronizationExceptions;
      }

      teamCityBuildLogger.addBridgeLog(teamCityBuildId, summary);

      mirror.setSummaryLogSent(true);
      mirror.setJenkinsResult(finalResult);
      mirror.setLastError(null);
      mirrorStore.saveMirror(mirror);
    }

    Date finishTime = getJenkinsFinishTime(jenkinsInfo);
    String finishDate = formatTeamCityFinishDate(finishTime);

    teamCityBuildFinisher.finishBuild(teamCityBuildId, finishTime, finalResult);

    mirror.setSyncState(SyncState.TEAMCITY_FINISHED);
    mirror.setJenkinsResult(finalResult);
    mirror.setTeamCityFinishDate(finishDate);
    mirror.setLastError(null);
    mirrorStore.saveMirror(mirror);
    mirrorStore.saveResultMetadata(mirror);
  }

  /**
   * Retrofits the final Jenkins graph onto a TeamCity-first promotion after the live mirror has
   * finished. This intentionally does not alter execution; it only adds historical chain nodes.
   */
  public void ensureRetrospectivePipelineChain(
      BuildMirror mirror,
      long teamCityBuildId,
      JenkinsPipelineGraph graph
  ) throws IOException {

    // Only add the chain if the build has finished
    if (graph == null || teamCityPipelineChainService == null
        || mirror.getPipelineChain() != null
        || mirror.getSyncState() != SyncState.TEAMCITY_FINISHED
        || !teamCityPipelineChainService.isChainEnabled(mirror.getTeamCityBuildTypeId())) {
      return;
    }
    PipelineChainMirror chain;
    try {
      BuildPromotion topPromotion = teamCityBuildLocator.findPromotion(teamCityBuildId);
      chain = teamCityPipelineChainService.retrofitFinishedChain(
          mirror, graph, topPromotion);
      if (chain != null) {
        // The live path reconciles generated node promotions on every poll. A finished
        // retrospective mirror will not be polled again, so perform the terminal node
        // start/finish reconciliation once before handing the chain to TeamCity.
        syncPipelineChainNodeStates(mirror, graph, chain);
      }
    } catch (TeamCityPipelineChainException | BridgeHttpException | IOException e) {
      recordPipelineChainFailure(
          mirror, teamCityBuildId, graph, "Retrospective Pipeline chain creation failed", e);
      return;
    }
    if (chain != null) {
      if (mirror.getPipelineGraph() == null) {
        mirror.setPipelineGraph(graph);
      }
      mirror.setPipelineChain(chain);
      mirror.setLastError(null);
      mirrorStore.saveMirror(mirror);
      mirrorStore.saveResultMetadata(mirror);
    }
  }

  private void recordPipelineChainFailure(
      BuildMirror mirror,
      long teamCityBuildId,
      JenkinsPipelineGraph graph,
      String message,
      Exception failure
  ) throws IOException {
    mirror.setLastError(message + ": " + describeException(failure));
    mirrorStore.saveMirror(mirror);
    boolean failureLogged = false;
    try {
      appendPipelineChainLogOnce(
          mirror,
          teamCityBuildId,
          pipelineChainMessageKey(graph, "failed"),
          "Native TeamCity Pipeline chain: failed: " + describeException(failure) + ".\n");
      failureLogged = true;
    } catch (BridgeHttpException | IOException logFailure) {
      failure.addSuppressed(logFailure);
      LOG.error("Jenkins Bridge: failed to write native Pipeline chain failure for "
          + mirror.getJenkinsBuildKey(), logFailure);
    }
    if (failureLogged) {
      mirrorStore.saveMirror(mirror);
    }
    LOG.error("Jenkins Bridge: " + message + " for " + mirror.getJenkinsBuildKey(), failure);
  }

  private Date getJenkinsFinishTime(JenkinsBuildInfo jenkinsInfo) {
    long finishMillis = jenkinsInfo.getTimestamp() + Math.max(0L, jenkinsInfo.getDuration());
    return new Date(finishMillis);
  }

  private String joinFailures(List<String> failures) {
    StringBuilder result = new StringBuilder();
    for (String failure : failures) {
      if (result.length() > 0) {
        result.append("; ");
      }
      result.append(failure);
    }
    return result.toString();
  }

  private String synchronizationExceptions(BuildMirror mirror) {
    StringBuilder result = new StringBuilder();
    if (mirror.getTestSyncError() != null && !mirror.getTestSyncError().isEmpty()) {
      result.append("Tests: ").append(mirror.getTestSyncError()).append('\n');
    }
    if (mirror.getArtifactSyncError() != null && !mirror.getArtifactSyncError().isEmpty()) {
      result.append("Artifacts: ").append(mirror.getArtifactSyncError()).append('\n');
    }
    List<String> vcsErrors = mirror.getVcsSyncErrors();
    if (vcsErrors != null && !vcsErrors.isEmpty()) {
      result.append("VCS: ").append(vcsErrors.get(vcsErrors.size() - 1)).append('\n');
    }
    return result.toString();
  }

  private String formatTeamCityFinishDate(Date finishTime) {
    SimpleDateFormat formatter = new SimpleDateFormat("yyyyMMdd'T'HHmmssZ");
    formatter.setTimeZone(TimeZone.getTimeZone("UTC"));
    return formatter.format(finishTime);
  }

  private String formatJenkinsStartDate(long timestamp) {
    return timestamp > 0L ? Instant.ofEpochMilli(timestamp).toString() : "unknown";
  }

}
