package com.jetbrains.teamcity.jenkinsbridge.polling;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.jetbrains.teamcity.jenkinsbridge.feature.MirroredJobProvider;
import com.jetbrains.teamcity.jenkinsbridge.feature.JenkinsParameterDefinitionService;
import com.jetbrains.teamcity.jenkinsbridge.http.BridgeHttpException;
import com.jetbrains.teamcity.jenkinsbridge.jenkins.JenkinsClient;
import com.jetbrains.teamcity.jenkinsbridge.jenkins.JenkinsClientFactory;
import com.jetbrains.teamcity.jenkinsbridge.model.JenkinsBuildInfo;
import com.jetbrains.teamcity.jenkinsbridge.model.JenkinsJobParameters;
import com.jetbrains.teamcity.jenkinsbridge.feature.JenkinsParameterDefinitionServiceTest;
import com.jetbrains.teamcity.jenkinsbridge.model.JenkinsBuildParameters;
import com.jetbrains.teamcity.jenkinsbridge.model.JenkinsArtifacts;
import com.jetbrains.teamcity.jenkinsbridge.model.JenkinsLogChunk;
import com.jetbrains.teamcity.jenkinsbridge.model.JenkinsPipelineGraph;
import com.jetbrains.teamcity.jenkinsbridge.model.JenkinsScmHeadInfo;
import com.jetbrains.teamcity.jenkinsbridge.model.JenkinsScmRevision;
import com.jetbrains.teamcity.jenkinsbridge.model.JenkinsStages;
import com.jetbrains.teamcity.jenkinsbridge.model.JenkinsTestReport;
import com.jetbrains.teamcity.jenkinsbridge.model.JenkinsVcsInfo;
import com.jetbrains.teamcity.jenkinsbridge.persistence.BuildMirror;
import com.jetbrains.teamcity.jenkinsbridge.persistence.BuildMirrorStore;
import com.jetbrains.teamcity.jenkinsbridge.persistence.PendingTrigger;
import com.jetbrains.teamcity.jenkinsbridge.persistence.SyncState;
import com.jetbrains.teamcity.jenkinsbridge.settings.JenkinsBridgeSettingsProvider;
import com.jetbrains.teamcity.jenkinsbridge.settings.MirroredJob;
import com.jetbrains.teamcity.jenkinsbridge.settings.MirroredChildJob;
import com.jetbrains.teamcity.jenkinsbridge.settings.MirroredJobNode;
import com.jetbrains.teamcity.jenkinsbridge.teamcity.TeamCityBuildMirrorService;
import com.jetbrains.teamcity.jenkinsbridge.vcs.VcsRefType;
import jetbrains.buildServer.serverSide.TeamCityNode;
import jetbrains.buildServer.serverSide.TeamCityNodes;
import jetbrains.buildServer.serverSide.ProjectManager;
import jetbrains.buildServer.serverSide.SBuildType;
import jetbrains.buildServer.serverSide.SProject;
import org.jetbrains.annotations.NotNull;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.jetbrains.teamcity.jenkinsbridge.persistence.BuildMirrorStoreTest.buildMockProjectManager;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class JenkinsBridgePollingServiceTest {
  @Test
  public void secondaryNodeIsNotAllowedToPoll() {
    TeamCityNodes nodes = org.mockito.Mockito.mock(TeamCityNodes.class);
    TeamCityNode node = org.mockito.Mockito.mock(TeamCityNode.class);
    org.mockito.Mockito.when(nodes.getCurrentNode()).thenReturn(node);
    org.mockito.Mockito.when(node.isMainNode()).thenReturn(false);

    JenkinsBridgePollingService service = new JenkinsBridgePollingService(
        null, null, null, null, null, null, null, null, null, nodes, null, null, null);

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

    pollJob(service, jenkinsClient, new MirroredJob("conn1", "job", "buildType", "Build", 1, false));

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

    pollJob(service, jenkinsClient, new MirroredJob("conn1", "job", "buildType", "Build", 1, false));

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
    MirroredJob mirroredJob = new MirroredJob("conn1", "job", "buildType", "Build", 10, false);

    pollJob(service, jenkinsClient, mirroredJob);
    assertNull(store.findMirror("buildType::job#2@1710000000002"));
    assertEquals(1, store.getLastSeenBuildNumber("buildType::job"));

    jenkinsClient.buildInfoFailure = null;
    pollJob(service, jenkinsClient, mirroredJob);

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

    pollJob(service, jenkinsClient, job);

    assertNull(store.findMirror("buildType::job#3@1710000000003"));
    assertNull(store.findMirror("buildType::job#2@1710000000002"));
    assertNull(store.findMirror("buildType::job#1@1710000000001"));
    // The watermark still advances, otherwise the job would stay cold and never mirror anything.
    assertEquals(3, store.getLastSeenBuildNumber("buildType::job"));

    jenkinsClient.addBuild(buildInfo(4, 1710000000004L));
    pollJob(service, jenkinsClient, job);

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

    pollJob(service, jenkinsClient, new MirroredJob("conn1", "job", "buildType", "Build", 1000, false));

    assertNotNull(store.findMirror("buildType::job#3@1710000000003"));
    assertNotNull(store.findMirror("buildType::job#2@1710000000002"));
    assertNotNull(store.findMirror("buildType::job#1@1710000000001"));
    assertEquals(3, store.getLastSeenBuildNumber("buildType::job"));
  }

  @Test
  public void parameterRefreshUsesConfiguredPollCadence() throws Exception {
    JenkinsBridgePollingService service = newService(provider(), new FakeJenkinsClient(),
        new CapturingMirrorService(), new BuildMirrorStore(buildMockProjectManager()));
    Method refreshDue = JenkinsBridgePollingService.class.getDeclaredMethod("shouldRefreshParameters", int.class);
    refreshDue.setAccessible(true);

    assertEquals(false, refreshDue.invoke(service, 3));
    assertEquals(false, refreshDue.invoke(service, 3));
    assertEquals(true, refreshDue.invoke(service, 3));
    assertEquals(false, refreshDue.invoke(service, 3));
  }

  @Test
  public void childDefinitionsRefreshOnlyWhenCachedAndDue() throws Exception {
    ProjectManager manager = buildMockProjectManager();
    BuildMirrorStore store = new BuildMirrorStore(manager);
    MultibranchJenkinsClient client = new MultibranchJenkinsClient();
    client.childBuilds.put("job/main", List.of());
    client.childBuilds.put("job/other", List.of());
    MirroredJob parent = new MirroredJob("conn1", "job", "buildType", "Build", 1, true);
    MirroredChildJob main = new MirroredChildJob(parent, "job/main");
    JenkinsParameterDefinitionService definitions = new JenkinsParameterDefinitionService(store);
    definitions.refreshChild(client, main);
    client.parameterRequests.clear();
    client.jobParameters = JenkinsParameterDefinitionServiceTest.definitions("UPDATED");
    JenkinsBridgePollingService service = newPipelineService(manager, client, new CapturingMirrorService(), store, null);

    pollPipeline(service, parent);
    assertTrue(client.parameterRequests.isEmpty());
    pollPipeline(service, parent, true);
    assertEquals(List.of("job/main"), client.parameterRequests);
    assertEquals(client.jobParameters.snapshot(), definitions.getCachedChild(client, main).orElseThrow().getSnapshot());
    assertTrue(definitions.getCachedChild(client, new MirroredChildJob(parent, "job/other")).isEmpty());
    org.mockito.Mockito.verify(manager.findBuildTypeByExternalId("buildType"), org.mockito.Mockito.never())
        .schedulePersisting(org.mockito.ArgumentMatchers.anyString());
  }

  @Test
  public void childParameterFailurePreservesCacheAndBuildPollingContinues() throws Exception {
    ProjectManager manager = buildMockProjectManager();
    BuildMirrorStore store = new BuildMirrorStore(manager);
    MultibranchJenkinsClient client = new MultibranchJenkinsClient();
    client.addChildBuild("job/main", buildInfo(1, 1710000000001L));
    MirroredJob parent = new MirroredJob("conn1", "job", "buildType", "Build", 1, true);
    MirroredChildJob child = new MirroredChildJob(parent, "job/main");
    JenkinsParameterDefinitionService definitions = new JenkinsParameterDefinitionService(store);
    definitions.refreshChild(client, child);
    client.parameterFailure = new BridgeHttpException("GET", "job/main", 503, "unavailable");
    JenkinsBridgePollingService service = newPipelineService(manager, client, new CapturingMirrorService(), store, null);

    pollPipeline(service, parent, true);

    assertNotNull(store.findMirror("buildType::job/main#1@1710000000001"));
    assertEquals("[]", definitions.getCachedChild(client, child).orElseThrow().getSnapshot());
  }

  @Test
  public void multibranchPollingKeepsChildIdentitiesAndReportsHealthOnTheParent() throws Exception {
    ProjectManager projectManager = buildMockProjectManager();
    BuildMirrorStore store = new BuildMirrorStore(projectManager);
    MultibranchJenkinsClient client = new MultibranchJenkinsClient();
    client.addChildBuild("job/main", buildInfo(1, 1710000000001L));
    client.addChildBuild("job/feature%2Flogin", buildInfo(1, 1710000000001L));
    client.childBuilds.put("job/empty", List.of());
    CapturingProblemReporter reporter = new CapturingProblemReporter();
    JenkinsBridgePollingService service = newPipelineService(
        projectManager, client, new CapturingMirrorService(), store, reporter);
    activate(service);
    MirroredJob parent = new MirroredJob("conn1", "job", "buildType", "Build", 1, true);

    Set<String> mappings = pollPipeline(service, parent);

    assertEquals(Set.of("buildType::job/main", "buildType::job/feature%2Flogin", "buildType::job/empty"), mappings);
    assertEquals("job", client.listedParent);
    assertEquals(List.of("job/main", "job/feature%2Flogin"), client.detailRequests);
    assertEquals(2, store.getMirrorCount());
    BuildMirror main = store.findMirror("buildType::job/main#1@1710000000001");
    BuildMirror feature = store.findMirror("buildType::job/feature%2Flogin#1@1710000000001");
    assertNotNull(main);
    assertNotNull(feature);
    assertEquals("job/main", main.getJenkinsJob());
    assertEquals("job/feature%2Flogin", feature.getJenkinsJob());
    assertEquals("buildType", main.getTeamCityBuildTypeId());
    assertEquals("buildType", feature.getTeamCityBuildTypeId());
    assertEquals(1, store.getLastSeenBuildNumber("buildType::job/main"));
    assertEquals(1, store.getLastSeenBuildNumber("buildType::job/feature%2Flogin"));
    assertEquals(0, store.getLastSeenBuildNumber(parent.getMirrorKeyPrefix()));
    assertEquals("job", parent.jenkinsJob());
    assertEquals(1, reporter.recoveries);
    assertSame(parent, reporter.lastMapping);
  }

  @Test
  public void childDiscoveryFailureDoesNotAdvanceItsWatermarkOrBlockItsSibling() throws Exception {
    ProjectManager projectManager = buildMockProjectManager();
    BuildMirrorStore store = new BuildMirrorStore(projectManager);
    store.setLastSeenBuildNumber("buildType::job/main", 1);
    store.setLastSeenBuildNumber("buildType::job/release", 1);
    MultibranchJenkinsClient client = new MultibranchJenkinsClient();
    client.addChildBuild("job/main", buildInfo(2, 1710000000002L));
    client.addChildBuild("job/release", buildInfo(2, 1710000000002L));
    client.failedJobs.put("job/main",
        new BridgeHttpException("GET", "/job/job/job/main/2/api/json", 503, "temporary failure"));
    CapturingProblemReporter reporter = new CapturingProblemReporter();
    JenkinsBridgePollingService service = newPipelineService(
        projectManager, client, new CapturingMirrorService(), store, reporter);
    activate(service);
    MirroredJob parent = new MirroredJob("conn1", "job", "buildType", "Build", 1, true);

    pollPipeline(service, parent);

    assertNull(store.findMirror("buildType::job/main#2@1710000000002"));
    assertNotNull(store.findMirror("buildType::job/release#2@1710000000002"));
    assertEquals(1, store.getLastSeenBuildNumber("buildType::job/main"));
    assertEquals(2, store.getLastSeenBuildNumber("buildType::job/release"));
    assertEquals(1, reporter.connectivityReports);
    assertEquals(0, reporter.recoveries);
    assertSame(parent, reporter.lastMapping);

    client.failedJobs.clear();
    pollPipeline(service, parent);

    assertNotNull(store.findMirror("buildType::job/main#2@1710000000002"));
    assertEquals(2, store.getLastSeenBuildNumber("buildType::job/main"));
    assertEquals(2, store.getLastSeenBuildNumber("buildType::job/release"));
    assertEquals(1, reporter.recoveries);
  }

  @Test
  public void childrenInheritZeroBackfillAndMirrorSubsequentBuilds() throws Exception {
    ProjectManager projectManager = buildMockProjectManager();
    BuildMirrorStore store = new BuildMirrorStore(projectManager);
    MultibranchJenkinsClient client = new MultibranchJenkinsClient();
    client.addChildBuild("job/main", buildInfo(1, 1710000000001L));
    client.addChildBuild("job/release", buildInfo(2, 1710000000002L));
    JenkinsBridgePollingService service = newPipelineService(
        projectManager, client, new CapturingMirrorService(), store, null);
    MirroredJob parent = new MirroredJob("conn1", "job", "buildType", "Build", 0, true);

    pollPipeline(service, parent);

    assertEquals(0, store.getMirrorCount());
    assertEquals(1, store.getLastSeenBuildNumber("buildType::job/main"));
    assertEquals(2, store.getLastSeenBuildNumber("buildType::job/release"));

    client.addChildBuild("job/main", buildInfo(2, 1710000000002L));
    client.addChildBuild("job/release", buildInfo(3, 1710000000003L));
    pollPipeline(service, parent);

    assertEquals(2, store.getMirrorCount());
    assertNotNull(store.findMirror("buildType::job/main#2@1710000000002"));
    assertNotNull(store.findMirror("buildType::job/release#3@1710000000003"));
    assertNull(store.findMirror("buildType::job/main#1@1710000000001"));
    assertNull(store.findMirror("buildType::job/release#2@1710000000002"));
  }

  @Test
  public void recreatedChildrenResumeExistingPersistedMirrors() throws Exception {
    ProjectManager projectManager = buildMockProjectManager();
    BuildMirrorStore originalStore = new BuildMirrorStore(projectManager);
    JenkinsBuildInfo build = buildInfo(1, 1710000000001L);
    BuildMirror existing = originalStore.getOrCreateMirror(
        "buildType::job/main#1@1710000000001", "job/main", "buildType", build);
    existing.setTeamCityBuildId(77L);
    existing.setPipelineMode(false);
    existing.setLastLogOffset(123L);
    originalStore.saveMirror(existing);
    originalStore.setLastSeenBuildNumber("buildType::job/main", 1);

    BuildMirrorStore reloaded = new BuildMirrorStore(projectManager);
    MultibranchJenkinsClient client = new MultibranchJenkinsClient();
    client.addChildBuild("job/main", build);
    CapturingMirrorService mirrorService = new CapturingMirrorService();
    JenkinsBridgePollingService service = newPipelineService(
        projectManager, client, mirrorService, reloaded, null);
    MirroredJob recreatedParent = new MirroredJob("conn1", "job", "buildType", "Build", 0, true);

    pollPipeline(service, recreatedParent);

    assertEquals(1, reloaded.getMirrorCount());
    BuildMirror resumed = reloaded.findMirror("buildType::job/main#1@1710000000001");
    assertEquals(Long.valueOf(77L), resumed.getTeamCityBuildId());
    assertEquals(123L, resumed.getLastLogOffset());
    assertEquals("job/main", resumed.getJenkinsJob());
    assertEquals(1, mirrorService.runningDataCalls);
    assertEquals(1, client.getBuildInfoCalls);
  }

  @Test
  public void twoMappingsOfTheSameChildKeepSeparateMirrors() throws Exception {
    BuildMirrorStore store = new BuildMirrorStore(buildMockProjectManager());
    MultibranchJenkinsClient client = new MultibranchJenkinsClient();
    client.addChildBuild("job/main", buildInfo(1, 1710000000001L));
    JenkinsBridgePollingService service = newService(provider(), client, new CapturingMirrorService(), store);
    MirroredJob first = new MirroredJob("conn1", "job", "First", "First", 1, true);
    MirroredJob second = new MirroredJob("conn1", "job", "Second", "Second", 1, true);

    pollJob(service, client, new MirroredChildJob(first, "job/main"));
    pollJob(service, client, new MirroredChildJob(second, "job/main"));

    assertEquals(2, store.getMirrorCount());
    assertEquals("First", store.findMirror("First::job/main#1@1710000000001").getTeamCityBuildTypeId());
    assertEquals("Second", store.findMirror("Second::job/main#1@1710000000001").getTeamCityBuildTypeId());
    assertEquals(1, store.getLastSeenBuildNumber("First::job/main"));
    assertEquals(1, store.getLastSeenBuildNumber("Second::job/main"));
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
        jenkinsClient, new MirroredJob("conn1", "job", "buildType", "Build", 1, false));

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
        jenkinsClient, new MirroredJob("conn1", "job", "buildType", "Build", 1, false));

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
        jenkinsClient, new MirroredJob("conn1", "job", "buildType", "Build", 1, false));

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
        jenkinsClient, new MirroredJob("conn1", "job", "buildType", "Build", 1, false));

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
        jenkinsClient, new MirroredJob("conn1", "job", "buildType", "Build", 1, false));

    assertTrue(outcome.isSuccessful());
    assertEquals(2, mirrorService.vcsFetchFailureCalls);
    assertEquals(1, mirrorService.finishCalls);
  }

  @Test
  public void runningFirstTagUsesChildScmAndPrimaryRevisionBeforeBuildDataExists() throws Exception {
    JenkinsBridgeSettingsProvider provider = provider();
    BuildMirrorStore store = new BuildMirrorStore(buildMockProjectManager());
    FakeJenkinsClient jenkinsClient = new FakeJenkinsClient();
    jenkinsClient.addBuild(buildInfo());
    jenkinsClient.scmInfo = Optional.of(new JenkinsScmHeadInfo(
        VcsRefType.TAGS, "v1.0", "source", "https://github.com/org/repo.git"));
    jenkinsClient.buildVcs = new JenkinsVcsInfo(Collections.emptyList(),
        Optional.of(new JenkinsScmRevision("v1.0", "abc123")));
    CapturingMirrorService mirrorService = new CapturingMirrorService();

    pollMultibranchChild(newService(provider, jenkinsClient, mirrorService, store), jenkinsClient,
        new MirroredJob("conn1", "job", "buildType", "Build", 1, false), 1);

    assertEquals(1, mirrorService.lastQueueVcs.repositories().size());
    assertEquals("refs/tags/v1.0", mirrorService.lastQueueVcs.repositories().getFirst().rawBranchName());
    assertEquals("abc123", mirrorService.lastQueueVcs.repositories().getFirst().sha1());
  }

  @Test
  public void runningRegularJobDoesNotFetchMultibranchVcsMetadataBeforeQueueing() throws Exception {
    JenkinsBridgeSettingsProvider provider = provider();
    BuildMirrorStore store = new BuildMirrorStore(buildMockProjectManager());
    FakeJenkinsClient jenkinsClient = new FakeJenkinsClient();
    jenkinsClient.addBuild(buildInfo());
    CapturingMirrorService mirrorService = new CapturingMirrorService();

    pollJob(newService(provider, jenkinsClient, mirrorService, store), jenkinsClient,
        new MirroredJob("conn1", "job", "buildType", "Build", 1, false));

    assertEquals(0, jenkinsClient.getBuildVcsCalls);
    assertEquals(0, jenkinsClient.getBranchScmInfoCalls);
    assertNull(mirrorService.lastQueueVcs);
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
        null,
        new JenkinsParameterDefinitionService(store));
  }

  private static void activate(JenkinsBridgePollingService service) throws Exception {
    Field field = JenkinsBridgePollingService.class.getDeclaredField("started");
    field.setAccessible(true);
    ((AtomicBoolean) field.get(service)).set(true);
  }

  private static JenkinsBridgePollingService newPipelineService(
      ProjectManager projectManager,
      JenkinsClient client,
      CapturingMirrorService mirrorService,
      BuildMirrorStore store,
      JenkinsBridgeSystemProblemReporter reporter
  ) {
    SProject project = projectManager.getRootProject();
    SBuildType buildType = org.mockito.Mockito.mock(SBuildType.class);
    org.mockito.Mockito.when(projectManager.findBuildTypeByExternalId("buildType")).thenReturn(buildType);
    org.mockito.Mockito.when(buildType.getProject()).thenReturn(project);
    JenkinsClientFactory factory = org.mockito.Mockito.mock(JenkinsClientFactory.class);
    org.mockito.Mockito.when(factory.forConnectionId(project, "conn1"))
        .thenReturn(client);
    return new JenkinsBridgePollingService(
        provider(), factory, projectManager, null, mirrorService, store,
        new MirroredJobProvider(projectManager), null, null, null, reporter, null, new JenkinsParameterDefinitionService(store));
  }

  @SuppressWarnings("unchecked")
  private static Set<String> pollPipeline(JenkinsBridgePollingService service, MirroredJob mapping)
      throws Exception {
    return pollPipeline(service, mapping, false);
  }

  @SuppressWarnings("unchecked")
  private static Set<String> pollPipeline(JenkinsBridgePollingService service, MirroredJob mapping, boolean refresh)
      throws Exception {
    Method pollPipeline = JenkinsBridgePollingService.class.getDeclaredMethod("pollPipeline", MirroredJob.class, boolean.class);
    pollPipeline.setAccessible(true);
    return (Set<String>) pollPipeline.invoke(service, mapping, refresh);
  }

  /** Drives one job node through the poller after its mapping's connection is resolved. */
  private static JenkinsBridgePollingService.JobPollOutcome pollJob(
      JenkinsBridgePollingService service,
      JenkinsClient jenkinsClient,
      MirroredJobNode jobNode
  ) throws Exception {
    Method pollJob = JenkinsBridgePollingService.class.getDeclaredMethod(
        "pollJob", JenkinsClient.class, MirroredJobNode.class, List.class);
    pollJob.setAccessible(true);
    return (JenkinsBridgePollingService.JobPollOutcome) pollJob.invoke(
        service, jenkinsClient, jobNode, jenkinsClient.getBuilds(jobNode.jenkinsJob()));
  }

  private static JenkinsBridgePollingService.JobPollOutcome pollMultibranchChild(
      JenkinsBridgePollingService service,
      JenkinsClient jenkinsClient,
      MirroredJob parent,
      int recentBuildLimit
  ) throws Exception {
    MirroredChildJob child = new MirroredChildJob(
        new MirroredJob(parent.connectionId(), "parent", parent.teamCityBuildTypeExternalId(),
            parent.teamCityBuildTypeName(), recentBuildLimit, true), parent.jenkinsJob());
    return pollJob(service, jenkinsClient, child);
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
    int getBuildVcsCalls;
    int getBranchScmInfoCalls;
    BridgeHttpException buildInfoFailure;
    BridgeHttpException artifactFailure;
    BridgeHttpException vcsFailure;
    JenkinsVcsInfo buildVcs = JenkinsVcsInfo.empty();
    Optional<JenkinsScmHeadInfo> scmInfo = Optional.empty();

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

    @Override
    public JenkinsPipelineGraph getPipelineGraphView(String jobName, int buildNumber, String flowIdPrefix) {
      return null;
    }

    @NotNull
    @Override
    public JenkinsVcsInfo getBuildVcs(String jobName, int buildNumber) throws BridgeHttpException {
      getBuildVcsCalls++;
      if (vcsFailure != null) {
        throw vcsFailure;
      }
      return buildVcs;
    }

    @NotNull
    @Override
    public Optional<JenkinsScmHeadInfo> getBranchScmInfo(String jobName) {
      getBranchScmInfoCalls++;
      return scmInfo;
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

  private static class MultibranchJenkinsClient extends FakeJenkinsClient {
    final Map<String, List<JenkinsBuildInfo>> childBuilds = new LinkedHashMap<>();
    final Map<String, BridgeHttpException> failedJobs = new LinkedHashMap<>();
    final List<String> detailRequests = new ArrayList<>();
    String listedParent;
    final List<String> parameterRequests = new ArrayList<>();
    JenkinsJobParameters jobParameters =
        JenkinsJobParameters.empty();
    BridgeHttpException parameterFailure;

    @Override
    public JenkinsJobParameters getJobParameters(String job)
        throws BridgeHttpException {
      parameterRequests.add(job);
      if (parameterFailure != null) {
        throw parameterFailure;
      }
      return jobParameters;
    }


    void addChildBuild(String job, JenkinsBuildInfo build) {
      childBuilds.computeIfAbsent(job, ignored -> new ArrayList<>()).add(build);
    }

    @Override
    public Map<String, List<JenkinsBuildInfo>> listBranchBuilds(String parent) {
      listedParent = parent;
      return new LinkedHashMap<>(childBuilds);
    }

    @Override
    public List<JenkinsBuildInfo> getBuilds(String job) {
      return new ArrayList<>(childBuilds.getOrDefault(job, List.of()));
    }

    @Override
    public List<JenkinsBuildInfo> getAllBuilds(String job) {
      return getBuilds(job);
    }

    @Override
    public JenkinsBuildInfo getBuildInfo(String job, int number) throws BridgeHttpException {
      getBuildInfoCalls++;
      detailRequests.add(job);
      BridgeHttpException failure = failedJobs.get(job);
      if (failure != null) {
        throw failure;
      }
      return childBuilds.getOrDefault(job, List.of()).stream()
          .filter(build -> build.getNumber() == number)
          .findFirst()
          .orElseThrow(() -> new IllegalArgumentException("Unknown child build: " + job + "#" + number));
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
    JenkinsVcsInfo lastQueueVcs;

    CapturingMirrorService() {
      super(null, null, null, null, null, null, null, null, null, null, null, null);
    }

    @Override
    public long ensureTeamCityBuild(BuildMirror mirror, String connectionId, JenkinsBuildInfo jenkinsInfo,
                                    JenkinsPipelineGraph graph, JenkinsVcsInfo vcsInfo) {
      ensureBuildCalls++;
      lastQueueVcs = vcsInfo;
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
    MirroredJob lastMapping;

    CapturingProblemReporter() {
      super(null, null, null);
    }

    @Override
    public synchronized void reportMissingJob(MirroredJob job, BridgeHttpException failure) {
      lastMapping = job;
      missingReports++;
    }

    @Override
    public synchronized void reportConnectivity(MirroredJob job, BridgeHttpException failure) {
      lastMapping = job;
      connectivityReports++;
    }

    @Override
    public synchronized void recover(MirroredJob job) {
      lastMapping = job;
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
