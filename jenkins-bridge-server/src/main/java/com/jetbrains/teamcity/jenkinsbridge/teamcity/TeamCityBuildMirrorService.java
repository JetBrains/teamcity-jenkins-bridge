package com.jetbrains.teamcity.jenkinsbridge.teamcity;

import com.jetbrains.teamcity.jenkinsbridge.http.BridgeHttpClient;
import com.jetbrains.teamcity.jenkinsbridge.http.BridgeHttpException;
import com.jetbrains.teamcity.jenkinsbridge.jenkins.JenkinsClient;
import com.jetbrains.teamcity.jenkinsbridge.model.JenkinsArtifact;
import com.jetbrains.teamcity.jenkinsbridge.model.JenkinsArtifacts;
import com.jetbrains.teamcity.jenkinsbridge.persistence.BuildMirror;
import com.jetbrains.teamcity.jenkinsbridge.persistence.PipelineChainMirror;
import com.jetbrains.teamcity.jenkinsbridge.persistence.PipelineChainNodeMirror;
import com.jetbrains.teamcity.jenkinsbridge.persistence.BuildMirrorStore;
import com.jetbrains.teamcity.jenkinsbridge.persistence.StageMirror;
import com.jetbrains.teamcity.jenkinsbridge.persistence.SyncState;
import com.jetbrains.teamcity.jenkinsbridge.model.JenkinsBuildInfo;
import com.jetbrains.teamcity.jenkinsbridge.model.JenkinsLogChunk;
import com.jetbrains.teamcity.jenkinsbridge.model.JenkinsPipelineGraph;
import com.jetbrains.teamcity.jenkinsbridge.model.JenkinsPipelineGraphNode;
import com.jetbrains.teamcity.jenkinsbridge.model.JenkinsStage;
import com.jetbrains.teamcity.jenkinsbridge.model.JenkinsStageLog;
import com.jetbrains.teamcity.jenkinsbridge.model.JenkinsStages;
import com.jetbrains.teamcity.jenkinsbridge.model.JenkinsTestReport;
import com.jetbrains.teamcity.jenkinsbridge.settings.JenkinsBridgeSettingsProvider;
import jetbrains.buildServer.messages.BuildMessage1;

import java.io.IOException;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import com.intellij.openapi.diagnostic.Logger;

public class TeamCityBuildMirrorService {
  private static final Logger LOG = Logger.getInstance(TeamCityBuildMirrorService.class.getName());
  private static final Set<SyncState> RUNNING_DATA_ALREADY_SENT_STATES = EnumSet.of(
      SyncState.RUNNING_SENT,
      SyncState.LOG_SYNCING,
      SyncState.TEAMCITY_FINISHED
  );

  private final JenkinsBridgeSettingsProvider settingsProvider;
  private final TeamCityClient teamCityClient;
  private final TeamCityBuildQueuer teamCityBuildQueuer;
  private final TeamCityBuildStarter teamCityBuildStarter;
  private final TeamCityBuildLogger teamCityBuildLogger;
  private final TeamCityTestReporter teamCityTestReporter;
  private final TeamCityStageReporter teamCityStageReporter;
  private final TeamCityArtifactPublisher teamCityArtifactPublisher;
  private final TeamCityBuildFinisher teamCityBuildFinisher;
  private final TeamCityPipelineChainService teamCityPipelineChainService;
  private final BuildMirrorStore mirrorStore;

  public TeamCityBuildMirrorService(
      JenkinsBridgeSettingsProvider settingsProvider,
      TeamCityClient teamCityClient,
      TeamCityBuildQueuer teamCityBuildQueuer,
      TeamCityBuildStarter teamCityBuildStarter,
      TeamCityBuildLogger teamCityBuildLogger,
      TeamCityTestReporter teamCityTestReporter,
      TeamCityStageReporter teamCityStageReporter,
      TeamCityArtifactPublisher teamCityArtifactPublisher,
      TeamCityBuildFinisher teamCityBuildFinisher,
      TeamCityPipelineChainService teamCityPipelineChainService,
      BuildMirrorStore mirrorStore
  ) {
    this.settingsProvider = settingsProvider;
    this.teamCityClient = teamCityClient;
    this.teamCityBuildQueuer = teamCityBuildQueuer;
    this.teamCityBuildStarter = teamCityBuildStarter;
    this.teamCityBuildLogger = teamCityBuildLogger;
    this.teamCityTestReporter = teamCityTestReporter;
    this.teamCityStageReporter = teamCityStageReporter;
    this.teamCityArtifactPublisher = teamCityArtifactPublisher;
    this.teamCityBuildFinisher = teamCityBuildFinisher;
    this.teamCityPipelineChainService = teamCityPipelineChainService;
    this.mirrorStore = mirrorStore;
  }


  // May need better naming
  public long ensureTeamCityBuild(BuildMirror mirror, JenkinsBuildInfo jenkinsInfo)
      throws BridgeHttpException, IOException {
    return ensureTeamCityBuild(mirror, jenkinsInfo, null);
  }

  public long ensureTeamCityBuild(BuildMirror mirror, JenkinsBuildInfo jenkinsInfo, JenkinsPipelineGraph graph)
      throws BridgeHttpException, IOException {
    if (mirror.getTeamCityBuildId() != null) {
      return mirror.getTeamCityBuildId();
    }

    if (graph != null && teamCityPipelineChainService != null) {
      try {
        PipelineChainMirror chain = teamCityPipelineChainService.ensureChain(mirror, graph);
        if (chain != null && chain.getTopPromotionId() != null) {
          mirror.setPipelineGraph(graph);
          mirror.setPipelineChain(chain);
          mirror.setTeamCityBuildId(chain.getTopPromotionId());
          mirror.setSyncState(SyncState.TEAMCITY_CREATED);
          mirror.setLastError(null);
          mirrorStore.saveMirror(mirror);
          return chain.getTopPromotionId();
        }
      } catch (Exception e) {
        mirror.setLastError("Pipeline chain creation failed before queueing mirror build: "
            + e.getClass().getSimpleName()
            + (e.getMessage() == null ? "" : ": " + e.getMessage()));
        mirrorStore.saveMirror(mirror);
        LOG.warn("Jenkins Bridge: failed to create native TeamCity Pipeline chain for "
            + mirror.getJenkinsBuildKey() + "; falling back to a single mirror build", e);
      }
    }

    Long restoredBuildId = teamCityClient.findBuildIdByJenkinsBuildKey(mirror.getJenkinsBuildKey());
    String legacyBuildKey = BuildMirrorStore.legacyBuildKey(mirror.getJenkinsBuildKey());
    if (restoredBuildId == null && !legacyBuildKey.equals(mirror.getJenkinsBuildKey())) {
      restoredBuildId = teamCityClient.findBuildIdByJenkinsBuildKey(legacyBuildKey);
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


    Map<String, String> properties = bridgeBuildParameters(mirror, jenkinsInfo);

    long buildId = teamCityBuildQueuer.queueAgentlessBuild(
        mirror.getTeamCityBuildTypeId(),
        properties,
        mirror.getJenkinsBuildParameters());
    mirror.setTeamCityBuildId(buildId);
    mirror.setSyncState(SyncState.TEAMCITY_CREATED);
    mirror.setLastError(null);
    mirrorStore.saveMirror(mirror);
    return buildId;
  }

  Map<String, String> bridgeBuildParameters(BuildMirror mirror, JenkinsBuildInfo jenkinsInfo) {
    Map<String, String> properties = new LinkedHashMap<String, String>();
    properties.put("jenkins.job", mirror.getJenkinsJob());
    properties.put("jenkins.build.number", String.valueOf(mirror.getJenkinsBuildNumber()));
    properties.put("jenkins.build.timestamp", String.valueOf(mirror.getJenkinsBuildTimestamp()));
    properties.put("jenkins.build.key", mirror.getJenkinsBuildKey());
    properties.put("jenkins.build.url", nullToEmpty(jenkinsInfo.getUrl()));
    return properties;
  }

  public void ensureRunningDataSent(BuildMirror mirror, long teamCityBuildId)
      throws BridgeHttpException, IOException {
    if (RUNNING_DATA_ALREADY_SENT_STATES.contains(mirror.getSyncState())) {
      return;
    }

    String text = "Monitoring Jenkins job " + mirror.getJenkinsJob()
        + " build #" + mirror.getJenkinsBuildNumber();

    teamCityBuildStarter.markBuildAsRunning(teamCityBuildId, text);

    mirror.setSyncState(SyncState.RUNNING_SENT);
    mirror.setLastError(null);
    mirrorStore.saveMirror(mirror);
  }

  public void ensureMetadataLogSent(BuildMirror mirror, long teamCityBuildId)
      throws BridgeHttpException, IOException {
    if (mirror.isMetadataLogSent()) {
      return;
    }

    String text = "Monitoring Jenkins job: " + mirror.getJenkinsJob() + "\n"
        + "Jenkins build number: " + mirror.getJenkinsBuildNumber() + "\n"
        + "Jenkins build key: " + mirror.getJenkinsBuildKey() + "\n"
        + "Jenkins build URL: " + nullToEmpty(mirror.getJenkinsBuildUrl()) + "\n"
        + "\n";

    teamCityBuildLogger.addBuildLog(teamCityBuildId, text);

    mirror.setMetadataLogSent(true);
    mirror.setSyncState(SyncState.LOG_SYNCING);
    mirror.setLastError(null);
    mirrorStore.saveMirror(mirror);
  }

  public void syncLogs(BuildMirror mirror, long teamCityBuildId, JenkinsLogChunk logChunk)
      throws BridgeHttpException, IOException {
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
    if (graph == null) {
      return;
    }

    mirror.setPipelineGraph(graph);
    mirror.setLastError(null);
    mirrorStore.saveMirror(mirror);

    if (teamCityPipelineChainService == null) {
      appendPipelineChainLogOnce(
          mirror,
          teamCityBuildId,
          pipelineChainMessageKey(graph, "disabled"),
          "Native TeamCity Pipeline chain: disabled in this plugin wiring.\n");
      return;
    }

    try {
      if (mirror.getPipelineChain() != null
          && mirror.getPipelineChain().matchesQueuedTopology(graph.getTopologyHash())) {
        syncPipelineChainNodeStates(mirror, graph, mirror.getPipelineChain());
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
      mirror.setLastError(null);
      mirrorStore.saveMirror(mirror);
    } catch (Exception e) {
      mirror.setLastError("Pipeline chain creation failed: " + e.getClass().getSimpleName()
          + (e.getMessage() == null ? "" : ": " + e.getMessage()));
      appendPipelineChainLogOnce(
          mirror,
          teamCityBuildId,
          pipelineChainMessageKey(graph, "failed"),
          "Native TeamCity Pipeline chain: failed: " + e.getClass().getSimpleName()
              + (e.getMessage() == null ? "" : ": " + e.getMessage()) + ".\n");
      mirrorStore.saveMirror(mirror);
      LOG.warn("Jenkins Bridge: failed to create native TeamCity Pipeline chain for "
          + mirror.getJenkinsBuildKey(), e);
    }
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
    teamCityBuildLogger.addBuildLog(teamCityBuildId, text);
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
    return status.length() > 0;
  }

  private boolean isTerminalPipelineNodeStatus(String status) {
    return "SUCCESS".equals(status)
        || "FAILED".equals(status)
        || "FAILURE".equals(status)
        || "UNSTABLE".equals(status)
        || "ABORTED".equals(status)
        || "NOT_EXECUTED".equals(status);
  }

  static String jenkinsResultForPipelineNodeStatus(String status) {
    if ("FAILED".equals(status) || "FAILURE".equals(status)) {
      return "FAILURE";
    }
    if ("NOT_EXECUTED".equals(status)) {
      // A skipped Jenkins stage should be visible as a red graph node, but not as a canceled
      // dependency. FAILURE gives the node a failed TeamCity result; dependency continuation mode
      // decides whether downstream nodes inherit a dependency problem.
      return "FAILURE";
    }
    if ("SUCCESS".equals(status)
        || "UNSTABLE".equals(status)
        || "ABORTED".equals(status)) {
      return status;
    }
    return "UNKNOWN";
  }

  private Date pipelineNodeFinishTime(JenkinsPipelineGraphNode node) {
    long start = node.getStartTimeMillis();
    long duration = Math.max(0L, node.getDurationMillis());
    if (start <= 0L) {
      return new Date();
    }
    return new Date(start + duration);
  }

  /**
   * Mirrors Jenkins Pipeline stages as TeamCity build-step blocks, live and idempotently. Stages are
   * processed in {@code describe} order; for each stage we open its block once, append only the
   * console text produced since the last poll, and close it once the stage reaches a terminal status.
   *
   * To keep blocks well-formed (non-overlapping) in the linear TeamCity log, we never open the next
   * stage's block until the current one is closed: a stage that is still running (or paused, or not
   * yet started) stops this poll. Parallel stages are therefore serialized in describe order — a
   * documented v1 limitation.
   */
  public void syncStages(BuildMirror mirror, long teamCityBuildId, JenkinsStages stages, JenkinsClient jenkinsClient)
      throws BridgeHttpException, IOException {
    Map<String, StageMirror> state = mirror.getStages();
    List<BuildMessage1> messages = new ArrayList<BuildMessage1>();

    for (JenkinsStage stage : stages.getStages()) {
      StageMirror sm = state.get(stage.getId());
      if (sm == null) {
        sm = new StageMirror(stage.getName());
        state.put(stage.getId(), sm);
      }
      sm.setStatus(stage.getStatus());

      if (stage.isSkipped()) {
        // Skipped stages have no node log; emit an empty, informative block and keep going.
        if (!sm.isBlockClosed()) {
          messages.addAll(teamCityStageReporter.messagesForStage(
              stage.getName(), null, null, !sm.isBlockOpened(), "(stage skipped)", true));
          sm.setBlockOpened(true);
          sm.setBlockClosed(true);
        }
        continue;
      }

      if (stage.isNotStarted() && !sm.isBlockOpened()) {
        // Queued but not running yet: nothing to show, and nothing after it can have started either.
        break;
      }

      boolean open = !sm.isBlockOpened();

      String append = "";
      JenkinsStageLog log = jenkinsClient.getStageLog(
          mirror.getJenkinsJob(), mirror.getJenkinsBuildNumber(), stage.getId());
      String full = log.getText();
      long offset = sm.getLogOffset();
      if (offset < full.length()) {
        append = full.substring((int) offset);
        sm.setLogOffset(full.length());
      }

      boolean close = stage.isTerminal() && !sm.isBlockClosed();

      // Block start/end carry no explicit Date: they default to the current time, so every message
      // in the queue (block start, the now()-stamped console lines, block end) stays in monotonic
      // timestamp order. A historical Jenkins timestamp on the boundaries would land the blockEnd
      // before its own content and TeamCity would not render a foldable block.
      messages.addAll(teamCityStageReporter.messagesForStage(stage.getName(), null, null, open, append, close));
      if (open) {
        sm.setBlockOpened(true);
      }
      if (close) {
        sm.setBlockClosed(true);
      }

      // Do not open the next stage until this one is closed: keeps log blocks non-overlapping.
      if (!sm.isBlockClosed()) {
        break;
      }
    }

    if (!messages.isEmpty()) {
      teamCityStageReporter.report(teamCityBuildId, messages);
    }

    mirror.setSyncState(SyncState.LOG_SYNCING);
    mirror.setLastError(null);
    mirrorStore.saveMirror(mirror);
  }

  public void syncTestsIfNeeded(BuildMirror mirror, long teamCityBuildId, JenkinsTestReport testReport)
      throws IOException {
    if (mirror.isTestsSynced()) {
      return;
    }

    teamCityTestReporter.reportTests(teamCityBuildId, testReport);

    mirror.setTestsSynced(true);
    mirror.setLastError(null);
    mirrorStore.saveMirror(mirror);
  }

  @Deprecated
  public void syncArtifactsIfNeeded(
      final BuildMirror mirror,
      final long teamCityBuildId,
      JenkinsArtifacts artifacts,
      final JenkinsClient jenkinsClient
  ) {
    if (mirror.isArtifactsSynced()) {
      return;
    }

    int published = 0;
    int skipped = 0;
    List<String> failures = new ArrayList<String>();

    if (artifacts != null) {
      for (final JenkinsArtifact artifact : artifacts.getArtifacts()) {
        final String relativePath = artifact.getRelativePath();
        final String teamCityPath = teamCityArtifactPath(relativePath);
        if (teamCityPath == null) {
          skipped++;
          failures.add("Skipped unsafe artifact path: " + nullToEmpty(relativePath));
          continue;
        }

        try {
          jenkinsClient.streamArtifact(
              mirror.getJenkinsJob(),
              mirror.getJenkinsBuildNumber(),
              relativePath,
              new BridgeHttpClient.StreamHandler() {
                public void handle(InputStream inputStream) throws IOException {
                  teamCityArtifactPublisher.publishArtifact(teamCityBuildId, teamCityPath, inputStream);
                }
              });
          published++;
        } catch (Exception e) {
          failures.add(relativePath + ": " + e.getClass().getSimpleName()
              + (e.getMessage() == null ? "" : ": " + e.getMessage()));
          LOG.warn("Jenkins Bridge: failed to publish artifact "
              + relativePath + " for " + mirror.getJenkinsBuildKey(), e);
        }
      }
    }

    String message = "\n--- Jenkins artifact mirroring ---\n"
        + "Published artifacts: " + published + "\n"
        + "Skipped artifacts: " + skipped + "\n"
        + "Failures: " + failures.size() + "\n";
    if (!failures.isEmpty()) {
      message += "Artifact mirroring is best-effort; the TeamCity build result still follows Jenkins.\n";
    }

    try {
      teamCityBuildLogger.addBuildLog(teamCityBuildId, message);
    } catch (Exception e) {
      LOG.warn("Jenkins Bridge: failed to write artifact summary for "
          + mirror.getJenkinsBuildKey(), e);
      failures.add("Failed to write artifact summary: " + e.getClass().getSimpleName()
          + (e.getMessage() == null ? "" : ": " + e.getMessage()));
    }

    mirror.setArtifactsSynced(true);
    mirror.setArtifactSyncError(failures.isEmpty() ? null : joinFailures(failures));
    if (failures.isEmpty()) {
      mirror.setLastError(null);
    }
    try {
      mirrorStore.saveMirror(mirror);
    } catch (Exception e) {
      LOG.warn("Jenkins Bridge: failed to persist artifact sync state for "
          + mirror.getJenkinsBuildKey(), e);
    }
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

    int registered = 0;
    List<String> failures = new ArrayList<String>();

    List<JenkinsArtifact> safeArtifacts = new ArrayList<JenkinsArtifact>();
    if (artifacts != null) {
      for (JenkinsArtifact artifact : artifacts.getArtifacts()) {
        safeArtifacts.add(artifact);
        registered++;
      }
    }

    try {
      teamCityArtifactPublisher.publishArtifactList(teamCityBuildId, safeArtifacts);
    } catch (Exception e) {
      failures.add(e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage()));
      LOG.warn("Jenkins Bridge: failed to register artifact list for "
          + mirror.getJenkinsBuildKey(), e);
    }

    String message = "\n--- Jenkins artifact mirroring ---\n"
        + "Registered artifacts: " + registered + "\n"
        + "Failures: " + failures.size() + "\n";
    if (!failures.isEmpty()) {
      message += "Artifact mirroring is best-effort; the TeamCity build result still follows Jenkins.\n";
    }

    try {
      teamCityBuildLogger.addBuildLog(teamCityBuildId, message);
    } catch (Exception e) {
      LOG.warn("Jenkins Bridge: failed to write artifact summary for "
          + mirror.getJenkinsBuildKey(), e);
      failures.add("Failed to write artifact summary: " + e.getClass().getSimpleName()
          + (e.getMessage() == null ? "" : ": " + e.getMessage()));
    }

    mirror.setArtifactsSynced(true);
    mirror.setArtifactSyncError(failures.isEmpty() ? null : joinFailures(failures));
    if (failures.isEmpty()) {
      mirror.setLastError(null);
    }
    try {
      mirrorStore.saveMirror(mirror);
    } catch (Exception e) {
      LOG.warn("Jenkins Bridge: failed to persist artifact sync state for "
          + mirror.getJenkinsBuildKey(), e);
    }
  }

  public void finishBuildIfNeeded(BuildMirror mirror, long teamCityBuildId, JenkinsBuildInfo jenkinsInfo)
      throws BridgeHttpException, IOException {
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

      teamCityBuildLogger.addBuildLog(teamCityBuildId, summary);

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
  }

  private Date getJenkinsFinishTime(JenkinsBuildInfo jenkinsInfo) {
    long finishMillis = jenkinsInfo.getTimestamp() + Math.max(0L, jenkinsInfo.getDuration());
    return new Date(finishMillis);
  }

  @Deprecated
  static String teamCityArtifactPath(String jenkinsRelativePath) {
    if (jenkinsRelativePath == null) {
      return null;
    }
    String trimmed = jenkinsRelativePath.trim();
    if (trimmed.length() == 0 || trimmed.startsWith("/") || trimmed.indexOf('\\') >= 0) {
      return null;
    }

    String[] segments = trimmed.split("/");
    StringBuilder normalized = new StringBuilder();
    for (String segment : segments) {
      if (segment.length() == 0 || ".".equals(segment)) {
        continue;
      }
      if ("..".equals(segment)) {
        return null;
      }
      if (normalized.length() > 0) {
        normalized.append('/');
      }
      normalized.append(segment);
    }

    if (normalized.length() == 0) {
      return null;
    }
    return "jenkins-artifacts/" + normalized;
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

  private String formatTeamCityFinishDate(Date finishTime) {
    SimpleDateFormat formatter = new SimpleDateFormat("yyyyMMdd'T'HHmmssZ");
    formatter.setTimeZone(TimeZone.getTimeZone(settingsProvider.load().getZoneId()));
    return formatter.format(finishTime);
  }

  private String nullToEmpty(String value) {
    return value == null ? "" : value;
  }
}
