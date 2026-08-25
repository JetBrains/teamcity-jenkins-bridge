package com.jetbrains.teamcity.jenkinsbridge.polling;

import com.jetbrains.teamcity.jenkinsbridge.feature.MirroredJobProvider;
import com.jetbrains.teamcity.jenkinsbridge.feature.JenkinsParameterSynchronizer;
import com.jetbrains.teamcity.jenkinsbridge.http.BridgeHttpException;
import com.jetbrains.teamcity.jenkinsbridge.jenkins.JenkinsClient;
import com.jetbrains.teamcity.jenkinsbridge.jenkins.JenkinsClientFactory;
import com.jetbrains.teamcity.jenkinsbridge.jenkins.JenkinsDataException;
import com.jetbrains.teamcity.jenkinsbridge.persistence.BuildMirror;
import com.jetbrains.teamcity.jenkinsbridge.persistence.BuildMirrorStore;
import com.jetbrains.teamcity.jenkinsbridge.persistence.PendingTrigger;
import com.jetbrains.teamcity.jenkinsbridge.persistence.SyncState;
import com.jetbrains.teamcity.jenkinsbridge.model.JenkinsBuildInfo;
import com.jetbrains.teamcity.jenkinsbridge.model.JenkinsBuildParameters;
import com.jetbrains.teamcity.jenkinsbridge.model.JenkinsArtifacts;
import com.jetbrains.teamcity.jenkinsbridge.model.JenkinsLogChunk;
import com.jetbrains.teamcity.jenkinsbridge.model.JenkinsPipelineGraph;
import com.jetbrains.teamcity.jenkinsbridge.model.JenkinsQueueBuildResolution;
import com.jetbrains.teamcity.jenkinsbridge.model.JenkinsStages;
import com.jetbrains.teamcity.jenkinsbridge.model.JenkinsTestReport;
import com.jetbrains.teamcity.jenkinsbridge.model.JenkinsVcsInfo;
import com.jetbrains.teamcity.jenkinsbridge.settings.JenkinsBridgeSettings;
import com.jetbrains.teamcity.jenkinsbridge.settings.JenkinsBridgeSettingsProvider;
import com.jetbrains.teamcity.jenkinsbridge.settings.MirroredJob;
import com.jetbrains.teamcity.jenkinsbridge.teamcity.TeamCityBuildMirrorService;
import com.jetbrains.teamcity.jenkinsbridge.teamcity.TeamCityRunningBuildLocator;
import com.jetbrains.teamcity.jenkinsbridge.teamcity.TeamCityBuildFinishException;
import com.jetbrains.teamcity.jenkinsbridge.teamcity.TeamCityBuildQueueException;
import com.jetbrains.teamcity.jenkinsbridge.teamcity.TeamCityRunningBuildNotFoundException;
import com.jetbrains.teamcity.jenkinsbridge.teamcity.TeamCityQueuedBuildFailureService;
import com.jetbrains.teamcity.jenkinsbridge.util.TeamCityNodeLog;
import com.jetbrains.teamcity.jenkinsbridge.util.Utilities;

import jetbrains.buildServer.serverSide.ProjectManager;
import jetbrains.buildServer.serverSide.BuildsManager;
import jetbrains.buildServer.serverSide.SBuildType;
import jetbrains.buildServer.serverSide.TeamCityNode;
import jetbrains.buildServer.serverSide.TeamCityNodes;

import com.intellij.openapi.diagnostic.Logger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.io.IOException;
import org.jetbrains.annotations.Nullable;

public class JenkinsBridgePollingService {
  private static final int FINISHED_MIRROR_PRUNING_THRESHOLD = 1000;
  private static final int ORPHAN_METADATA_RECONCILIATION_POLL_CYCLES = 100;
  private static final Logger LOG = Logger.getInstance(JenkinsBridgePollingService.class.getName());

  private final JenkinsBridgeSettingsProvider settingsProvider;
  private final JenkinsClientFactory jenkinsClientFactory;
  private final ProjectManager projectManager;
  private final BuildsManager buildsManager;
  private final TeamCityBuildMirrorService mirrorService;
  private final BuildMirrorStore mirrorStore;
  private final MirroredJobProvider mirroredJobProvider;
  private final TeamCityRunningBuildLocator buildLocator;
  private final TeamCityQueuedBuildFailureService failureService;
  private final TeamCityNodes teamCityNodes;
  private final JenkinsBridgeSystemProblemReporter systemProblemReporter;
  private final JenkinsParameterSynchronizer parameterSynchronizer;
  private final Object systemProblemLifecycleLock = new Object();
  private final AtomicBoolean started = new AtomicBoolean(false);
  private long pollCycle;
  private ScheduledExecutorService executorService;

  public JenkinsBridgePollingService(
      JenkinsBridgeSettingsProvider settingsProvider,
      JenkinsClientFactory jenkinsClientFactory,
      ProjectManager projectManager,
      BuildsManager buildsManager,
      TeamCityBuildMirrorService mirrorService,
      BuildMirrorStore mirrorStore,
      MirroredJobProvider mirroredJobProvider,
      TeamCityRunningBuildLocator buildLocator,
      TeamCityQueuedBuildFailureService failureService,
      TeamCityNodes teamCityNodes,
      JenkinsBridgeSystemProblemReporter systemProblemReporter,
      JenkinsParameterSynchronizer parameterSynchronizer
  ) {
    this.settingsProvider = settingsProvider;
    this.jenkinsClientFactory = jenkinsClientFactory;
    this.projectManager = projectManager;
    this.buildsManager = buildsManager;
    this.mirrorService = mirrorService;
    this.mirrorStore = mirrorStore;
    this.mirroredJobProvider = mirroredJobProvider;
    this.buildLocator = buildLocator;
    this.failureService = failureService;
    this.teamCityNodes = teamCityNodes;
    this.systemProblemReporter = systemProblemReporter;
    this.parameterSynchronizer = parameterSynchronizer;
  }

  public void start() {
    JenkinsBridgeSettings settings = settingsProvider.load();
    LOG.info(TeamCityNodeLog.currentNode(teamCityNodes)
        + " Jenkins Bridge poller starting; settings=" + settings.describeForLog());

    if (!settings.isEnabled()) {
      LOG.info("Jenkins Bridge polling is disabled");
      return;
    }

    if (teamCityNodes != null && !mayPollOnCurrentNode()) {
      LOG.info(TeamCityNodeLog.currentNode(teamCityNodes)
          + " Jenkins Bridge polling scheduler started on a secondary TeamCity node; "
          + "poll cycles will remain idle until this node is main");
    }

    if (!started.compareAndSet(false, true)) {
      return;
    }

    executorService = Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
      public Thread newThread(Runnable runnable) {
        Thread thread = new Thread(runnable, "jenkins-bridge-poller");
        thread.setDaemon(true);
        return thread;
      }
    });

    LOG.info(TeamCityNodeLog.currentNode(teamCityNodes)
        + " Jenkins Bridge poller scheduled every " + settings.getPollSeconds() + " second(s)");
    executorService.scheduleWithFixedDelay(new Runnable() {
      public void run() {
        pollOnceSafely();
      }
    }, 0L, settings.getPollSeconds(), TimeUnit.SECONDS);
  }

  public void stop() {
    if (!started.compareAndSet(true, false)) {
      return;
    }

    if (executorService != null) {
      executorService.shutdownNow();
    }
    synchronized (systemProblemLifecycleLock) {
      if (systemProblemReporter != null) {
        systemProblemReporter.clearAll();
      }
    }
  }

  private void pollOnceSafely() {
    if (!mayPollOnCurrentNode()) {
      LOG.debug(TeamCityNodeLog.currentNode(teamCityNodes)
          + " Jenkins Bridge poll cycle skipped because this node is not main");
      return;
    }
    try {
      LOG.debug(TeamCityNodeLog.currentNode(teamCityNodes) + " Jenkins Bridge poll cycle started");
      pollOnce();
      mirrorStore.markPollSuccess();
      LOG.debug(TeamCityNodeLog.currentNode(teamCityNodes) + " Jenkins Bridge poll cycle completed");
    } catch (Exception e) {
      try {
        mirrorStore.markPollError(e);
      } catch (IOException stateError) {
        e.addSuppressed(stateError);
        LOG.error("Jenkins Bridge could not persist the polling failure", stateError);
      }
      LOG.error("Jenkins Bridge polling failed", e);
    }
  }

  boolean mayPollOnCurrentNode() {
    TeamCityNode currentNode = teamCityNodes.getCurrentNode();
    return currentNode != null && currentNode.isMainNode();
  }

  private void pollOnce() throws BridgeHttpException, JenkinsDataException, IOException {
    JenkinsBridgeSettings settings = settingsProvider.load();
    boolean refreshParametersRequired = shouldRefreshParameters(
        settings.getParameterRefreshPollCycles());
    List<MirroredJob> mirroredJobs = mirroredJobProvider.discoverMirroredJobs();
    Set<String> successfullyPolledMappings = new HashSet<String>();
    if (systemProblemReporter != null) {
      systemProblemReporter.reconcile(mirroredJobs);
    }
    LOG.debug(TeamCityNodeLog.currentNode(teamCityNodes)
        + " Jenkins Bridge poll cycle discovered " + mirroredJobs.size() + " mirrored job(s)");
    for (MirroredJob mirroredJob : mirroredJobs) {
      LOG.debug(TeamCityNodeLog.currentNode(teamCityNodes)
          + " Jenkins Bridge polling mapped job " + mirroredJob.describeForLog());
    }
    resolvePendingTriggers(mirroredJobs, settings);

    for (MirroredJob mirroredJob : mirroredJobs) {
      try {
        if (refreshParametersRequired) {
          refreshParameters(mirroredJob);
        }
        successfullyPolledMappings.addAll(pollPipeline(mirroredJob));
      } catch (BridgeHttpException e) {
        reportAuthoritativePollingFailure(mirroredJob, e);
        LOG.warn("Jenkins Bridge: failed to poll " + mirroredJob.describeForLog(), e);
      } catch (JenkinsDataException | IOException e) {
        // Isolate per-job operational failures so one broken job does not abort the rest of the cycle.
        LOG.warn("Jenkins Bridge: failed to poll " + mirroredJob.describeForLog(), e);
      } catch (RuntimeException e) {
        // Isolate per-job failures so one broken job does not abort the rest of the cycle.
        LOG.error("Jenkins Bridge: failed to poll " + mirroredJob.describeForLog(), e);
      }
    }
    checkIfWeNeedPruning(successfullyPolledMappings);
    reconcileOrphanedMetadataIfDue();
  }

  private void reconcileOrphanedMetadataIfDue() {
    // This is deliberately infrequent: orphaned metadata is exceptional, and regular deletion
    // events already remove metadata for normal TeamCity cleanup.
    if (pollCycle % ORPHAN_METADATA_RECONCILIATION_POLL_CYCLES != 0) {
      return;
    }
    try {
      int removed = mirrorStore.removeOrphanedResultMetadata(buildsManager);
      if (removed > 0) {
        LOG.info("Jenkins Bridge removed " + removed + " orphaned result metadata record(s)");
      }
    } catch (IOException | RuntimeException e) {
      // Orphan reconciliation is best effort and must not fail the Jenkins poll cycle.
      LOG.error("Could not reconcile orphaned Jenkins Bridge result metadata", e);
    }
  }

  /**
   * Checks whether pruning is needed and prunes finished active mirrors when it is. A total mirror
   * count of 1,000 or fewer returns without scanning. Above 1,000, the second scan finds finished
   * mirrors belonging to mappings successfully polled in this cycle; an empty result also returns
   * without pruning.
   */
  private void checkIfWeNeedPruning(Set<String> successfullyPolledMappings) throws IOException {
    if (mirrorStore.getMirrorCount() <= FINISHED_MIRROR_PRUNING_THRESHOLD
        || successfullyPolledMappings.isEmpty()) {
      return;
    }
    Set<String> mappingsWithFinishedMirrors =
        mirrorStore.getFinishedMirrorMappings(successfullyPolledMappings);
    if (mappingsWithFinishedMirrors.isEmpty()) {
      return;
    }
    String pruneTime = Instant.now().toString();
    for (String mapping : mappingsWithFinishedMirrors) {
      mirrorStore.setLastPruned(mapping, pruneTime);
    }
    mirrorStore.pruneFinishedMirrors(mappingsWithFinishedMirrors);
    LOG.info("Jenkins Bridge pruned finished mirrors for "
        + mappingsWithFinishedMirrors.size() + " mapping(s)");
  }

  private boolean shouldRefreshParameters(int refreshIntervalCycles) {
    pollCycle++;
    return pollCycle % refreshIntervalCycles == 0;
  }

  private void refreshParameters(MirroredJob mirroredJob) {
    if (parameterSynchronizer == null) {
      return;
    }
    SBuildType buildType = projectManager.findBuildTypeByExternalId(
        mirroredJob.teamCityBuildTypeExternalId());
    if (buildType == null) {
      LOG.warn("Jenkins Bridge could not refresh parameters because TeamCity build configuration "
          + mirroredJob.teamCityBuildTypeExternalId() + " was not found");
      return;
    }
    try {
      int refreshed = parameterSynchronizer.synchronize(buildType).getSynchronizedCount();
      buildType.schedulePersisting("Jenkins Bridge: refresh Jenkins build parameters")
          .awaitUninterruptibly();
      LOG.debug("Jenkins Bridge refreshed " + refreshed + " Jenkins parameter(s) for "
          + mirroredJob.describeForLog());
    } catch (BridgeHttpException | JenkinsDataException e) {
      LOG.warn("Jenkins Bridge could not refresh Jenkins parameters for "
          + mirroredJob.describeForLog(), e);
    } catch (RuntimeException e) {
      LOG.error("Jenkins Bridge failed to refresh Jenkins parameters for "
          + mirroredJob.describeForLog(), e);
    }
  }

  private void resolvePendingTriggers(List<MirroredJob> mirroredJobs, JenkinsBridgeSettings settings) throws IOException {
    List<PendingTrigger> pendingTriggers = mirrorStore.getPendingTriggers();
    if (pendingTriggers.isEmpty()) {
      return;
    }

    LOG.debug(TeamCityNodeLog.currentNode(teamCityNodes)
        + " [Jenkins Bridge DEBUG] Resolving " + pendingTriggers.size()
        + " pending TeamCity trigger(s)");

    for (PendingTrigger pendingTrigger : pendingTriggers) {
      try {
        if (isPendingTriggerExpired(pendingTrigger, settings.getPendingTriggerTimeoutMinutes(), Instant.now())) {
          if (failTriggeredPromotion(pendingTrigger,
              "Jenkins Bridge called Jenkins, but the trigger could not be confirmed within "
                  + settings.getPendingTriggerTimeoutMinutes() + " minute(s). "
                  + "The result is uncertain; check Jenkins for an accepted build.")) {
            mirrorStore.removePendingTrigger(pendingTrigger.getTeamCityPromotionId());
            LOG.warn("Jenkins Bridge expired pending TeamCity promotion "
                + pendingTrigger.getTeamCityPromotionId());
          } else {
            LOG.error("Jenkins Bridge could not remove expired pending TeamCity promotion "
                + pendingTrigger.getTeamCityPromotionId() + "; retaining it for retry");
          }
          continue;
        }
        MirroredJob mirroredJob = findMirroredJob(mirroredJobs, pendingTrigger);
        if (mirroredJob == null) {
          LOG.warn("Jenkins Bridge pending TeamCity promotion "
              + pendingTrigger.getTeamCityPromotionId()
              + " no longer matches an active Jenkins Bridge build feature; keeping it pending");
          continue;
        }

        // The Jenkins server to ask is the one the owning build configuration mirrors from, so the
        // client has to be resolved per pending trigger.
        JenkinsClient jenkinsClient = jenkinsClientFor(mirroredJob);
        pendingTrigger = normalizePendingTrigger(jenkinsClient, pendingTrigger);
        if (!pendingTrigger.hasResolvedQueueId()) {
          continue;
        }

        JenkinsQueueBuildResolution resolution =
            jenkinsClient.resolveQueuedBuildNumber(pendingTrigger.getQueueItemUrl());
        if (resolution.isPending()) {
          continue;
        }

        if (resolution.isCancelled()) {
          failTriggeredPromotion(pendingTrigger,
              "Jenkins accepted the trigger, but its queue item was cancelled before a build was created.");
          mirrorStore.removePendingTrigger(pendingTrigger.getTeamCityPromotionId());
          continue;
        }

        bindResolvedTrigger(jenkinsClient, pendingTrigger, mirroredJob, resolution.getBuildNumber());
      } catch (BridgeHttpException | JenkinsDataException | IOException e) {
        LOG.warn("Jenkins Bridge failed to resolve pending TeamCity promotion "
            + pendingTrigger.getTeamCityPromotionId(), e);
      } catch (RuntimeException e) {
        // Keep this trigger pending and continue resolving the others, but do not present a bridge
        // defect as an ordinary Jenkins resolution failure.
        LOG.error("Jenkins Bridge failed to resolve pending TeamCity promotion "
            + pendingTrigger.getTeamCityPromotionId(), e);
      }
    }
  }

  static boolean isPendingTriggerExpired(PendingTrigger pendingTrigger, int timeoutMinutes, Instant now) {
    if (pendingTrigger == null || pendingTrigger.getCreatedAt() == null || now == null) {
      return false;
    }
    try {
      Instant created = OffsetDateTime.parse(pendingTrigger.getCreatedAt()).toInstant();
      return !now.isBefore(created.plus(Duration.ofMinutes(Math.max(1, timeoutMinutes))));
    } catch (DateTimeParseException ignored) {
      LOG.warn("Jenkins Bridge could not parse pending trigger creation time for promotion "
          + pendingTrigger.getTeamCityPromotionId() + "; retaining it");
      return false;
    }
  }

  private PendingTrigger normalizePendingTrigger(JenkinsClient jenkinsClient, PendingTrigger pendingTrigger)
      throws IOException {
    if (pendingTrigger.getJenkinsQueueId() >= 0) {
      return pendingTrigger;
    }
    long queueId = JenkinsClient.parseQueueId(pendingTrigger.getQueueItemUrl());
    if (queueId < 0) {
      return pendingTrigger;
    }
    PendingTrigger upgraded = new PendingTrigger(
        pendingTrigger.getTeamCityPromotionId(),
        pendingTrigger.getJenkinsJob(),
        pendingTrigger.getTeamCityBuildTypeExternalId(),
        pendingTrigger.getQueueItemUrl(),
        queueId,
        isBlank(pendingTrigger.getJenkinsController())
            ? jenkinsClient.getControllerIdentity() : pendingTrigger.getJenkinsController(),
        pendingTrigger.getCreatedAt());
    mirrorStore.savePendingTrigger(upgraded);
    return upgraded;
  }

  private boolean isBlank(String value) {
    return value == null || value.trim().isEmpty();
  }

  private MirroredJob findMirroredJob(List<MirroredJob> mirroredJobs, PendingTrigger pendingTrigger) {
    for (MirroredJob mirroredJob : mirroredJobs) {
      if (pendingTrigger.getTeamCityBuildTypeExternalId().equals(mirroredJob.teamCityBuildTypeExternalId())
          && pendingTrigger.getJenkinsJob().equals(mirroredJob.jenkinsJob())) {
        return mirroredJob;
      }
    }
    return null;
  }

  private void bindResolvedTrigger(JenkinsClient jenkinsClient, PendingTrigger pendingTrigger,
                                   MirroredJob mirroredJob, int buildNumber)
      throws BridgeHttpException, JenkinsDataException, IOException {
    JenkinsBuildInfo buildInfo = jenkinsClient.getBuildInfo(pendingTrigger.getJenkinsJob(), buildNumber);
    if (pendingTrigger.hasResolvedQueueId()
        && buildInfo.getQueueId() >= 0
        && pendingTrigger.getJenkinsQueueId() != buildInfo.getQueueId()) {
        LOG.warn("Jenkins Bridge refused queue-item binding for TeamCity promotion "
            + pendingTrigger.getTeamCityPromotionId() + ": queue id changed from "
            + pendingTrigger.getJenkinsQueueId() + " to " + buildInfo.getQueueId());
        failTriggeredPromotion(pendingTrigger,
            "Jenkins was called, but the returned build did not match the trigger queue item. "
                + "The result is uncertain; check Jenkins.");
        mirrorStore.removePendingTrigger(pendingTrigger.getTeamCityPromotionId());
        return;
      }
    String mirrorKey = BuildMirrorStore.buildKey(mirroredJob.getMirrorKeyPrefix(), buildInfo);
    BuildMirror mirror = mirrorStore.getOrCreateMirror(
        mirrorKey,
        pendingTrigger.getJenkinsJob(),
        pendingTrigger.getTeamCityBuildTypeExternalId(),
        buildInfo);
    if (mirror.getTeamCityBuildId() != null
        && mirror.getTeamCityBuildId() != pendingTrigger.getTeamCityPromotionId()) {
        failTriggeredPromotion(pendingTrigger,
            "Jenkins was called, but the resulting build is already associated with another TeamCity build. "
                + "Check Jenkins and the other TeamCity build.");
        mirrorStore.removePendingTrigger(pendingTrigger.getTeamCityPromotionId());
        return;
      }
    mirror.setTeamCityBuildId(pendingTrigger.getTeamCityPromotionId());
    mirrorService.stampExistingPromotion(
        pendingTrigger.getTeamCityPromotionId(), mirror, mirroredJob.connectionId(), buildInfo);
    mirrorStore.saveMirror(mirror);
    mirrorStore.removePendingTrigger(pendingTrigger.getTeamCityPromotionId());
    LOG.info("Jenkins Bridge bound TeamCity promotion " + pendingTrigger.getTeamCityPromotionId()
        + " to Jenkins build " + mirror.getJenkinsBuildKey());
  }

  private boolean failTriggeredPromotion(PendingTrigger pendingTrigger, String reason) {
    try {
      if (failureService == null) {
        LOG.warn("Jenkins Bridge cannot fail TeamCity promotion "
            + pendingTrigger.getTeamCityPromotionId() + " because the failure service is unavailable");
        return false;
      }
      failureService.failQueuedPromotion(pendingTrigger.getTeamCityPromotionId(), reason);
      return true;
    } catch (RuntimeException e) {
      LOG.warn("Jenkins Bridge failed to mark TeamCity promotion "
          + pendingTrigger.getTeamCityPromotionId(), e);
      return false;
    }
  }

  /**
   * Calls {@code pollJob} once for regular pipelines, and once for each branch in the case of a multibranch pipeline.
   */
  private Set<String> pollPipeline(MirroredJob mirroredJob)
      throws BridgeHttpException, JenkinsDataException, IOException {
    if (!mirroredJob.hasMinimumConfiguration()) {
      LOG.warn(mirroredJob.describeMinimumConfigurationProblem());
      return Collections.emptySet();
    }

    JenkinsClient jenkinsClient = jenkinsClientFor(mirroredJob);
    int recentBuildLimit = mirroredJob.recentBuildLimit();

    if (mirroredJob.isMultibranch()) {
      Map<String, List<JenkinsBuildInfo>> branchBuilds =
          jenkinsClient.listBranchBuilds(mirroredJob.jenkinsJob());
      JobPollOutcome outcome = new JobPollOutcome();
      for (var entry : branchBuilds.entrySet()) {
        MirroredJob branchJob = new MirroredJob(
            mirroredJob.connectionId(),
            entry.getKey(),
            mirroredJob.teamCityBuildTypeExternalId(),
            mirroredJob.teamCityBuildTypeName(),
            recentBuildLimit, false);
        outcome.merge(pollJob(jenkinsClient, branchJob, entry.getValue(), recentBuildLimit));
      }
      updateSystemProblem(mirroredJob, outcome);
      Set<String> mappings = new HashSet<String>();
      for (String branch : branchBuilds.keySet()) {
        mappings.add(mirroredJob.teamCityBuildTypeExternalId() + "::" + branch);
      }
      return mappings;
    }

    JobPollOutcome outcome = pollJob(
        jenkinsClient, mirroredJob, jenkinsClient.getBuilds(mirroredJob.jenkinsJob()), recentBuildLimit);
    updateSystemProblem(mirroredJob, outcome);
    return Collections.singleton(mirroredJob.getMirrorKeyPrefix());
  }

  void reportAuthoritativePollingFailure(MirroredJob mirroredJob, BridgeHttpException failure) {
    synchronized (systemProblemLifecycleLock) {
      if (systemProblemReporter == null || !started.get()) {
        return;
      }
      if (failure.getStatusCode() == 404) {
        systemProblemReporter.reportMissingJob(mirroredJob, failure);
      } else if (isConnectivityFailure(failure)) {
        systemProblemReporter.reportConnectivity(mirroredJob, failure);
      }
    }
  }

  void updateSystemProblem(MirroredJob mirroredJob, JobPollOutcome outcome) {
    synchronized (systemProblemLifecycleLock) {
      if (systemProblemReporter == null || !started.get()) {
        return;
      }
      BridgeHttpException connectivityFailure = outcome.getConnectivityFailure();
      if (connectivityFailure != null) {
        systemProblemReporter.reportConnectivity(mirroredJob, connectivityFailure);
      } else {
        systemProblemReporter.recover(mirroredJob);
      }
    }
  }

  static boolean isConnectivityFailure(BridgeHttpException failure) {
    if (failure == null) {
      return false;
    }
    int status = failure.getStatusCode();
    return status < 0 || status == 401 || status == 403 || status >= 500;
  }

  private JenkinsClient jenkinsClientFor(MirroredJob mirroredJob) {
    SBuildType buildType =
        Utilities.findBuildType(mirroredJob.teamCityBuildTypeExternalId(), projectManager);
    if (buildType == null) {
      throw new IllegalStateException("TeamCity build type "
          + mirroredJob.teamCityBuildTypeExternalId() + " was not found");
    }
    return jenkinsClientFactory.forConnectionId(buildType.getProject(), mirroredJob.connectionId());
  }

  private JobPollOutcome pollJob(JenkinsClient jenkinsClient, MirroredJob mirroredJob,
                                 List<JenkinsBuildInfo> builds, int recentBuildLimit)
      throws BridgeHttpException, JenkinsDataException, IOException {
    JobPollOutcome outcome = new JobPollOutcome();
    String job = mirroredJob.jenkinsJob();
    String keyPrefix = mirroredJob.getMirrorKeyPrefix();

    // The most recent builds are fetched (Jenkins caps this at the 100 newest). The timestamp is part of
    // the bridge identity because Jenkins build numbers can be reused after the build history is reset.
    if (builds.isEmpty()) {
      LOG.debug(TeamCityNodeLog.currentNode(teamCityNodes)
          + " [Jenkins Bridge DEBUG] Jenkins job " + job + " has no builds yet");
      return outcome;
    }

    int latest = maxBuildNumber(builds);
    int oldest = minBuildNumber(builds);
    int lastSeen = mirrorStore.getLastSeenBuildNumber(keyPrefix);
    String lastPruned = mirrorStore.getLastPruned(keyPrefix);
    int coldStartAfter = lastSeen;
    boolean coldStart = lastSeen == 0;
    boolean resetDetected = false;

    if (coldStart) {
      // Cold start: don't replay the whole history. Backfill only the most recent builds, where
      // recentBuildLimit is the backfill depth (default 1 = start from the latest build).
      coldStartAfter = Math.max(0, latest - recentBuildLimit);
      LOG.debug(TeamCityNodeLog.currentNode(teamCityNodes) + " [Jenkins Bridge DEBUG] Cold start for job " + job
          + "; backfilling from build " + (coldStartAfter + 1) + " (latest=" + latest + ")");
    } else if (latest < lastSeen) {
      resetDetected = true;
      LOG.warn(TeamCityNodeLog.currentNode(teamCityNodes) + " Jenkins Bridge detected build-number reset for job " + job
          + " (lastSeen=" + lastSeen + ", latest=" + latest
          + "); processing recent builds by timestamped identity");
    } else if (oldest > lastSeen + 1) {
      // We were running before but more than ~100 builds have happened since, so the cheap
      // builds view no longer reaches back to lastSeen. Escalate to allBuilds so we
      // never skip a build (rare; only after a long outage).
      // TODO: Add a cap of ~1000 to not accidentally load thousands of builds
      LOG.debug(TeamCityNodeLog.currentNode(teamCityNodes) + " [Jenkins Bridge DEBUG] Gap detected for job " + job
          + " (lastSeen=" + lastSeen + ", oldest fetched=" + oldest
          + "); fetching all build numbers");
      builds = jenkinsClient.getAllBuilds(job);
      if (builds.isEmpty()) {
        return outcome;
      }
      latest = maxBuildNumber(builds);
    }

    List<JenkinsBuildInfo> toProcess = new ArrayList<JenkinsBuildInfo>();
    for (JenkinsBuildInfo build : builds) {
      if (shouldProcessDiscoveredBuild(jenkinsClient, build, keyPrefix, lastSeen, coldStartAfter,
          coldStart, resetDetected, lastPruned)) {
        toProcess.add(build);
      }
    }
    sortByBuildNumber(toProcess);
    LOG.debug(TeamCityNodeLog.currentNode(teamCityNodes) + " [Jenkins Bridge DEBUG] " + mirroredJob.describeForLog() + ": " + toProcess.size()
        + " build(s) selected after watermark " + lastSeen);

    Set<Integer> handled = new HashSet<Integer>();
    int maxNumber = lastSeen;
    boolean watermarkCanAdvance = true;

    for (JenkinsBuildInfo build : toProcess) {
      boolean discoveredAndTracked = syncDiscoveredBuild(jenkinsClient, mirroredJob, build, outcome);
      if (!discoveredAndTracked) {
        watermarkCanAdvance = false;
        continue;
      }
      if (watermarkCanAdvance) {
        maxNumber = Math.max(maxNumber, build.getNumber());
      }
      handled.add(build.getNumber());
    }

    // Keep syncing builds that are still in progress but already past the watermark.
    List<BuildMirror> active =
        mirrorStore.getActiveMirrors(mirroredJob.teamCityBuildTypeExternalId(), job);

    for (BuildMirror mirror : active) {
      if (handled.contains(mirror.getJenkinsBuildNumber())) {
        continue;
      }
      syncActiveMirror(jenkinsClient, mirroredJob.connectionId(), mirror, outcome);
    }

    // On a cold start the watermark also has to clear the builds that were deliberately skipped,
    // otherwise a backfill depth of 0 would leave the job cold forever and never mirror anything.
    int watermark = coldStart ? Math.max(maxNumber, coldStartAfter) : maxNumber;
    if (!resetDetected && watermark > mirrorStore.getLastSeenBuildNumber(keyPrefix)) {
      mirrorStore.setLastSeenBuildNumber(keyPrefix, watermark);
    }

    return outcome;
  }

  private boolean shouldProcessDiscoveredBuild(
      JenkinsClient jenkinsClient,
      JenkinsBuildInfo build,
      String keyPrefix,
      int lastSeen,
      int coldStartAfter,
      boolean coldStart,
      boolean resetDetected,
      @Nullable String lastPruned
  ) throws IOException {
    // A newly triggered run must be considered even when the numeric watermark has already moved
    // past it (for example after a coalesced Jenkins submission). Queue ID ownership outranks the
    // build-number optimization.
    if (build.getQueueId() >= 0
        && mirrorStore.findPendingTrigger(jenkinsClient.getControllerIdentity(), build.getQueueId()) != null) {
      return true;
    }
    String currentKey = BuildMirrorStore.buildKey(keyPrefix, build);
    BuildMirror current = mirrorStore.findMirror(currentKey);
    if (current != null) {
      return current.getSyncState() != SyncState.TEAMCITY_FINISHED;
    }

    if (isBeforePruneBoundary(build.getTimestamp(), lastPruned, keyPrefix)) {
      return false;
    }

    if (coldStart) {
      return build.getNumber() > coldStartAfter;
    }

    if (resetDetected) {
      return true;
    }

    if (build.getNumber() <= lastSeen) {
      return false;
    }

    // New keys include the Jenkins timestamp so reused build numbers can coexist. Keep this lookup
    // for state written before timestamped keys were introduced; otherwise an existing mirror could
    // be rediscovered under its new key after an upgrade.
    BuildMirror legacy = mirrorStore.findMirror(BuildMirrorStore.buildKey(keyPrefix, build.getNumber()));
    return legacy == null;
  }

  private boolean isBeforePruneBoundary(long buildTimestamp, @Nullable String lastPruned, String keyPrefix) {
    if (lastPruned == null || buildTimestamp <= 0L) {
      return false;
    }
    try {
      return buildTimestamp <= Instant.parse(lastPruned).toEpochMilli();
    } catch (DateTimeParseException e) {
      LOG.error("Invalid Jenkins Bridge prune boundary for " + keyPrefix + ": " + lastPruned, e);
      return false;
    }
  }

  /**
   * Synchronizes a discovered build and reports whether discovery/tracking succeeded.
   *
   * <p>False means the watermark must not advance past this build. True means a mirror exists;
   * later synchronization failures are recorded for retry and do not block watermark progress.</p>
   */
  private boolean syncDiscoveredBuild(JenkinsClient jenkinsClient, MirroredJob mirroredJob,
                                   JenkinsBuildInfo discoveredBuild, JobPollOutcome outcome) {
    String job = mirroredJob.jenkinsJob();
    BuildMirror mirror = null;
    boolean discoveredAndTracked = false;
    int buildNumber = discoveredBuild.getNumber();
    LOG.debug(TeamCityNodeLog.currentNode(teamCityNodes)
        + " [Jenkins Bridge DEBUG] Discovered Jenkins build " + job + "#" + buildNumber
        + " (queueId=" + discoveredBuild.getQueueId() + ")");
    try {
      JenkinsBuildInfo buildInfo = jenkinsClient.getBuildInfo(job, buildNumber);
      String mirrorKey = BuildMirrorStore.buildKey(mirroredJob.getMirrorKeyPrefix(), buildInfo);
      mirror = mirrorStore.getOrCreateMirror(mirrorKey, job, mirroredJob.teamCityBuildTypeExternalId(), buildInfo);
      discoveredAndTracked = true;
      PendingTrigger pending = mirrorStore.findPendingTrigger(jenkinsClient.getControllerIdentity(), buildInfo.getQueueId());
      if (pending != null) {
        boolean ownershipMatches = job.equals(pending.getJenkinsJob())
            && mirroredJob.teamCityBuildTypeExternalId().equals(pending.getTeamCityBuildTypeExternalId());
        if (!ownershipMatches) {
          LOG.debug("Jenkins Bridge ignored pending ownership for queue id "
              + buildInfo.getQueueId() + " while processing " + mirror.getJenkinsBuildKey());
          pending = null;
        }
      }
      if (pending != null) {
        if (mirror.getTeamCityBuildId() != null
            && mirror.getTeamCityBuildId() != pending.getTeamCityPromotionId()) {
          failTriggeredPromotion(pending,
              "Jenkins was called, but the resulting build is already associated with another TeamCity build. "
                  + "Check Jenkins and the other TeamCity build.");
          mirrorStore.removePendingTrigger(pending.getTeamCityPromotionId());
          LOG.warn("Jenkins Bridge retained existing owner for " + mirror.getJenkinsBuildKey()
              + " instead of replacing TeamCity promotion " + pending.getTeamCityPromotionId());
          return true;
        }
        mirror.setTeamCityBuildId(pending.getTeamCityPromotionId());
        mirrorService.stampExistingPromotion(
            pending.getTeamCityPromotionId(), mirror, mirroredJob.connectionId(), buildInfo);
        mirrorStore.saveMirror(mirror);
        mirrorStore.removePendingTrigger(pending.getTeamCityPromotionId());
        LOG.debug("Jenkins Bridge claimed Jenkins build " + mirror.getJenkinsBuildKey()
            + " for TeamCity promotion " + pending.getTeamCityPromotionId()
            + " by queue id " + buildInfo.getQueueId());
      }
      LOG.debug(TeamCityNodeLog.currentNode(teamCityNodes) + " [Jenkins Bridge DEBUG] Syncing Jenkins build " + mirror.getJenkinsBuildKey()
          + " in state " + mirror.getSyncState()
          + " with TeamCity build id " + mirror.getTeamCityBuildId());
      syncBuild(jenkinsClient, mirroredJob.connectionId(), mirror, buildInfo);
      LOG.debug(TeamCityNodeLog.currentNode(teamCityNodes) + " [Jenkins Bridge DEBUG] Synced Jenkins build " + mirror.getJenkinsBuildKey()
          + " now in state " + mirror.getSyncState()
          + " with TeamCity build id " + mirror.getTeamCityBuildId());
    } catch (BridgeHttpException | JenkinsDataException | IOException | TeamCityBuildQueueException e) {
        outcome.recordJenkinsFailure(e);
        if (mirror != null) {
          recordBuildError(mirror, e);
        }
        LOG.warn("Failed to sync Jenkins build " + job + "#" + buildNumber, e);
    } catch (RuntimeException e) {
        outcome.recordJenkinsFailure(e);
        if (mirror != null) {
          recordBuildError(mirror, e);
        }
        LOG.error("Failed to sync Jenkins build " + job + "#" + buildNumber, e);
    }
    return discoveredAndTracked;
  }

  private void syncActiveMirror(JenkinsClient jenkinsClient, String connectionId, BuildMirror mirror,
                                JobPollOutcome outcome) {
    try {
      JenkinsBuildInfo buildInfo = jenkinsClient.getBuildInfo(mirror.getJenkinsJob(), mirror.getJenkinsBuildNumber());
      if (mirror.getJenkinsBuildTimestamp() > 0L
          && buildInfo.getTimestamp() > 0L
          && mirror.getJenkinsBuildTimestamp() != buildInfo.getTimestamp()) {
        LOG.warn("Skipping active Jenkins mirror " + mirror.getJenkinsBuildKey()
            + " because Jenkins now reports build #" + mirror.getJenkinsBuildNumber()
            + " with timestamp " + buildInfo.getTimestamp()
            + " instead of " + mirror.getJenkinsBuildTimestamp()
            + "; the build number appears to have been reused");
        return;
      }
      syncBuild(jenkinsClient, connectionId, mirror, buildInfo);
    } catch (BridgeHttpException | JenkinsDataException | IOException | TeamCityBuildQueueException e) {
      outcome.recordJenkinsFailure(e);
      recordBuildError(mirror, e);
      LOG.warn("Failed to sync Jenkins build " + mirror.getJenkinsBuildKey(), e);
    } catch (RuntimeException e) {
      outcome.recordJenkinsFailure(e);
      recordBuildError(mirror, e);
      LOG.error("Failed to sync Jenkins build " + mirror.getJenkinsBuildKey(), e);
    }
  }

  private void recordBuildError(BuildMirror mirror, Exception error) {
    try {
      mirrorStore.markBuildError(mirror, error);
    } catch (IOException stateError) {
      error.addSuppressed(stateError);
      LOG.error("Failed to persist Jenkins Bridge build error for " + mirror.getJenkinsBuildKey(), stateError);
    }
  }

  private int maxBuildNumber(List<JenkinsBuildInfo> builds) {
    int max = 0;
    for (JenkinsBuildInfo build : builds) {
      max = Math.max(max, build.getNumber());
    }
    return max;
  }

  private int minBuildNumber(List<JenkinsBuildInfo> builds) {
    int min = Integer.MAX_VALUE;
    for (JenkinsBuildInfo build : builds) {
      min = Math.min(min, build.getNumber());
    }
    return min == Integer.MAX_VALUE ? 0 : min;
  }

  private void sortByBuildNumber(List<JenkinsBuildInfo> builds) {
    Collections.sort(builds, new Comparator<JenkinsBuildInfo>() {
      public int compare(JenkinsBuildInfo left, JenkinsBuildInfo right) {
        int numberComparison = Integer.valueOf(left.getNumber()).compareTo(Integer.valueOf(right.getNumber()));
        if (numberComparison != 0) {
          return numberComparison;
        }
        return Long.valueOf(left.getTimestamp()).compareTo(Long.valueOf(right.getTimestamp()));
      }
    });
  }

  private void syncBuild(JenkinsClient jenkinsClient, String connectionId, BuildMirror mirror,
                         JenkinsBuildInfo buildInfo)
      throws BridgeHttpException, JenkinsDataException, IOException, TeamCityBuildQueueException,
      TeamCityBuildFinishException, TeamCityRunningBuildNotFoundException {
    // Decide once whether this build is a Jenkins Pipeline (mirror stages as build steps) or a
    // freestyle build (mirror the flat progressive console log). The decision is sticky per build.
    @Nullable Boolean persistedPipelineMode = mirror.getPipelineMode();
    @Nullable JenkinsStages stages = null;
    boolean pipelineBuild;
    if (persistedPipelineMode == null) {
      stages = jenkinsClient.getStages(mirror.getJenkinsJob(), mirror.getJenkinsBuildNumber());
      pipelineBuild = stages.isPipeline();
      mirror.setPipelineMode(pipelineBuild);
      // Persist the decision now so a freestyle poll with no new log does not re-probe wfapi forever.
      mirrorStore.saveMirror(mirror);
      LOG.debug(TeamCityNodeLog.currentNode(teamCityNodes) + " [Jenkins Bridge DEBUG] " + mirror.getJenkinsBuildKey()
          + " classified as " + (pipelineBuild ? "Pipeline" : "freestyle"));
    } else {
      pipelineBuild = persistedPipelineMode;
    }

    @Nullable JenkinsPipelineGraph graph = pipelineBuild ? loadPipelineGraph(jenkinsClient, mirror) : null;

    ensureJenkinsBuildParametersLoaded(jenkinsClient, mirror);

    // Try to fetch any existing VCS info before queueing to pin the correct branch name
    // TODO: Check whether this API call can be merged with another one to prevent unnecessary network communication
    JenkinsVcsInfo queueVcsInfo = null;
    if (shouldLoadQueueVcs(mirror, buildInfo)) {
      queueVcsInfo = loadBuildVcs(jenkinsClient, mirror);
    }

    long teamCityBuildId =
        mirrorService.ensureTeamCityBuild(mirror, connectionId, buildInfo, graph, queueVcsInfo);

    mirrorService.ensureRunningDataSent(mirror, teamCityBuildId);
    mirrorService.ensureMetadataLogSent(mirror, teamCityBuildId);
    mirrorService.syncBuildNumber(mirror);

    if (pipelineBuild) {
      // A persisted classification does not include the stage payload, so load stages on polls
      // where classification happened before this invocation.
      if (stages == null) {
        stages = jenkinsClient.getStages(mirror.getJenkinsJob(), mirror.getJenkinsBuildNumber());
      }
      if (graph != null) {
        mirrorService.syncPipelineGraph(mirror, teamCityBuildId, graph);
      }
      LOG.debug(TeamCityNodeLog.currentNode(teamCityNodes) + " [Jenkins Bridge DEBUG] Syncing " + stages.getStages().size()
          + " Pipeline stage(s) for " + mirror.getJenkinsBuildKey());
      mirrorService.syncStages(mirror, teamCityBuildId, stages, jenkinsClient);
    } else {
      long start = Math.max(0L, mirror.getLastLogOffset());
      JenkinsLogChunk logChunk = jenkinsClient.getProgressiveLog(
          mirror.getJenkinsJob(), mirror.getJenkinsBuildNumber(), start);
      LOG.debug(TeamCityNodeLog.currentNode(teamCityNodes) + " [Jenkins Bridge DEBUG] Fetched " + logChunk.getText().length()
          + " new console character(s) for " + mirror.getJenkinsBuildKey()
          + " from byte offset " + start + " (nextStart=" + logChunk.getNextStart() + ")");
      mirrorService.syncLogs(mirror, teamCityBuildId, logChunk);
    }

    if (buildInfo.isBuilding()) {
      return;
    }

    if (shouldSyncFinishedData(buildInfo, mirror, mirror.isTestsSynced())) {
      JenkinsTestReport testReport = jenkinsClient.getTestReport(mirror.getJenkinsJob(), mirror.getJenkinsBuildNumber());
      LOG.debug(TeamCityNodeLog.currentNode(teamCityNodes) + " [Jenkins Bridge DEBUG] Read " + testReport.getTestCount()
          + " Jenkins test(s) for " + mirror.getJenkinsBuildKey());
      mirrorService.syncTestsIfNeeded(mirror, teamCityBuildId, testReport);
    }
    syncArtifactsIfNeeded(jenkinsClient, buildInfo, mirror, teamCityBuildId);
    if (shouldSyncFinishedData(buildInfo, mirror, mirror.isVcsSynced())) {
      JenkinsVcsInfo vcsInfo = loadBuildVcs(jenkinsClient, mirror);
      if (vcsInfo != null) {
        LOG.debug("Read " + vcsInfo.repositories().size() + " Jenkins VCS repository(ies) for " + mirror.getJenkinsBuildKey());
        mirrorService.syncVcsIfNeeded(mirror, vcsInfo);
      }
    }
    mirrorService.finishBuildIfNeeded(mirror, teamCityBuildId, buildInfo);
    mirrorService.ensureRetrospectivePipelineChain(mirror, teamCityBuildId, graph);
  }

  @Nullable
  private JenkinsVcsInfo loadBuildVcs(JenkinsClient jenkinsClient, BuildMirror mirror) throws IOException {
    try {
      return jenkinsClient.getBuildVcs(mirror.getJenkinsJob(), mirror.getJenkinsBuildNumber());
    } catch (BridgeHttpException | JenkinsDataException e) {
      LOG.warn("Failed to read VCS for build " + mirror.getJenkinsBuildKey(), e);
      mirrorService.recordVcsFetchFailure(mirror, e);
      return null;
    }
  }

  private boolean shouldLoadQueueVcs(BuildMirror mirror, JenkinsBuildInfo buildInfo) {
    return mirror.getTeamCityBuildId() == null && !buildInfo.isBuilding();
  }

  @Nullable
  private JenkinsPipelineGraph loadPipelineGraph(JenkinsClient jenkinsClient, BuildMirror mirror)
      throws BridgeHttpException, JenkinsDataException, IOException {
    JenkinsPipelineGraph graph = jenkinsClient.getPipelineGraph(
        mirror.getJenkinsJob(), mirror.getJenkinsBuildNumber(), mirror.getJenkinsBuildKey());
    if (graph == null) {
      LOG.warn("[Jenkins Bridge DEBUG] Jenkins returned no Pipeline graph for "
          + mirror.getJenkinsBuildKey());
      return null;
    }
    LOG.debug(TeamCityNodeLog.currentNode(teamCityNodes) + " [Jenkins Bridge DEBUG] Pipeline graph for " + mirror.getJenkinsBuildKey()
        + " has source " + graph.getSource()
        + ", confidence " + graph.getConfidence()
        + ", " + graph.getNodes().size() + " node(s), topologyHash=" + graph.getTopologyHash());
    mirror.setPipelineGraph(graph);
    mirrorStore.saveMirror(mirror);
    LOG.debug(TeamCityNodeLog.currentNode(teamCityNodes) + " [Jenkins Bridge DEBUG] Delaying native Pipeline chain creation for "
        + mirror.getJenkinsBuildKey() + " until Jenkins finishes so the WFAPI graph is complete");
    return graph;
  }

  private void syncArtifactsIfNeeded(JenkinsClient jenkinsClient, JenkinsBuildInfo buildInfo,
                                    BuildMirror mirror, long teamCityBuildId)
      throws BridgeHttpException, JenkinsDataException, IOException {
    if (!shouldSyncFinishedData(buildInfo, mirror, mirror.isArtifactsSynced())) {
      return;
    }
    try {
      JenkinsArtifacts artifacts = jenkinsClient.getArtifacts(
          mirror.getJenkinsJob(), mirror.getJenkinsBuildNumber());
      LOG.debug(TeamCityNodeLog.currentNode(teamCityNodes) + " [Jenkins Bridge DEBUG] Read " + artifacts.size()
          + " Jenkins artifact(s) for " + mirror.getJenkinsBuildKey());
      mirrorService.syncArtifactMetadataIfNeeded(mirror, teamCityBuildId, artifacts);
    } catch (BridgeHttpException | JenkinsDataException e) {
      LOG.error("Jenkins Bridge: artifact mirroring failed for "
          + mirror.getJenkinsBuildKey() + "; finishing will continue", e);
      mirror.setArtifactsSynced(false);
      mirror.setArtifactSyncError(e.getClass().getSimpleName()
          + (e.getMessage() == null ? "" : ": " + e.getMessage()));
      mirrorStore.saveMirror(mirror);
    }
  }

  private boolean shouldSyncFinishedData(JenkinsBuildInfo buildInfo, BuildMirror mirror,
                                         boolean dataAlreadySynced) {
    return !buildInfo.isBuilding()
        && !dataAlreadySynced
        && mirror.getSyncState() != SyncState.TEAMCITY_FINISHED;
  }

  private void ensureJenkinsBuildParametersLoaded(JenkinsClient jenkinsClient, BuildMirror mirror)
      throws BridgeHttpException, JenkinsDataException, IOException {
    if (mirror.getTeamCityBuildId() != null || mirror.isJenkinsBuildParametersLoaded()) {
      return;
    }

    JenkinsBuildParameters parameters =
        jenkinsClient.getBuildParameters(mirror.getJenkinsJob(), mirror.getJenkinsBuildNumber());
    mirror.setJenkinsBuildParameters(parameters.asMap());
    mirrorStore.saveMirror(mirror);
    LOG.debug(TeamCityNodeLog.currentNode(teamCityNodes) + " [Jenkins Bridge DEBUG] Read " + parameters.getParameters().size()
        + " Jenkins build parameter(s) for " + mirror.getJenkinsBuildKey());
  }

  static final class JobPollOutcome {
    private BridgeHttpException connectivityFailure;

    void recordJenkinsFailure(Exception failure) {
      if (!(failure instanceof BridgeHttpException)) {
        return;
      }
      BridgeHttpException httpFailure = (BridgeHttpException) failure;
      if (connectivityFailure == null && isConnectivityFailure(httpFailure)) {
        connectivityFailure = httpFailure;
      }
    }

    void merge(JobPollOutcome other) {
      if (other == null) {
        return;
      }
      if (connectivityFailure == null) {
        connectivityFailure = other.connectivityFailure;
      }
    }

    boolean isSuccessful() {
      return connectivityFailure == null;
    }

    BridgeHttpException getConnectivityFailure() {
      return connectivityFailure;
    }
  }
}
