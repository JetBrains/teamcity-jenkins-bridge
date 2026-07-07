package com.jetbrains.teamcity.jenkinsbridge.polling;

import com.jetbrains.teamcity.jenkinsbridge.feature.MirroredJobProvider;
import com.jetbrains.teamcity.jenkinsbridge.jenkins.JenkinsClient;
import com.jetbrains.teamcity.jenkinsbridge.persistence.BuildMirror;
import com.jetbrains.teamcity.jenkinsbridge.persistence.BuildMirrorStore;
import com.jetbrains.teamcity.jenkinsbridge.persistence.SyncState;
import com.jetbrains.teamcity.jenkinsbridge.model.JenkinsBuildInfo;
import com.jetbrains.teamcity.jenkinsbridge.model.JenkinsBuildParameters;
import com.jetbrains.teamcity.jenkinsbridge.model.JenkinsArtifacts;
import com.jetbrains.teamcity.jenkinsbridge.model.JenkinsLogChunk;
import com.jetbrains.teamcity.jenkinsbridge.model.JenkinsPipelineGraph;
import com.jetbrains.teamcity.jenkinsbridge.model.JenkinsStages;
import com.jetbrains.teamcity.jenkinsbridge.model.JenkinsTestReport;
import com.jetbrains.teamcity.jenkinsbridge.settings.JenkinsBridgeSettings;
import com.jetbrains.teamcity.jenkinsbridge.settings.JenkinsBridgeSettingsProvider;
import com.jetbrains.teamcity.jenkinsbridge.settings.MirroredJob;
import com.jetbrains.teamcity.jenkinsbridge.teamcity.TeamCityBuildMirrorService;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import com.intellij.openapi.diagnostic.Logger;

public class JenkinsBridgePollingService {
  private static final Logger LOG = Logger.getInstance(JenkinsBridgePollingService.class.getName());

  private final JenkinsBridgeSettingsProvider settingsProvider;
  private final JenkinsClient jenkinsClient;
  private final TeamCityBuildMirrorService mirrorService;
  private final BuildMirrorStore mirrorStore;
  private final MirroredJobProvider mirroredJobProvider;
  private final AtomicBoolean started = new AtomicBoolean(false);
  private ScheduledExecutorService executorService;

  public JenkinsBridgePollingService(
      JenkinsBridgeSettingsProvider settingsProvider,
      JenkinsClient jenkinsClient,
      TeamCityBuildMirrorService mirrorService,
      BuildMirrorStore mirrorStore,
      MirroredJobProvider mirroredJobProvider
  ) {
    this.settingsProvider = settingsProvider;
    this.jenkinsClient = jenkinsClient;
    this.mirrorService = mirrorService;
    this.mirrorStore = mirrorStore;
    this.mirroredJobProvider = mirroredJobProvider;
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

    LOG.info("Starting Jenkins Bridge polling; state file: " + mirrorStore.getStateFile());
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
    if (!settings.hasJenkinsConnection()) {
      throw new IllegalStateException("Jenkins Bridge is missing the global Jenkins server URL (jenkinsUrl)");
    }

    List<MirroredJob> mirroredJobs = mirroredJobProvider.discoverMirroredJobs();
    LOG.info("[Jenkins Bridge DEBUG] Discovered " + mirroredJobs.size() + " mirrored job(s)");

    for (MirroredJob mirroredJob : mirroredJobs) {
      try {
        pollJob(mirroredJob, settings);
      } catch (Exception e) {
        // Isolate per-job failures so one broken job does not abort the rest of the cycle.
        LOG.warn("Jenkins Bridge: failed to poll " + mirroredJob.describeForLog(), e);
      }
    }
  }

  private void pollJob(MirroredJob mirroredJob, JenkinsBridgeSettings settings) throws Exception {
    if (!mirroredJob.hasMinimumConfiguration()) {
      LOG.warn(mirroredJob.describeMinimumConfigurationProblem());
      return;
    }

    String job = mirroredJob.getJenkinsJob();
    String keyPrefix = mirroredJob.getMirrorKeyPrefix();
    int recentBuildLimit = mirroredJob.getEffectiveRecentBuildLimit(settings.getRecentBuildLimit());

    // Fetch the most recent builds (Jenkins caps this at the 100 newest). The timestamp is part of
    // the bridge identity because Jenkins build numbers can be reused after build history is reset.
    List<JenkinsBuildInfo> builds = jenkinsClient.getBuilds(job);
    if (builds.isEmpty()) {
      LOG.info("[Jenkins Bridge DEBUG] Jenkins job " + job + " has no builds yet");
      return;
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
      LOG.info("[Jenkins Bridge DEBUG] Gap detected for job " + job
          + " (lastSeen=" + lastSeen + ", oldest fetched=" + oldest
          + "); fetching all build numbers");
      builds = jenkinsClient.getAllBuilds(job);
      if (builds.isEmpty()) {
        return;
      }
      latest = maxBuildNumber(builds);
    }

    List<JenkinsBuildInfo> toProcess = new ArrayList<JenkinsBuildInfo>();
    for (JenkinsBuildInfo build : builds) {
      if (shouldProcessDiscoveredBuild(build, keyPrefix, lastSeen, coldStartAfter, coldStart, resetDetected)) {
        toProcess.add(build);
      }
    }
    sortByBuildNumber(toProcess);
    LOG.info("[Jenkins Bridge DEBUG] " + mirroredJob.describeForLog() + ": " + toProcess.size()
        + " build(s) selected after watermark " + lastSeen);

    Set<Integer> handled = new HashSet<Integer>();
    int maxNumber = lastSeen;

    for (JenkinsBuildInfo build : toProcess) {
      maxNumber = Math.max(maxNumber, build.getNumber());
      syncDiscoveredBuild(mirroredJob, build);
      handled.add(build.getNumber());
    }

    // Keep syncing builds that are still in progress but already past the watermark.
    List<BuildMirror> active = mirroredJob.isLegacy()
        ? mirrorStore.getActiveMirrors(job)
        : mirrorStore.getActiveMirrors(mirroredJob.getTeamCityBuildTypeExternalId(), job);
    for (BuildMirror mirror : active) {
      if (handled.contains(mirror.getJenkinsBuildNumber())) {
        continue;
      }
      syncActiveMirror(mirror);
    }

    if (!resetDetected && maxNumber > mirrorStore.getLastSeenBuildNumber(keyPrefix)) {
      mirrorStore.setLastSeenBuildNumber(keyPrefix, maxNumber);
    }
  }

  private boolean shouldProcessDiscoveredBuild(
      JenkinsBuildInfo build,
      String keyPrefix,
      int lastSeen,
      int coldStartAfter,
      boolean coldStart,
      boolean resetDetected
  ) throws Exception {
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
  private void syncDiscoveredBuild(MirroredJob mirroredJob, JenkinsBuildInfo discoveredBuild) {
    String job = mirroredJob.getJenkinsJob();
    BuildMirror mirror = null;
    int buildNumber = discoveredBuild.getNumber();
    try {
      JenkinsBuildInfo buildInfo = jenkinsClient.getBuildInfo(job, buildNumber);
      String mirrorKey = BuildMirrorStore.buildKey(mirroredJob.getMirrorKeyPrefix(), buildInfo);
      mirror = mirrorStore.getOrCreateMirror(mirrorKey, job, mirroredJob.getTeamCityBuildTypeExternalId(), buildInfo);
      LOG.info("[Jenkins Bridge DEBUG] Syncing Jenkins build " + mirror.getJenkinsBuildKey()
          + " in state " + mirror.getSyncState()
          + " with TeamCity build id " + mirror.getTeamCityBuildId());
      syncBuild(mirror, buildInfo);
      LOG.info("[Jenkins Bridge DEBUG] Synced Jenkins build " + mirror.getJenkinsBuildKey()
          + " now in state " + mirror.getSyncState()
          + " with TeamCity build id " + mirror.getTeamCityBuildId());
    } catch (Exception e) {
      if (mirror != null) {
        mirrorStore.markBuildError(mirror, e);
      }
      LOG.warn("Failed to sync Jenkins build " + job + "#" + buildNumber, e);
    }
  }

  private void syncActiveMirror(BuildMirror mirror) {
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
      syncBuild(mirror, buildInfo);
    } catch (Exception e) {
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

  private void syncBuild(BuildMirror mirror, JenkinsBuildInfo buildInfo) throws Exception {
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
      if (buildInfo.isBuilding() && mirror.getTeamCityBuildId() == null) {
        mirror.setPipelineGraph(graph);
        mirrorStore.saveMirror(mirror);
        LOG.info("[Jenkins Bridge DEBUG] Delaying native Pipeline chain creation for "
            + mirror.getJenkinsBuildKey()
            + " until Jenkins finishes so the WFAPI graph is complete");
        return;
      }
    }

    ensureJenkinsBuildParametersLoaded(mirror);

    long teamCityBuildId = mirrorService.ensureTeamCityBuild(mirror, buildInfo, graph);
    mirrorService.ensureRunningDataSent(mirror, teamCityBuildId);
    mirrorService.ensureMetadataLogSent(mirror, teamCityBuildId);

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

    if (!buildInfo.isBuilding()
        && !mirror.isTestsSynced()
        && mirror.getSyncState() != SyncState.TEAMCITY_FINISHED) {
      JenkinsTestReport testReport = jenkinsClient.getTestReport(mirror.getJenkinsJob(), mirror.getJenkinsBuildNumber());
      LOG.info("[Jenkins Bridge DEBUG] Read " + testReport.getTestCount()
          + " Jenkins test(s) for " + mirror.getJenkinsBuildKey());
      mirrorService.syncTestsIfNeeded(mirror, teamCityBuildId, testReport);
    }
    if (!buildInfo.isBuilding()
        && !mirror.isArtifactsSynced()
        && mirror.getSyncState() != SyncState.TEAMCITY_FINISHED) {
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
    mirrorService.finishBuildIfNeeded(mirror, teamCityBuildId, buildInfo);
  }

  private void ensureJenkinsBuildParametersLoaded(BuildMirror mirror) throws Exception {
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
}
