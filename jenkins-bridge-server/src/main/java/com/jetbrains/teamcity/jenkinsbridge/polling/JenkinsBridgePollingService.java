package com.jetbrains.teamcity.jenkinsbridge.polling;

import com.jetbrains.teamcity.jenkinsbridge.feature.MirroredJobProvider;
import com.jetbrains.teamcity.jenkinsbridge.http.BridgeHttpException;
import com.jetbrains.teamcity.jenkinsbridge.jenkins.JenkinsClient;
import com.jetbrains.teamcity.jenkinsbridge.jenkins.JenkinsClientFactory;
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
import com.jetbrains.teamcity.jenkinsbridge.util.Utilities;

import jetbrains.buildServer.serverSide.ProjectManager;
import jetbrains.buildServer.serverSide.SBuildType;

import com.intellij.openapi.diagnostic.Logger;
import jetbrains.buildServer.serverSide.BuildPromotion;
import jetbrains.buildServer.serverSide.SQueuedBuild;

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
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class JenkinsBridgePollingService {
  private static final Logger LOG = Logger.getInstance(JenkinsBridgePollingService.class.getName());

  private final JenkinsBridgeSettingsProvider settingsProvider;
  private final JenkinsClientFactory jenkinsClientFactory;
  private final ProjectManager projectManager;
  private final TeamCityBuildMirrorService mirrorService;
  private final BuildMirrorStore mirrorStore;
  private final MirroredJobProvider mirroredJobProvider;
  private final TeamCityRunningBuildLocator buildLocator;
  private final JenkinsBridgeSystemProblemReporter systemProblemReporter;
  private final JenkinsJobCoordinator jobCoordinator = new JenkinsJobCoordinator();
  private final Object systemProblemLifecycleLock = new Object();
  private final AtomicBoolean started = new AtomicBoolean(false);
  private ScheduledExecutorService executorService;

  public JenkinsBridgePollingService(
      JenkinsBridgeSettingsProvider settingsProvider,
      JenkinsClientFactory jenkinsClientFactory,
      ProjectManager projectManager,
      TeamCityBuildMirrorService mirrorService,
      BuildMirrorStore mirrorStore,
      MirroredJobProvider mirroredJobProvider,
      TeamCityRunningBuildLocator buildLocator,
      JenkinsBridgeSystemProblemReporter systemProblemReporter
  ) {
    this.settingsProvider = settingsProvider;
    this.jenkinsClientFactory = jenkinsClientFactory;
    this.projectManager = projectManager;
    this.mirrorService = mirrorService;
    this.mirrorStore = mirrorStore;
    this.mirroredJobProvider = mirroredJobProvider;
    this.buildLocator = buildLocator;
    this.systemProblemReporter = systemProblemReporter;
  }

  public void start() {
    JenkinsBridgeSettings settings = settingsProvider.load();
    LOG.info("[Jenkins Bridge DEBUG] Loaded settings: " + settings.describeForLog());

    if (!settings.isEnabled()) {
      LOG.info("Jenkins Bridge polling is disabled");
      return;
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

    LOG.info("[Jenkins Bridge DEBUG] Scheduling poller every " + settings.getPollSeconds() + " second(s)");
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
    try {
      LOG.info("[Jenkins Bridge DEBUG] Poll cycle started");
      pollOnce();
      mirrorStore.markPollSuccess();
      LOG.info("[Jenkins Bridge DEBUG] Poll cycle completed");
    } catch (Exception e) {
      mirrorStore.markPollError(e);
      LOG.warn("Jenkins Bridge polling failed", e);
    }
  }

  private void pollOnce() throws Exception {
    JenkinsBridgeSettings settings = settingsProvider.load();
    List<MirroredJob> mirroredJobs = mirroredJobProvider.discoverMirroredJobs();
    if (systemProblemReporter != null) {
      systemProblemReporter.reconcile(mirroredJobs);
    }
    LOG.info("[Jenkins Bridge DEBUG] Discovered " + mirroredJobs.size() + " mirrored job(s)");
    resolvePendingTriggers(mirroredJobs, settings);

    for (MirroredJob mirroredJob : mirroredJobs) {
      try {
        pollPipeline(mirroredJob);
      } catch (BridgeHttpException e) {
        reportAuthoritativePollingFailure(mirroredJob, e);
        LOG.warn("Jenkins Bridge: failed to poll " + mirroredJob.describeForLog(), e);
      } catch (Exception e) {
        // Isolate per-job failures so one broken job does not abort the rest of the cycle.
        LOG.warn("Jenkins Bridge: failed to poll " + mirroredJob.describeForLog(), e);
      }
    }
  }

  private void resolvePendingTriggers(List<MirroredJob> mirroredJobs, JenkinsBridgeSettings settings) throws Exception {
    List<PendingTrigger> pendingTriggers = mirrorStore.getPendingTriggers();
    if (pendingTriggers.isEmpty()) {
      return;
    }

    for (PendingTrigger pendingTrigger : pendingTriggers) {
      try {
        if (isPendingTriggerExpired(pendingTrigger, settings.getPendingTriggerTimeoutMinutes(), Instant.now())) {
          if (cancelQueuedPromotion(pendingTrigger,
              "Jenkins Bridge pending trigger expired after "
                  + settings.getPendingTriggerTimeoutMinutes() + " minute(s)")) {
            mirrorStore.removePendingTrigger(pendingTrigger.getTeamCityPromotionId());
            LOG.warn("Jenkins Bridge expired pending TeamCity promotion "
                + pendingTrigger.getTeamCityPromotionId());
          } else {
            LOG.warn("Jenkins Bridge could not remove expired pending TeamCity promotion "
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
        if (pendingTrigger.getJenkinsQueueId() < 0) {
          cancelQueuedPromotion(pendingTrigger, "Jenkins Bridge has no valid persisted Jenkins queue id");
          mirrorStore.removePendingTrigger(pendingTrigger.getTeamCityPromotionId());
          continue;
        }

        JenkinsQueueBuildResolution resolution =
            jenkinsClient.resolveQueuedBuildNumber(pendingTrigger.getQueueItemUrl());
        if (resolution.isPending()) {
          continue;
        }

        if (resolution.isCancelled()) {
          cancelQueuedPromotion(pendingTrigger, "Jenkins queue item was cancelled");
          mirrorStore.removePendingTrigger(pendingTrigger.getTeamCityPromotionId());
          continue;
        }

        bindResolvedTrigger(jenkinsClient, pendingTrigger, mirroredJob, resolution.getBuildNumber());
      } catch (Exception e) {
        LOG.warn("Jenkins Bridge failed to resolve pending TeamCity promotion "
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
    } catch (RuntimeException ignored) {
      LOG.warn("Jenkins Bridge could not parse pending trigger creation time for promotion "
          + pendingTrigger.getTeamCityPromotionId() + "; retaining it");
      return false;
    }
  }

  private PendingTrigger normalizePendingTrigger(JenkinsClient jenkinsClient, PendingTrigger pendingTrigger)
      throws Exception {
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
                                   MirroredJob mirroredJob, int buildNumber) throws Exception {
    synchronized (jobCoordinator.lockFor(pendingTrigger.getJenkinsController(), pendingTrigger.getJenkinsJob())) {
      JenkinsBuildInfo buildInfo = jenkinsClient.getBuildInfo(pendingTrigger.getJenkinsJob(), buildNumber);
      if (pendingTrigger.getJenkinsQueueId() >= 0
          && buildInfo.getQueueId() >= 0
          && pendingTrigger.getJenkinsQueueId() != buildInfo.getQueueId()) {
        LOG.warn("Jenkins Bridge refused queue-item binding for TeamCity promotion "
            + pendingTrigger.getTeamCityPromotionId() + ": queue id changed from "
            + pendingTrigger.getJenkinsQueueId() + " to " + buildInfo.getQueueId());
        cancelQueuedPromotion(pendingTrigger, "Jenkins queue id did not match the triggered run");
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
        cancelQueuedPromotion(pendingTrigger, "Jenkins build is already owned by another TeamCity promotion");
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
  }

  private boolean cancelQueuedPromotion(PendingTrigger pendingTrigger, String comment) {
    try {
      if (buildLocator == null) {
        LOG.warn("Jenkins Bridge cannot cancel TeamCity promotion "
            + pendingTrigger.getTeamCityPromotionId()
            + " because TeamCityRunningBuildLocator is not available");
        return false;
      }
      BuildPromotion promotion = buildLocator.findPromotion(pendingTrigger.getTeamCityPromotionId());
      if (promotion == null) {
        return false;
      }
      SQueuedBuild queuedBuild = promotion.getQueuedBuild();
      if (queuedBuild != null) {
        queuedBuild.removeFromQueue(null, comment);
        return true;
      }
      return false;
    } catch (Exception e) {
      LOG.warn("Jenkins Bridge failed to cancel TeamCity promotion "
          + pendingTrigger.getTeamCityPromotionId(), e);
      return false;
    }
  }

  /**
   * Calls {@code pollJob} once for regular pipelines, and once for each branch in the case of a multibranch pipeline.
   */
  private void pollPipeline(MirroredJob mirroredJob) throws Exception {
    if (!mirroredJob.hasMinimumConfiguration()) {
      LOG.warn(mirroredJob.describeMinimumConfigurationProblem());
      return;
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
      return;
    }

    JobPollOutcome outcome = pollJob(
        jenkinsClient, mirroredJob, jenkinsClient.getBuilds(mirroredJob.jenkinsJob()), recentBuildLimit);
    updateSystemProblem(mirroredJob, outcome);
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
                                 List<JenkinsBuildInfo> builds, int recentBuildLimit) throws Exception {
    JobPollOutcome outcome = new JobPollOutcome();
    String job = mirroredJob.jenkinsJob();
    String keyPrefix = mirroredJob.getMirrorKeyPrefix();

    // The most recent builds are fetched (Jenkins caps this at the 100 newest). The timestamp is part of
    // the bridge identity because Jenkins build numbers can be reused after the build history is reset.
    if (builds.isEmpty()) {
      LOG.info("[Jenkins Bridge DEBUG] Jenkins job " + job + " has no builds yet");
      return outcome;
    }

    int latest = maxBuildNumber(builds);
    int oldest = minBuildNumber(builds);
    int lastSeen = mirrorStore.getLastSeenBuildNumber(keyPrefix);
    int coldStartAfter = lastSeen;
    boolean coldStart = lastSeen == 0;
    boolean resetDetected = false;

    if (coldStart) {
      // Cold start: don't replay the whole history. Backfill only the most recent builds, where
      // recentBuildLimit is the backfill depth (default 1 = start from the latest build).
      coldStartAfter = Math.max(0, latest - recentBuildLimit);
      LOG.info("[Jenkins Bridge DEBUG] Cold start for job " + job
          + "; backfilling from build " + (coldStartAfter + 1) + " (latest=" + latest + ")");
    } else if (latest < lastSeen) {
      resetDetected = true;
      LOG.warn("Jenkins Bridge detected build-number reset for job " + job
          + " (lastSeen=" + lastSeen + ", latest=" + latest
          + "); processing recent builds by timestamped identity");
    } else if (oldest > lastSeen + 1) {
      // We were running before but more than ~100 builds have happened since, so the cheap
      // builds view no longer reaches back to lastSeen. Escalate to allBuilds so we
      // never skip a build (rare; only after a long outage).
      // TODO: Add a cap of ~1000 to not accidentally load thousands of builds
      LOG.info("[Jenkins Bridge DEBUG] Gap detected for job " + job
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
      if (shouldProcessDiscoveredBuild(jenkinsClient, build, keyPrefix, lastSeen, coldStartAfter, coldStart, resetDetected)) {
        toProcess.add(build);
      }
    }
    sortByBuildNumber(toProcess);
    LOG.info("[Jenkins Bridge DEBUG] " + mirroredJob.describeForLog() + ": " + toProcess.size()
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

    // Keep finished mirrors until the watermark has been persisted successfully. If persistence
    // fails, the mirror is still available for restore-by-key on the next poll instead of being
    // pruned while the watermark remains behind it.
    int prunedCount = mirrorStore.pruneFinishedMirrors().size();
    LOG.info("[Jenkins Bridge DEBUG] " + mirroredJob.describeForLog() + ": " + prunedCount + " build(s) pruned");
    return outcome;
  }

  private boolean shouldProcessDiscoveredBuild(
      JenkinsClient jenkinsClient,
      JenkinsBuildInfo build,
      String keyPrefix,
      int lastSeen,
      int coldStartAfter,
      boolean coldStart,
      boolean resetDetected
  ) throws Exception {
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

    if (coldStart) {
      return build.getNumber() > coldStartAfter;
    }

    if (resetDetected) {
      return true;
    }

    if (build.getNumber() <= lastSeen) {
      return false;
    }

    BuildMirror legacy = mirrorStore.findMirror(BuildMirrorStore.buildKey(keyPrefix, build.getNumber()));
    return legacy == null;
  }

  // Syncs a newly discovered Jenkins build, isolating failures so one bad build does not abort the poll cycle.
  private boolean syncDiscoveredBuild(JenkinsClient jenkinsClient, MirroredJob mirroredJob,
                                   JenkinsBuildInfo discoveredBuild, JobPollOutcome outcome) {
    String job = mirroredJob.jenkinsJob();
    BuildMirror mirror = null;
    boolean discoveredAndTracked = false;
    int buildNumber = discoveredBuild.getNumber();
    synchronized (jobCoordinator.lockFor(jenkinsClient.getControllerIdentity(), job)) {
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
            LOG.info("Jenkins Bridge ignored pending ownership for queue id "
                + buildInfo.getQueueId() + " while processing " + mirror.getJenkinsBuildKey());
            pending = null;
          }
        }
        if (pending != null) {
          if (mirror.getTeamCityBuildId() != null
              && mirror.getTeamCityBuildId() != pending.getTeamCityPromotionId()) {
            cancelQueuedPromotion(pending, "Jenkins build is already owned by another TeamCity promotion");
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
          LOG.info("Jenkins Bridge claimed Jenkins build " + mirror.getJenkinsBuildKey()
              + " for TeamCity promotion " + pending.getTeamCityPromotionId()
              + " by queue id " + buildInfo.getQueueId());
        }
        LOG.info("[Jenkins Bridge DEBUG] Syncing Jenkins build " + mirror.getJenkinsBuildKey()
            + " in state " + mirror.getSyncState()
            + " with TeamCity build id " + mirror.getTeamCityBuildId());
        syncBuild(jenkinsClient, mirroredJob.connectionId(), mirror, buildInfo);
        LOG.info("[Jenkins Bridge DEBUG] Synced Jenkins build " + mirror.getJenkinsBuildKey()
            + " now in state " + mirror.getSyncState()
            + " with TeamCity build id " + mirror.getTeamCityBuildId());
      } catch (Exception e) {
        outcome.recordJenkinsFailure(e);
        if (mirror != null) {
          mirrorStore.markBuildError(mirror, e);
        }
        LOG.warn("Failed to sync Jenkins build " + job + "#" + buildNumber, e);
      }
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
    } catch (Exception e) {
      outcome.recordJenkinsFailure(e);
      mirrorStore.markBuildError(mirror, e);
      LOG.warn("Failed to sync Jenkins build " + mirror.getJenkinsBuildKey(), e);
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
                         JenkinsBuildInfo buildInfo) throws Exception {
    // Decide once whether this build is a Jenkins Pipeline (mirror stages as build steps) or a
    // freestyle build (mirror the flat progressive console log). The decision is sticky per build.
    Boolean pipelineMode = mirror.getPipelineMode();
    JenkinsStages stages = null;
    JenkinsPipelineGraph graph = null;
    if (pipelineMode == null) {
      stages = jenkinsClient.getStages(mirror.getJenkinsJob(), mirror.getJenkinsBuildNumber());
      pipelineMode = stages.isPipeline();
      mirror.setPipelineMode(pipelineMode);
      // Persist the decision now so a freestyle poll with no new log does not re-probe wfapi forever.
      mirrorStore.saveMirror(mirror);
      LOG.info("[Jenkins Bridge DEBUG] " + mirror.getJenkinsBuildKey()
          + " pipelineMode=" + pipelineMode);
    }

    if (pipelineMode) {
      graph = jenkinsClient.getPipelineGraph(
          mirror.getJenkinsJob(), mirror.getJenkinsBuildNumber(), mirror.getJenkinsBuildKey());
      LOG.info("[Jenkins Bridge DEBUG] Pipeline graph for " + mirror.getJenkinsBuildKey()
          + " has source " + graph.getSource()
          + ", confidence " + graph.getConfidence()
          + ", " + graph.getNodes().size() + " node(s), topologyHash=" + graph.getTopologyHash());
      // Mirror Pipeline builds LIVE as a single running TeamCity build: the top build shows RUNNING for
      // the whole Jenkins run and streams stage blocks + current-stage status as they arrive. Persist the
      // Blue Ocean graph so the pipeline graph tab can render it. Native TeamCity build-chain creation is
      // a separate track and is intentionally not run on this branch.
      if (graph != null) {
        mirror.setPipelineGraph(graph);
        mirrorStore.saveMirror(mirror);
        LOG.info("[Jenkins Bridge DEBUG] Delaying native Pipeline chain creation for "
            + mirror.getJenkinsBuildKey()
            + " until Jenkins finishes so the WFAPI graph is complete");
      }
    }

    ensureJenkinsBuildParametersLoaded(jenkinsClient, mirror);

    // Try to fetch any existing VCS info before queueing to pin the correct branch name
    // TODO: Check whether this API call can be merged with another one to prevent unnecessary network communication
    JenkinsVcsInfo queueVcsInfo = null;
    if (mirror.getTeamCityBuildId() == null && !buildInfo.isBuilding()) {
      try {
        queueVcsInfo = jenkinsClient.getBuildVcs(mirror.getJenkinsJob(), mirror.getJenkinsBuildNumber());
      } catch (Exception e) {
        LOG.warn("Failed to read VCS before queueing for build "
            + mirror.getJenkinsBuildKey() + e);
      }
    }

    long teamCityBuildId =
        mirrorService.ensureTeamCityBuild(mirror, connectionId, buildInfo, graph, queueVcsInfo);

    mirrorService.ensureRunningDataSent(mirror, teamCityBuildId);
    mirrorService.ensureMetadataLogSent(mirror, teamCityBuildId);
    mirrorService.syncBuildNumber(mirror);

    if (pipelineMode) {
      if (stages == null) {
        stages = jenkinsClient.getStages(mirror.getJenkinsJob(), mirror.getJenkinsBuildNumber());
      }
      mirrorService.syncPipelineGraph(mirror, teamCityBuildId, graph);
      LOG.info("[Jenkins Bridge DEBUG] Syncing " + stages.getStages().size()
          + " Pipeline stage(s) for " + mirror.getJenkinsBuildKey());
      mirrorService.syncStages(mirror, teamCityBuildId, stages, jenkinsClient);
    } else {
      long start = Math.max(0L, mirror.getLastLogOffset());
      JenkinsLogChunk logChunk = jenkinsClient.getProgressiveLog(
          mirror.getJenkinsJob(), mirror.getJenkinsBuildNumber(), start);
      LOG.info("[Jenkins Bridge DEBUG] Fetched " + logChunk.getText().length()
          + " new console character(s) for " + mirror.getJenkinsBuildKey()
          + " from byte offset " + start + " (nextStart=" + logChunk.getNextStart() + ")");
      mirrorService.syncLogs(mirror, teamCityBuildId, logChunk);
    }

    if (buildInfo.isBuilding()) {
      return;
    }

    if (!mirror.isTestsSynced() && mirror.getSyncState() != SyncState.TEAMCITY_FINISHED) {
      JenkinsTestReport testReport = jenkinsClient.getTestReport(mirror.getJenkinsJob(), mirror.getJenkinsBuildNumber());
      LOG.info("[Jenkins Bridge DEBUG] Read " + testReport.getTestCount()
          + " Jenkins test(s) for " + mirror.getJenkinsBuildKey());
      mirrorService.syncTestsIfNeeded(mirror, teamCityBuildId, testReport);
    }
    if (!mirror.isArtifactsSynced() && mirror.getSyncState() != SyncState.TEAMCITY_FINISHED) {
      try {
        JenkinsArtifacts artifacts = jenkinsClient.getArtifacts(mirror.getJenkinsJob(), mirror.getJenkinsBuildNumber());
        LOG.info("[Jenkins Bridge DEBUG] Read " + artifacts.size()
            + " Jenkins artifact(s) for " + mirror.getJenkinsBuildKey());
        mirrorService.syncArtifactMetadataIfNeeded(mirror, teamCityBuildId, artifacts);
      } catch (Exception e) {
        LOG.warn("Jenkins Bridge: artifact mirroring failed for "
            + mirror.getJenkinsBuildKey() + "; finishing will continue", e);
        mirror.setArtifactsSynced(true);
        mirror.setArtifactSyncError(e.getClass().getSimpleName()
            + (e.getMessage() == null ? "" : ": " + e.getMessage()));
        try {
          mirrorStore.saveMirror(mirror);
        } catch (Exception saveError) {
          LOG.warn("Jenkins Bridge: failed to persist artifact sync failure for "
              + mirror.getJenkinsBuildKey(), saveError);
        }
      }
    }
    if (!buildInfo.isBuilding()
        && !mirror.isVcsSynced()
        && mirror.getSyncState() != SyncState.TEAMCITY_FINISHED) {
      JenkinsVcsInfo vcsInfo = jenkinsClient.getBuildVcs(mirror.getJenkinsJob(), mirror.getJenkinsBuildNumber());
      LOG.debug("Read " + vcsInfo.repositories().size() + " Jenkins VCS repository(ies) for " + mirror.getJenkinsBuildKey());
      mirrorService.syncVcsIfNeeded(mirror, vcsInfo);
    }
    mirrorService.finishBuildIfNeeded(mirror, teamCityBuildId, buildInfo);
    mirrorService.ensureRetrospectivePipelineChain(mirror, teamCityBuildId, graph);
  }

  private void ensureJenkinsBuildParametersLoaded(JenkinsClient jenkinsClient, BuildMirror mirror)
      throws Exception {
    if (mirror.getTeamCityBuildId() != null || mirror.isJenkinsBuildParametersLoaded()) {
      return;
    }

    JenkinsBuildParameters parameters =
        jenkinsClient.getBuildParameters(mirror.getJenkinsJob(), mirror.getJenkinsBuildNumber());
    mirror.setJenkinsBuildParameters(parameters.asMap());
    mirrorStore.saveMirror(mirror);
    LOG.info("[Jenkins Bridge DEBUG] Read " + parameters.getParameters().size()
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
