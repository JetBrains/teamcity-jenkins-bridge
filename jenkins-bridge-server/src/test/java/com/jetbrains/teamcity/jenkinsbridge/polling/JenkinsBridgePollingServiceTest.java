package com.jetbrains.teamcity.jenkinsbridge.polling;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.jetbrains.teamcity.jenkinsbridge.feature.MirroredJobProvider;
import com.jetbrains.teamcity.jenkinsbridge.jenkins.JenkinsClient;
import com.jetbrains.teamcity.jenkinsbridge.model.JenkinsBuildInfo;
import com.jetbrains.teamcity.jenkinsbridge.model.JenkinsBuildParameters;
import com.jetbrains.teamcity.jenkinsbridge.model.JenkinsArtifacts;
import com.jetbrains.teamcity.jenkinsbridge.model.JenkinsLogChunk;
import com.jetbrains.teamcity.jenkinsbridge.model.JenkinsPipelineGraph;
import com.jetbrains.teamcity.jenkinsbridge.model.JenkinsStages;
import com.jetbrains.teamcity.jenkinsbridge.model.JenkinsTestReport;
import com.jetbrains.teamcity.jenkinsbridge.model.JenkinsVcsInfo;
import com.jetbrains.teamcity.jenkinsbridge.persistence.BuildMirror;
import com.jetbrains.teamcity.jenkinsbridge.persistence.BuildMirrorStore;
import com.jetbrains.teamcity.jenkinsbridge.persistence.PendingTrigger;
import com.jetbrains.teamcity.jenkinsbridge.persistence.SyncState;
import com.jetbrains.teamcity.jenkinsbridge.settings.JenkinsBridgeSettings;
import com.jetbrains.teamcity.jenkinsbridge.settings.JenkinsBridgeSettingsProvider;
import com.jetbrains.teamcity.jenkinsbridge.settings.MirroredJob;
import com.jetbrains.teamcity.jenkinsbridge.teamcity.TeamCityBuildMirrorService;
import org.jetbrains.annotations.NotNull;
import org.junit.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Map;
import java.time.Instant;

import static com.jetbrains.teamcity.jenkinsbridge.persistence.BuildMirrorStoreTest.buildMockProjectManager;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class JenkinsBridgePollingServiceTest {
  @Test
  public void pendingTriggerExpiryUsesConfiguredLifetime() {
    PendingTrigger trigger = new PendingTrigger(
        7L, "job", "buildType", "http://jenkins/queue/item/1/", 1L,
        "http://jenkins", "2026-08-04T10:00:00Z");

    assertTrue(JenkinsBridgePollingService.isPendingTriggerExpired(
        trigger, 1440, Instant.parse("2026-08-05T10:00:00Z")));
    assertTrue(!JenkinsBridgePollingService.isPendingTriggerExpired(
        trigger, 1440, Instant.parse("2026-08-05T09:59:59Z")));
  }

  @Test
  public void pollPipelineProcessesBuildNumberResetByTimestampedIdentity() throws Exception {
    JenkinsBridgeSettingsProvider provider = providerWithTempStateFile();
    BuildMirrorStore store = new BuildMirrorStore(null, provider, buildMockProjectManager());
    store.setLastSeenBuildNumber("buildType::job", 500);

    FakeJenkinsClient jenkinsClient = new FakeJenkinsClient();
    jenkinsClient.addBuild(buildInfo(1, 1710000000001L));

    JenkinsBridgePollingService service = newService(provider, jenkinsClient, new CapturingMirrorService(), store);

    pollJob(service, jenkinsClient, new MirroredJob("conn1", "job", "buildType", "Build", 0, false));

    assertNotNull(store.findMirror("buildType::job#1@1710000000001"));
    assertEquals(500, store.getLastSeenBuildNumber("buildType::job"));
    assertEquals(1, jenkinsClient.getBuildInfoCalls);
  }

  @Test
  public void coldStartStillBackfillsOnlyRecentBuildLimit() throws Exception {
    JenkinsBridgeSettingsProvider provider = providerWithTempStateFile();
    BuildMirrorStore store = new BuildMirrorStore(null, provider, buildMockProjectManager());

    FakeJenkinsClient jenkinsClient = new FakeJenkinsClient();
    jenkinsClient.addBuild(buildInfo(3, 1710000000003L));
    jenkinsClient.addBuild(buildInfo(2, 1710000000002L));
    jenkinsClient.addBuild(buildInfo(1, 1710000000001L));

    JenkinsBridgePollingService service = newService(provider, jenkinsClient, new CapturingMirrorService(), store);

    pollJob(service, jenkinsClient, new MirroredJob("conn1", "job", "buildType", "Build", 0, false));

    assertNotNull(store.findMirror("buildType::job#3@1710000000003"));
    assertNull(store.findMirror("buildType::job#2@1710000000002"));
    assertNull(store.findMirror("buildType::job#1@1710000000001"));
    assertEquals(3, store.getLastSeenBuildNumber("buildType::job"));
  }

  /** Finished mirrors are kept, they are how a TeamCity build is later resolved back to its Jenkins run. */
  @Test
  public void pollJobKeepsFinishedMirrorsOfOtherJobs() throws Exception {
    JenkinsBridgeSettingsProvider provider = providerWithTempStateFile();
    BuildMirrorStore store = new BuildMirrorStore(null, provider, buildMockProjectManager());

    BuildMirror finished = store.getOrCreateMirror(
        "buildType::otherJob#1@1710000000001", "otherJob", "buildType", buildInfo(1, 1710000000001L));
    finished.setSyncState(SyncState.TEAMCITY_FINISHED);
    store.saveMirror(finished);

    FakeJenkinsClient jenkinsClient = new FakeJenkinsClient();
    jenkinsClient.addBuild(buildInfo(1, 1710000000001L));

    JenkinsBridgePollingService service = newService(provider, jenkinsClient, new CapturingMirrorService(), store);

    pollJob(service, jenkinsClient, new MirroredJob("conn1", "job", "buildType", "Build", 0, false));

    assertNotNull(store.findMirror("buildType::otherJob#1@1710000000001"));
  }

  @Test
  public void fetchesJenkinsBuildParametersOnceBeforeTeamCityBuildCreation() throws Exception {
    JenkinsBridgeSettingsProvider provider = providerWithTempStateFile();
    BuildMirrorStore store = new BuildMirrorStore(null, provider, buildMockProjectManager());
    JenkinsBuildInfo finishedBuild = finishedBuildInfo();
    BuildMirror mirror = store.getOrCreateMirror("job#1", "job", "buildType", finishedBuild);

    FakeJenkinsClient jenkinsClient = new FakeJenkinsClient();
    CapturingMirrorService mirrorService = new CapturingMirrorService();
    JenkinsBridgePollingService service = newService(provider, jenkinsClient, mirrorService, store);

    syncBuild(service, jenkinsClient, mirror, finishedBuild);

    assertEquals(1, jenkinsClient.getBuildParametersCalls);
    assertEquals("feature/x", mirror.getJenkinsBuildParameters().get("BRANCH"));
    assertTrue(mirror.isJenkinsBuildParametersLoaded());
    assertEquals("feature/x", mirrorService.lastJenkinsParameters.get("BRANCH"));

    syncBuild(service, jenkinsClient, mirror, finishedBuild);

    assertEquals(1, jenkinsClient.getBuildParametersCalls);
  }

  @Test
  public void aZeroBackfillMirrorsNoHistoricalBuildButStillMirrorsTheNextOne() throws Exception {
    JenkinsBridgeSettingsProvider provider = providerWithTempStateFile();
    BuildMirrorStore store = new BuildMirrorStore(null, provider, buildMockProjectManager());

    FakeJenkinsClient jenkinsClient = new FakeJenkinsClient();
    jenkinsClient.addBuild(buildInfo(3, 1710000000003L));
    jenkinsClient.addBuild(buildInfo(2, 1710000000002L));
    jenkinsClient.addBuild(buildInfo(1, 1710000000001L));

    JenkinsBridgePollingService service = newService(provider, jenkinsClient, new CapturingMirrorService(), store);
    MirroredJob job = new MirroredJob("conn1", "job", "buildType", "Build", 0, false);

    pollJob(service, jenkinsClient, job, 0);

    assertNull(store.findMirror("buildType::job#3@1710000000003"));
    assertNull(store.findMirror("buildType::job#2@1710000000002"));
    assertNull(store.findMirror("buildType::job#1@1710000000001"));
    // The watermark still advances, otherwise the job would stay cold and never mirror anything.
    assertEquals(3, store.getLastSeenBuildNumber("buildType::job"));

    jenkinsClient.addBuild(buildInfo(4, 1710000000004L));
    pollJob(service, jenkinsClient, job, 0);

    assertNotNull(store.findMirror("buildType::job#4@1710000000004"));
    assertEquals(4, store.getLastSeenBuildNumber("buildType::job"));
  }

  @Test
  public void aBackfillLargerThanTheHistoryMirrorsEveryBuildThatExists() throws Exception {
    JenkinsBridgeSettingsProvider provider = providerWithTempStateFile();
    BuildMirrorStore store = new BuildMirrorStore(null, provider, buildMockProjectManager());

    FakeJenkinsClient jenkinsClient = new FakeJenkinsClient();
    jenkinsClient.addBuild(buildInfo(3, 1710000000003L));
    jenkinsClient.addBuild(buildInfo(2, 1710000000002L));
    jenkinsClient.addBuild(buildInfo(1, 1710000000001L));

    JenkinsBridgePollingService service = newService(provider, jenkinsClient, new CapturingMirrorService(), store);

    pollJob(service, jenkinsClient, new MirroredJob("conn1", "job", "buildType", "Build", 1000, false), 1000);

    assertNotNull(store.findMirror("buildType::job#3@1710000000003"));
    assertNotNull(store.findMirror("buildType::job#2@1710000000002"));
    assertNotNull(store.findMirror("buildType::job#1@1710000000001"));
    assertEquals(3, store.getLastSeenBuildNumber("buildType::job"));
  }

  @Test
  public void runningBoundTeamCityFirstBuildIsUpdatedLiveWithoutFinalSync() throws Exception {
    JenkinsBridgeSettingsProvider provider = providerWithTempStateFile();
    BuildMirrorStore store = new BuildMirrorStore(null, provider, buildMockProjectManager());
    JenkinsBuildInfo runningBuild = buildInfo(1, 1710000000001L, true);
    BuildMirror mirror = store.getOrCreateMirror("job#1", "job", "buildType", runningBuild);
    mirror.setTeamCityBuildId(77L);
    mirror.setPipelineMode(false);

    FakeJenkinsClient jenkinsClient = new FakeJenkinsClient();
    CapturingMirrorService mirrorService = new CapturingMirrorService();
    JenkinsBridgePollingService service = newService(provider, jenkinsClient, mirrorService, store);

    syncBuild(service, jenkinsClient, mirror, runningBuild);

    assertEquals(0, jenkinsClient.getBuildParametersCalls);
    assertEquals(1, mirrorService.ensureBuildCalls);
    assertEquals(1, mirrorService.runningDataCalls);
    assertEquals(1, mirrorService.metadataLogCalls);
    assertEquals(1, mirrorService.logSyncCalls);
    assertEquals(0, mirrorService.testSyncCalls);
    assertEquals(0, mirrorService.artifactSyncCalls);
    assertEquals(0, mirrorService.finishCalls);
  }

  private static JenkinsBuildInfo buildInfo() {
    return buildInfo(1, 1710000000001L);
  }

  private static JenkinsBuildInfo buildInfo(int number, long timestamp) {
    return buildInfo(number, timestamp, true);
  }

  private static JenkinsBuildInfo finishedBuildInfo() {
    return buildInfo(1, 1710000000001L, false);
  }

  private static JenkinsBuildInfo buildInfo(int number, long timestamp, boolean building) {
    JsonObject json = new JsonObject();
    json.addProperty("number", number);
    json.addProperty("timestamp", timestamp);
    json.addProperty("url", "http://jenkins/job/job/" + number + "/");
    json.addProperty("building", building);
    if (!building) {
      json.addProperty("result", "SUCCESS");
    }
    return JenkinsBuildInfo.fromJson(json);
  }

  private static JenkinsBridgePollingService newService(
      JenkinsBridgeSettingsProvider provider,
      FakeJenkinsClient jenkinsClient,
      CapturingMirrorService mirrorService,
      BuildMirrorStore store
  ) {
    return new JenkinsBridgePollingService(
        provider,
        null,
        null,
        mirrorService,
        store,
        new MirroredJobProvider(null),
        null);
  }

  /**
   * Drives one poll of a single (non-multibranch) job with an explicit Jenkins client, which is what
   * pollPipeline does once it has resolved the job's connection.
   */
  private static void pollJob(
      JenkinsBridgePollingService service,
      JenkinsClient jenkinsClient,
      MirroredJob mirroredJob
  ) throws Exception {
    pollJob(service, jenkinsClient, mirroredJob, 1);
  }

  private static void pollJob(
      JenkinsBridgePollingService service,
      JenkinsClient jenkinsClient,
      MirroredJob mirroredJob,
      int recentBuildLimit
  ) throws Exception {
    Method pollJob = JenkinsBridgePollingService.class.getDeclaredMethod(
        "pollJob", JenkinsClient.class, MirroredJob.class, List.class, int.class);
    pollJob.setAccessible(true);
    pollJob.invoke(service, jenkinsClient, mirroredJob,
        jenkinsClient.getBuilds(mirroredJob.jenkinsJob()), recentBuildLimit);
  }

  private static void syncBuild(
      JenkinsBridgePollingService service,
      JenkinsClient jenkinsClient,
      BuildMirror mirror,
      JenkinsBuildInfo buildInfo
  ) throws Exception {
    Method syncBuild = JenkinsBridgePollingService.class.getDeclaredMethod(
        "syncBuild", JenkinsClient.class, String.class, BuildMirror.class, JenkinsBuildInfo.class);
    syncBuild.setAccessible(true);
    syncBuild.invoke(service, jenkinsClient, "conn1", mirror, buildInfo);
  }

  private static JenkinsBridgeSettingsProvider providerWithTempStateFile() throws Exception {
    File stateFile = File.createTempFile("jenkins-bridge-polling-test", ".json");
    stateFile.delete();
    stateFile.deleteOnExit();
    final String path = stateFile.getAbsolutePath();
    return new JenkinsBridgeSettingsProvider(null) {
      @Override
      public JenkinsBridgeSettings load() {
        try {
          Constructor<JenkinsBridgeSettings> constructor = JenkinsBridgeSettings.class.getDeclaredConstructor(
              boolean.class, int.class, int.class, String.class,
              String.class, String.class, String.class);
          constructor.setAccessible(true);
          return constructor.newInstance(true, 10, 1440, path, "", "", "");
        } catch (Exception e) {
          throw new AssertionError(e);
        }
      }
    };
  }

  private static class FakeJenkinsClient extends JenkinsClient {
    private final JsonParser parser = new JsonParser();
    private final List<JenkinsBuildInfo> builds = new ArrayList<JenkinsBuildInfo>();
    private final Map<Integer, JenkinsBuildInfo> buildInfos = new LinkedHashMap<Integer, JenkinsBuildInfo>();
    int getBuildParametersCalls;
    int getBuildInfoCalls;

    FakeJenkinsClient() {
      super(new com.jetbrains.teamcity.jenkinsbridge.connection.JenkinsConnection(
          "http://jenkins", "user", "token"), null, null);
    }

    void addBuild(JenkinsBuildInfo buildInfo) {
      builds.add(buildInfo);
      buildInfos.put(Integer.valueOf(buildInfo.getNumber()), buildInfo);
    }

    @Override
    public List<JenkinsBuildInfo> getBuilds(String jobName) {
      return new ArrayList<JenkinsBuildInfo>(builds);
    }

    @Override
    public List<JenkinsBuildInfo> getAllBuilds(String jobName) {
      return new ArrayList<JenkinsBuildInfo>(builds);
    }

    @Override
    public JenkinsBuildInfo getBuildInfo(String jobName, int buildNumber) {
      getBuildInfoCalls++;
      return buildInfos.get(Integer.valueOf(buildNumber));
    }

    @Override
    public JenkinsStages getStages(String jobName, int buildNumber) {
      return JenkinsStages.notPipeline();
    }

    @NotNull
    @Override
    public JenkinsVcsInfo getBuildVcs(String jobName, int buildNumber) {
      return JenkinsVcsInfo.empty();
    }

    @Override
    public JenkinsBuildParameters getBuildParameters(String jobName, int buildNumber) {
      getBuildParametersCalls++;
      return JenkinsBuildParameters.fromJson(parser.parse("{\"actions\":[{\"parameters\":["
          + "{\"name\":\"BRANCH\",\"value\":\"feature/x\",\"_class\":\"hudson.model.StringParameterValue\"}"
          + "]}]}").getAsJsonObject());
    }

    @Override
    public JenkinsLogChunk getProgressiveLog(String jobName, int buildNumber, long start) {
      return new JenkinsLogChunk("", start, false);
    }

    @Override
    public JenkinsTestReport getTestReport(String jobName, int buildNumber) {
      return JenkinsTestReport.empty();
    }

    @Override
    public JenkinsArtifacts getArtifacts(String jobName, int buildNumber) {
      return JenkinsArtifacts.empty();
    }
  }

  private static class CapturingMirrorService extends TeamCityBuildMirrorService {
    Map<String, String> lastJenkinsParameters;
    int ensureBuildCalls;
    int runningDataCalls;
    int metadataLogCalls;
    int logSyncCalls;
    int testSyncCalls;
    int artifactSyncCalls;
    int finishCalls;

    CapturingMirrorService() {
      super(null, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    @Override
    public long ensureTeamCityBuild(BuildMirror mirror, String connectionId, JenkinsBuildInfo jenkinsInfo,
                                    JenkinsPipelineGraph graph, JenkinsVcsInfo vcsInfo) {
      ensureBuildCalls++;
      lastJenkinsParameters = mirror.getJenkinsBuildParameters();
      if (mirror.getTeamCityBuildId() != null) {
        return mirror.getTeamCityBuildId();
      }
      mirror.setTeamCityBuildId(100L);
      return 100L;
    }

    @Override
    public void ensureRunningDataSent(BuildMirror mirror, long teamCityBuildId) {
      runningDataCalls++;
    }

    @Override
    public void ensureMetadataLogSent(BuildMirror mirror, long teamCityBuildId) {
      metadataLogCalls++;
    }

    @Override
    public void syncLogs(BuildMirror mirror, long teamCityBuildId, JenkinsLogChunk logChunk) {
      logSyncCalls++;
    }

    @Override
    public void syncTestsIfNeeded(BuildMirror mirror, long teamCityBuildId, JenkinsTestReport testReport) {
      testSyncCalls++;
      mirror.setTestsSynced(true);
    }

    @Override
    public void syncArtifactMetadataIfNeeded(BuildMirror mirror, long teamCityBuildId, JenkinsArtifacts artifacts) {
      artifactSyncCalls++;
      mirror.setArtifactsSynced(true);
    }

    @Override
    public void syncVcsIfNeeded(BuildMirror mirror, JenkinsVcsInfo vcsInfo) {
      // no-op
    }

    @Override
    public void finishBuildIfNeeded(BuildMirror mirror, long teamCityBuildId, JenkinsBuildInfo jenkinsInfo) {
      finishCalls++;
    }

    @Override
    public void syncBuildNumber(BuildMirror mirror) {
      // no-op
    }
  }
}
