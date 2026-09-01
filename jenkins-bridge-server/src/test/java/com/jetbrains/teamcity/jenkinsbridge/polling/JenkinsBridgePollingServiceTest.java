package com.jetbrains.teamcity.jenkinsbridge.polling;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.jetbrains.teamcity.jenkinsbridge.feature.MirroredJobProvider;
import com.jetbrains.teamcity.jenkinsbridge.http.BridgeHttpException;
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
import com.jetbrains.teamcity.jenkinsbridge.settings.JenkinsBridgeSettingsProvider;
import com.jetbrains.teamcity.jenkinsbridge.settings.MirroredJob;
import com.jetbrains.teamcity.jenkinsbridge.teamcity.TeamCityBuildMirrorService;
import jetbrains.buildServer.serverSide.TeamCityNode;
import jetbrains.buildServer.serverSide.TeamCityNodes;
import org.jetbrains.annotations.NotNull;
import org.junit.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.jetbrains.teamcity.jenkinsbridge.persistence.BuildMirrorStoreTest.buildMockProjectManager;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class JenkinsBridgePollingServiceTest {
  @Test
  public void secondaryNodeIsNotAllowedToPoll() {
    TeamCityNodes nodes = org.mockito.Mockito.mock(TeamCityNodes.class);
    TeamCityNode node = org.mockito.Mockito.mock(TeamCityNode.class);
    org.mockito.Mockito.when(nodes.getCurrentNode()).thenReturn(node);
    org.mockito.Mockito.when(node.isMainNode()).thenReturn(false);

    JenkinsBridgePollingService service = new JenkinsBridgePollingService(
        null, null, null, null, null, null, null, null, null, nodes, null, null);

    assertFalse(service.mayPollOnCurrentNode());
  }

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
    JenkinsBridgeSettingsProvider provider = provider();
    BuildMirrorStore store = new BuildMirrorStore(buildMockProjectManager());
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
    JenkinsBridgeSettingsProvider provider = provider();
    BuildMirrorStore store = new BuildMirrorStore(buildMockProjectManager());

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

  /**
   * A transient detail-fetch failure must not advance the watermark past an untracked build.
   * The next poll must retry discovery and create the mirror after Jenkins recovers.
   */
  @Test
  public void retriesDiscoveredBuildAfterTransientDetailFetchFailure() throws Exception {
    JenkinsBridgeSettingsProvider provider = provider();
    BuildMirrorStore store = new BuildMirrorStore(buildMockProjectManager());
    store.setLastSeenBuildNumber("buildType::job", 1);

    FakeJenkinsClient jenkinsClient = new FakeJenkinsClient();
    jenkinsClient.addBuild(buildInfo(2, 1710000000002L));
    jenkinsClient.buildInfoFailure = new BridgeHttpException("GET", "/job/job/2/api/json", 503, "temporary failure");
    JenkinsBridgePollingService service = newService(
        provider, jenkinsClient, new CapturingMirrorService(), store);
    MirroredJob mirroredJob = new MirroredJob("conn1", "job", "buildType", "Build", 0, false);

    pollJob(service, jenkinsClient, mirroredJob, 10);
    assertNull(store.findMirror("buildType::job#2@1710000000002"));
    assertEquals(1, store.getLastSeenBuildNumber("buildType::job"));

    jenkinsClient.buildInfoFailure = null;
    pollJob(service, jenkinsClient, mirroredJob, 10);

    assertNotNull(store.findMirror("buildType::job#2@1710000000002"));
    assertEquals(2, store.getLastSeenBuildNumber("buildType::job"));
    assertEquals(2, jenkinsClient.getBuildInfoCalls);
  }

  // Temporarily disabled while finished-mirror pruning is deferred.
  // @Test
  // public void pollJobKeepsFinishedMirrorsOfOtherJobs() throws Exception {
  //   JenkinsBridgeSettingsProvider provider = provider();
  //   BuildMirrorStore store = new BuildMirrorStore(buildMockProjectManager());
  //
  //   BuildMirror finished = store.getOrCreateMirror(
  //       "buildType::otherJob#1@1710000000001", "otherJob", "buildType", buildInfo(1, 1710000000001L));
  //   finished.setSyncState(SyncState.TEAMCITY_FINISHED);
  //   store.saveMirror(finished);
  //
  //   FakeJenkinsClient jenkinsClient = new FakeJenkinsClient();
  //   jenkinsClient.addBuild(buildInfo(1, 1710000000001L));
  //
  //   JenkinsBridgePollingService service = newService(provider, jenkinsClient, new CapturingMirrorService(), store);
  //
  //   pollJob(service, jenkinsClient, new MirroredJob("conn1", "job", "buildType", "Build", 0, false));
  //
  //   assertNotNull(store.findMirror("buildType::otherJob#1@1710000000001"));
  // }

  @Test
  public void fetchesJenkinsBuildParametersOnceBeforeTeamCityBuildCreation() throws Exception {
    JenkinsBridgeSettingsProvider provider = provider();
    BuildMirrorStore store = new BuildMirrorStore(buildMockProjectManager());
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
    JenkinsBridgeSettingsProvider provider = provider();
    BuildMirrorStore store = new BuildMirrorStore(buildMockProjectManager());

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
    JenkinsBridgeSettingsProvider provider = provider();
    BuildMirrorStore store = new BuildMirrorStore(buildMockProjectManager());

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
    JenkinsBridgeSettingsProvider provider = provider();
    BuildMirrorStore store = new BuildMirrorStore(buildMockProjectManager());
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

  @Test
  public void classifiesOnlyAccessNetworkAndServerHttpFailuresAsConnectivity() {
    assertTrue(JenkinsBridgePollingService.isConnectivityFailure(
        new BridgeHttpException("GET", "http://jenkins", -1, "timeout")));
    assertTrue(JenkinsBridgePollingService.isConnectivityFailure(
        new BridgeHttpException("GET", "http://jenkins", 401, "unauthorized")));
    assertTrue(JenkinsBridgePollingService.isConnectivityFailure(
        new BridgeHttpException("GET", "http://jenkins", 403, "forbidden")));
    assertTrue(JenkinsBridgePollingService.isConnectivityFailure(
        new BridgeHttpException("GET", "http://jenkins", 503, "unavailable")));
    assertFalse(JenkinsBridgePollingService.isConnectivityFailure(
        new BridgeHttpException("GET", "http://jenkins", 404, "not found")));
    assertFalse(JenkinsBridgePollingService.isConnectivityFailure(
        new BridgeHttpException("GET", "http://jenkins", 429, "rate limited")));
  }

  @Test
  public void requiredBuildRequestFailurePreventsRecoveryAndCapturesConnectivity() throws Exception {
    JenkinsBridgeSettingsProvider provider = provider();
    BuildMirrorStore store = new BuildMirrorStore(buildMockProjectManager());
    FakeJenkinsClient jenkinsClient = new FakeJenkinsClient();
    jenkinsClient.addBuild(buildInfo());
    jenkinsClient.buildInfoFailure =
        new BridgeHttpException("GET", "http://jenkins/job/job/1/api/json", 503, "unavailable");

    JenkinsBridgePollingService.JobPollOutcome outcome = pollJob(
        newService(provider, jenkinsClient, new CapturingMirrorService(), store),
        jenkinsClient, new MirroredJob("conn1", "job", "buildType", "Build", 1, false), 1);

    assertFalse(outcome.isSuccessful());
    assertNotNull(outcome.getConnectivityFailure());
  }

  @Test
  public void innerBuild404DoesNotBecomeMissingJobOrConnectivity() throws Exception {
    JenkinsBridgeSettingsProvider provider = provider();
    BuildMirrorStore store = new BuildMirrorStore(buildMockProjectManager());
    FakeJenkinsClient jenkinsClient = new FakeJenkinsClient();
    jenkinsClient.addBuild(buildInfo());
    jenkinsClient.buildInfoFailure =
        new BridgeHttpException("GET", "http://jenkins/job/job/1/api/json", 404, "not found");

    JenkinsBridgePollingService.JobPollOutcome outcome = pollJob(
        newService(provider, jenkinsClient, new CapturingMirrorService(), store),
        jenkinsClient, new MirroredJob("conn1", "job", "buildType", "Build", 1, false), 1);

    assertTrue(outcome.isSuccessful());
    assertNull(outcome.getConnectivityFailure());
  }

  @Test
  public void dispatchesAuthoritativeAndNestedHealthOutcomesToTheReporter() throws Exception {
    JenkinsBridgeSettingsProvider provider = provider();
    BuildMirrorStore store = new BuildMirrorStore(buildMockProjectManager());
    CapturingProblemReporter reporter = new CapturingProblemReporter();
    JenkinsBridgePollingService service = newService(
        provider, new FakeJenkinsClient(), new CapturingMirrorService(), store, reporter);
    activate(service);
    MirroredJob job = new MirroredJob("conn1", "job", "buildType", "Build", 1, false);

    service.reportAuthoritativePollingFailure(
        job, new BridgeHttpException("GET", "http://jenkins/job/job/api/json", 404, "not found"));
    service.reportAuthoritativePollingFailure(
        job, new BridgeHttpException("GET", "http://jenkins/job/job/api/json", 503, "unavailable"));
    JenkinsBridgePollingService.JobPollOutcome clean = new JenkinsBridgePollingService.JobPollOutcome();
    service.updateSystemProblem(job, clean);
    JenkinsBridgePollingService.JobPollOutcome innerNotFound = new JenkinsBridgePollingService.JobPollOutcome();
    innerNotFound.recordJenkinsFailure(
        new BridgeHttpException("GET", "http://jenkins/job/job/1/api/json", 404, "not found"));
    service.updateSystemProblem(job, innerNotFound);
    JenkinsBridgePollingService.JobPollOutcome nestedFailure = new JenkinsBridgePollingService.JobPollOutcome();
    nestedFailure.recordJenkinsFailure(
        new BridgeHttpException("GET", "http://jenkins/job/job/1/api/json", 503, "unavailable"));
    service.updateSystemProblem(job, nestedFailure);

    assertEquals(1, reporter.missingReports);
    assertEquals(2, reporter.connectivityReports);
    assertEquals(2, reporter.recoveries);
  }

  @Test
  public void shutdownWaitsForInFlightReportingBeforeClearingTickets() throws Exception {
    JenkinsBridgeSettingsProvider provider = provider();
    BuildMirrorStore store = new BuildMirrorStore(buildMockProjectManager());
    BlockingProblemReporter reporter = new BlockingProblemReporter();
    JenkinsBridgePollingService service = newService(
        provider, new FakeJenkinsClient(), new CapturingMirrorService(), store, reporter);
    activate(service);
    MirroredJob job = new MirroredJob("conn1", "job", "buildType", "Build", 1, false);
    JenkinsBridgePollingService.JobPollOutcome failed = new JenkinsBridgePollingService.JobPollOutcome();
    failed.recordJenkinsFailure(
        new BridgeHttpException("GET", "http://jenkins/job/job/api/json", 503, "unavailable"));

    Thread reporting = new Thread(() -> service.updateSystemProblem(job, failed));
    reporting.start();
    Thread stopping = new Thread(service::stop);
    try {
      assertTrue(reporter.reportEntered.await(1, TimeUnit.SECONDS));
      stopping.start();
      assertFalse(reporter.clearCalled.await(100, TimeUnit.MILLISECONDS));
    } finally {
      reporter.allowReport.countDown();
      reporting.join(1000);
      if (stopping.getState() != Thread.State.NEW) {
        stopping.join(1000);
      }
    }

    assertFalse(reporting.isAlive());
    assertFalse(stopping.isAlive());
    assertEquals(1, reporter.connectivityReports);
    assertEquals(1, reporter.clears);
  }

  @Test
  public void localMirrorFailureDoesNotBecomeJenkinsConnectivityFailure() throws Exception {
    JenkinsBridgeSettingsProvider provider = provider();
    BuildMirrorStore store = new BuildMirrorStore(buildMockProjectManager());
    FakeJenkinsClient jenkinsClient = new FakeJenkinsClient();
    jenkinsClient.addBuild(buildInfo());
    CapturingMirrorService mirrorService = new CapturingMirrorService() {
      @Override
      public long ensureTeamCityBuild(BuildMirror mirror, String connectionId, JenkinsBuildInfo jenkinsInfo,
                                      JenkinsPipelineGraph graph, JenkinsVcsInfo vcsInfo) {
        throw new IllegalStateException("TeamCity adapter failed");
      }
    };

    JenkinsBridgePollingService.JobPollOutcome outcome = pollJob(
        newService(provider, jenkinsClient, mirrorService, store),
        jenkinsClient, new MirroredJob("conn1", "job", "buildType", "Build", 1, false), 1);

    assertTrue(outcome.isSuccessful());
    assertNull(outcome.getConnectivityFailure());
  }

  @Test
  public void artifactFailureRemainsBestEffortForHealth() throws Exception {
    JenkinsBridgeSettingsProvider provider = provider();
    BuildMirrorStore store = new BuildMirrorStore(buildMockProjectManager());
    FakeJenkinsClient jenkinsClient = new FakeJenkinsClient();
    jenkinsClient.addBuild(finishedBuildInfo());
    jenkinsClient.artifactFailure =
        new BridgeHttpException("GET", "http://jenkins/job/job/1/api/json", 503, "unavailable");

    JenkinsBridgePollingService.JobPollOutcome outcome = pollJob(
        newService(provider, jenkinsClient, new CapturingMirrorService(), store),
        jenkinsClient, new MirroredJob("conn1", "job", "buildType", "Build", 1, false), 1);

    assertTrue(outcome.isSuccessful());
    assertNull(outcome.getConnectivityFailure());
  }

  @Test
  public void vcsFetchFailureIsRecordedAndDoesNotBlockFinishing() throws Exception {
    JenkinsBridgeSettingsProvider provider = provider();
    BuildMirrorStore store = new BuildMirrorStore(buildMockProjectManager());
    FakeJenkinsClient jenkinsClient = new FakeJenkinsClient();
    jenkinsClient.addBuild(finishedBuildInfo());
    jenkinsClient.vcsFailure =
        new BridgeHttpException("GET", "http://jenkins/job/job/1/api/json", 503, "unavailable");
    CapturingMirrorService mirrorService = new CapturingMirrorService();

    JenkinsBridgePollingService.JobPollOutcome outcome = pollJob(
        newService(provider, jenkinsClient, mirrorService, store),
        jenkinsClient, new MirroredJob("conn1", "job", "buildType", "Build", 1, false), 1);

    assertTrue(outcome.isSuccessful());
    assertEquals(2, mirrorService.vcsFetchFailureCalls);
    assertEquals(1, mirrorService.finishCalls);
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
    return newService(provider, jenkinsClient, mirrorService, store, null);
  }

  private static JenkinsBridgePollingService newService(
      JenkinsBridgeSettingsProvider provider,
      FakeJenkinsClient jenkinsClient,
      CapturingMirrorService mirrorService,
      BuildMirrorStore store,
      JenkinsBridgeSystemProblemReporter reporter
  ) {
    return new JenkinsBridgePollingService(
        provider,
        null,
        null,
        null,
        mirrorService,
        store,
        new MirroredJobProvider(null),
        null,
        null,
        null,
        reporter,
        null);
  }

  private static void activate(JenkinsBridgePollingService service) throws Exception {
    Field field = JenkinsBridgePollingService.class.getDeclaredField("started");
    field.setAccessible(true);
    ((AtomicBoolean) field.get(service)).set(true);
  }

  /**
   * Drives one poll of a single (non-multibranch) job with an explicit Jenkins client, which is what
   * pollPipeline does once it has resolved the job's connection.
   */
  private static JenkinsBridgePollingService.JobPollOutcome pollJob(
      JenkinsBridgePollingService service,
      JenkinsClient jenkinsClient,
      MirroredJob mirroredJob
  ) throws Exception {
    return pollJob(service, jenkinsClient, mirroredJob, 1);
  }

  private static JenkinsBridgePollingService.JobPollOutcome pollJob(
      JenkinsBridgePollingService service,
      JenkinsClient jenkinsClient,
      MirroredJob mirroredJob,
      int recentBuildLimit
  ) throws Exception {
    Method pollJob = JenkinsBridgePollingService.class.getDeclaredMethod(
        "pollJob", JenkinsClient.class, MirroredJob.class, List.class, int.class);
    pollJob.setAccessible(true);
    return (JenkinsBridgePollingService.JobPollOutcome) pollJob.invoke(
        service, jenkinsClient, mirroredJob,
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

  private static JenkinsBridgeSettingsProvider provider() {
    return new JenkinsBridgeSettingsProvider();
  }

  private static class FakeJenkinsClient extends JenkinsClient {
    private final JsonParser parser = new JsonParser();
    private final List<JenkinsBuildInfo> builds = new ArrayList<JenkinsBuildInfo>();
    private final Map<Integer, JenkinsBuildInfo> buildInfos = new LinkedHashMap<Integer, JenkinsBuildInfo>();
    int getBuildParametersCalls;
    int getBuildInfoCalls;
    BridgeHttpException buildInfoFailure;
    BridgeHttpException artifactFailure;
    BridgeHttpException vcsFailure;

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
    public JenkinsBuildInfo getBuildInfo(String jobName, int buildNumber) throws BridgeHttpException {
      getBuildInfoCalls++;
      if (buildInfoFailure != null) {
        throw buildInfoFailure;
      }
      return buildInfos.get(Integer.valueOf(buildNumber));
    }

    @Override
    public JenkinsStages getStages(String jobName, int buildNumber) {
      return JenkinsStages.notPipeline();
    }

    @NotNull
    @Override
    public JenkinsVcsInfo getBuildVcs(String jobName, int buildNumber) throws BridgeHttpException {
      if (vcsFailure != null) {
        throw vcsFailure;
      }
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
    public JenkinsArtifacts getArtifacts(String jobName, int buildNumber) throws BridgeHttpException {
      if (artifactFailure != null) {
        throw artifactFailure;
      }
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
    int vcsFetchFailureCalls;

    CapturingMirrorService() {
      super(null, null, null, null, null, null, null, null, null, null, null, null);
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
    public void recordVcsFetchFailure(BuildMirror mirror, Exception failure) {
      vcsFetchFailureCalls++;
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

  private static class CapturingProblemReporter extends JenkinsBridgeSystemProblemReporter {
    int missingReports;
    int connectivityReports;
    int recoveries;
    int clears;

    CapturingProblemReporter() {
      super(null, null, null);
    }

    @Override
    public synchronized void reportMissingJob(MirroredJob job, BridgeHttpException failure) {
      missingReports++;
    }

    @Override
    public synchronized void reportConnectivity(MirroredJob job, BridgeHttpException failure) {
      connectivityReports++;
    }

    @Override
    public synchronized void recover(MirroredJob job) {
      recoveries++;
    }

    @Override
    public synchronized void clearAll() {
      clears++;
    }
  }

  private static class BlockingProblemReporter extends CapturingProblemReporter {
    final CountDownLatch reportEntered = new CountDownLatch(1);
    final CountDownLatch allowReport = new CountDownLatch(1);
    final CountDownLatch clearCalled = new CountDownLatch(1);

    @Override
    public synchronized void reportConnectivity(MirroredJob job, BridgeHttpException failure) {
      reportEntered.countDown();
      try {
        allowReport.await();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      super.reportConnectivity(job, failure);
    }

    @Override
    public synchronized void clearAll() {
      super.clearAll();
      clearCalled.countDown();
    }
  }
}
