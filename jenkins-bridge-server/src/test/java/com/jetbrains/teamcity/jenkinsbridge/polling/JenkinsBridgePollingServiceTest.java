package com.jetbrains.teamcity.jenkinsbridge.polling;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.jetbrains.teamcity.jenkinsbridge.feature.MirroredJobProvider;
import com.jetbrains.teamcity.jenkinsbridge.jenkins.JenkinsClient;
import com.jetbrains.teamcity.jenkinsbridge.model.JenkinsBuildInfo;
import com.jetbrains.teamcity.jenkinsbridge.model.JenkinsBuildParameters;
import com.jetbrains.teamcity.jenkinsbridge.model.JenkinsLogChunk;
import com.jetbrains.teamcity.jenkinsbridge.model.JenkinsPipelineGraph;
import com.jetbrains.teamcity.jenkinsbridge.model.JenkinsStages;
import com.jetbrains.teamcity.jenkinsbridge.persistence.BuildMirror;
import com.jetbrains.teamcity.jenkinsbridge.persistence.BuildMirrorStore;
import com.jetbrains.teamcity.jenkinsbridge.persistence.SyncState;
import com.jetbrains.teamcity.jenkinsbridge.settings.JenkinsBridgeSettings;
import com.jetbrains.teamcity.jenkinsbridge.settings.JenkinsBridgeSettingsProvider;
import com.jetbrains.teamcity.jenkinsbridge.settings.MirroredJob;
import com.jetbrains.teamcity.jenkinsbridge.teamcity.TeamCityBuildMirrorService;
import org.junit.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Map;

import static com.jetbrains.teamcity.jenkinsbridge.persistence.BuildMirrorStoreTest.buildMockProjectManager;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class JenkinsBridgePollingServiceTest {
  @Test
  public void pollJobProcessesBuildNumberResetByTimestampedIdentity() throws Exception {
    JenkinsBridgeSettingsProvider provider = providerWithTempStateFile();
    BuildMirrorStore store = new BuildMirrorStore(null, provider, buildMockProjectManager());
    store.setLastSeenBuildNumber("buildType::job", 500);

    FakeJenkinsClient jenkinsClient = new FakeJenkinsClient();
    jenkinsClient.addBuild(buildInfo(1, 1710000000001L));

    JenkinsBridgePollingService service = newService(provider, jenkinsClient, new CapturingMirrorService(), store);

    pollJob(service, new MirroredJob("job", "buildType", "Build", 0, false), provider.load());

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

    pollJob(service, new MirroredJob("job", "buildType", "Build", 0, false), provider.load());

    assertNotNull(store.findMirror("buildType::job#3@1710000000003"));
    assertNull(store.findMirror("buildType::job#2@1710000000002"));
    assertNull(store.findMirror("buildType::job#1@1710000000001"));
    assertEquals(3, store.getLastSeenBuildNumber("buildType::job"));
  }

  @Test
  public void pollJobPrunesFinishedMirrors() throws Exception {
    JenkinsBridgeSettingsProvider provider = providerWithTempStateFile();
    BuildMirrorStore store = new BuildMirrorStore(null, provider, buildMockProjectManager());

    BuildMirror finished = store.getOrCreateMirror(
        "buildType::otherJob#1@1710000000001", "otherJob", "buildType", buildInfo(1, 1710000000001L));
    finished.setSyncState(SyncState.TEAMCITY_FINISHED);
    store.saveMirror(finished);

    FakeJenkinsClient jenkinsClient = new FakeJenkinsClient();
    jenkinsClient.addBuild(buildInfo(1, 1710000000001L));

    JenkinsBridgePollingService service = newService(provider, jenkinsClient, new CapturingMirrorService(), store);

    pollJob(service, new MirroredJob("job", "buildType", "Build", 0, false), provider.load());

    assertNull(store.findMirror("buildType::otherJob#1@1710000000001"));
  }

  @Test
  public void fetchesJenkinsBuildParametersOnceBeforeTeamCityBuildCreation() throws Exception {
    JenkinsBridgeSettingsProvider provider = providerWithTempStateFile();
    BuildMirrorStore store = new BuildMirrorStore(null, provider, buildMockProjectManager());
    BuildMirror mirror = store.getOrCreateMirror("job#1", "job", "buildType", buildInfo());

    FakeJenkinsClient jenkinsClient = new FakeJenkinsClient();
    CapturingMirrorService mirrorService = new CapturingMirrorService();
    JenkinsBridgePollingService service = new JenkinsBridgePollingService(
        provider,
        jenkinsClient,
        mirrorService,
        store,
        new MirroredJobProvider(null, provider));

    Method syncBuild = JenkinsBridgePollingService.class.getDeclaredMethod(
        "syncBuild", BuildMirror.class, JenkinsBuildInfo.class);
    syncBuild.setAccessible(true);

    syncBuild.invoke(service, mirror, buildInfo());

    assertEquals(1, jenkinsClient.getBuildParametersCalls);
    assertEquals("feature/x", mirror.getJenkinsBuildParameters().get("BRANCH"));
    assertTrue(mirror.isJenkinsBuildParametersLoaded());
    assertEquals("feature/x", mirrorService.lastJenkinsParameters.get("BRANCH"));

    syncBuild.invoke(service, mirror, buildInfo());

    assertEquals(1, jenkinsClient.getBuildParametersCalls);
  }

  private static JenkinsBuildInfo buildInfo() {
    return buildInfo(1, 1710000000001L);
  }

  private static JenkinsBuildInfo buildInfo(int number, long timestamp) {
    JsonObject json = new JsonObject();
    json.addProperty("number", number);
    json.addProperty("timestamp", timestamp);
    json.addProperty("url", "http://jenkins/job/job/" + number + "/");
    json.addProperty("building", true);
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
        jenkinsClient,
        mirrorService,
        store,
        new MirroredJobProvider(null, provider));
  }

  private static void pollJob(
      JenkinsBridgePollingService service,
      MirroredJob mirroredJob,
      JenkinsBridgeSettings settings
  ) throws Exception {
    Method pollJob = JenkinsBridgePollingService.class.getDeclaredMethod(
        "pollJob", MirroredJob.class, JenkinsBridgeSettings.class);
    pollJob.setAccessible(true);
    pollJob.invoke(service, mirroredJob, settings);
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
              boolean.class, String.class, String.class, String.class, String.class,
              String.class, String.class, String.class, String.class,
              int.class, int.class, String.class, String.class);
          constructor.setAccessible(true);
          return constructor.newInstance(
              true, "http://jenkins", "user", "token", "job",
              "http://teamcity", "tc-user", "tc-pass", "buildType",
              10, 1, "Europe/Berlin", path);
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
      super(null, null);
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
  }

  private static class CapturingMirrorService extends TeamCityBuildMirrorService {
    Map<String, String> lastJenkinsParameters;

    CapturingMirrorService() {
      super(null, null, null, null, null, null, null, null, null, null, null);
    }

    @Override
    public long ensureTeamCityBuild(BuildMirror mirror, JenkinsBuildInfo jenkinsInfo, JenkinsPipelineGraph graph) {
      lastJenkinsParameters = mirror.getJenkinsBuildParameters();
      mirror.setTeamCityBuildId(100L);
      return 100L;
    }

    @Override
    public void ensureRunningDataSent(BuildMirror mirror, long teamCityBuildId) {
      // no-op
    }

    @Override
    public void ensureMetadataLogSent(BuildMirror mirror, long teamCityBuildId) {
      // no-op
    }

    @Override
    public void syncLogs(BuildMirror mirror, long teamCityBuildId, JenkinsLogChunk logChunk) {
      // no-op
    }

    @Override
    public void finishBuildIfNeeded(BuildMirror mirror, long teamCityBuildId, JenkinsBuildInfo jenkinsInfo) {
      // no-op
    }
  }
}
