package com.jetbrains.teamcity.jenkinsbridge.teamcity;

import com.google.gson.JsonParser;
import com.jetbrains.teamcity.jenkinsbridge.http.BridgeHttpException;
import com.jetbrains.teamcity.jenkinsbridge.model.JenkinsArtifact;
import com.jetbrains.teamcity.jenkinsbridge.model.JenkinsArtifacts;
import com.jetbrains.teamcity.jenkinsbridge.model.JenkinsBuildInfo;
import com.jetbrains.teamcity.jenkinsbridge.model.JenkinsVcsInfo;
import com.jetbrains.teamcity.jenkinsbridge.persistence.BuildMirror;
import com.jetbrains.teamcity.jenkinsbridge.persistence.BuildMirrorStore;
import com.jetbrains.teamcity.jenkinsbridge.vcs.VcsSyncResult;
import jetbrains.buildServer.serverSide.CustomDataStorage;
import jetbrains.buildServer.serverSide.BuildPromotionEx;
import jetbrains.buildServer.serverSide.ProjectManager;
import jetbrains.buildServer.serverSide.SProject;
import org.mockito.ArgumentCaptor;
import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class TeamCityBuildMirrorServiceTest {
  private final JsonParser parser = new JsonParser();

  @Test
  public void syncLogsDoesNotAdvanceOffsetWhenTeamCityRejectsDelivery() throws Exception {
    TeamCityBuildMirrorService service = new TeamCityBuildMirrorService(
        null, null, null, null, new FailingLogger(), null, null, null, null, null, null, new NoopStore());
    BuildMirror mirror = new BuildMirror();
    mirror.setLastLogOffset(10L);

    try {
      service.syncLogs(mirror, 1L, new com.jetbrains.teamcity.jenkinsbridge.model.JenkinsLogChunk("new log", 20L, false));
      fail("Expected TeamCityRunningBuildNotFoundException");
    } catch (TeamCityRunningBuildNotFoundException expected) {
      assertEquals(10L, mirror.getLastLogOffset());
    }
  }

  @Test
  public void skippedPipelineNodeFinishesGreenNotRed() {
    // Parity: Jenkins shows a skipped stage as neutral, so NOT_EXECUTED maps to SUCCESS (green), not red.
    assertEquals("SUCCESS", TeamCityBuildMirrorService.jenkinsResultForPipelineNodeStatus("NOT_EXECUTED"));
    // Real failures still map to a failed (red) result.
    assertEquals("FAILURE", TeamCityBuildMirrorService.jenkinsResultForPipelineNodeStatus("FAILED"));
    assertEquals("UNSTABLE", TeamCityBuildMirrorService.jenkinsResultForPipelineNodeStatus("UNSTABLE"));
  }

  @Test
  public void ensureTeamCityBuildPassesSavedJenkinsParametersToQueuer() throws Exception {
    CapturingQueuer queuer = new CapturingQueuer();
    TeamCityBuildMirrorService service = new TeamCityBuildMirrorService(
        null, new NoExistingBuildLocator(), queuer, null, null, null, null, null, null, null, null, new NoopStore());

    BuildMirror mirror = BuildMirror.create("job#4@1710000000004", "job", buildInfo(4), "buildType", "now");
    Map<String, String> parameters = new LinkedHashMap<String, String>();
    parameters.put("BRANCH", "feature/x");
    mirror.setJenkinsBuildParameters(parameters);

    service.ensureTeamCityBuild(mirror, "conn1", buildInfo(4), null, null);

    assertEquals("conn1", queuer.bridgeParameters.get("jenkins.connection.id"));
    assertEquals("job", queuer.bridgeParameters.get("jenkins.job"));
    assertEquals("job#4@1710000000004", queuer.bridgeParameters.get("jenkins.build.key"));
    assertEquals("1710000000004", queuer.bridgeParameters.get("jenkins.build.timestamp"));
    assertEquals("feature/x", queuer.jenkinsParameters.get("BRANCH"));
  }

  @Test
  public void stampExistingPromotionAddsJenkinsIdentityBeforeFinishing() {
    TeamCityRunningBuildLocator locator = mock(TeamCityRunningBuildLocator.class);
    BuildPromotionEx promotion = mock(BuildPromotionEx.class);
    when(locator.findPromotion(42L)).thenReturn(promotion);
    when(promotion.getCustomParameters()).thenReturn(new LinkedHashMap<String, String>());

    TeamCityBuildMirrorService service = new TeamCityBuildMirrorService(
        null, locator, null, null, null, null, null, null, null, null, null, new NoopStore());
    BuildMirror mirror = BuildMirror.create("job#4@1710000000004", "job", buildInfo(4), "buildType", "now");

    service.stampExistingPromotion(42L, mirror, "conn1", buildInfo(4));

    ArgumentCaptor<Map> captor = ArgumentCaptor.forClass(Map.class);
    verify(promotion).setCustomParameters(captor.capture());
    Map parameters = captor.getValue();
    assertEquals("job", parameters.get("jenkins.job"));
    assertEquals("4", parameters.get("jenkins.build.number"));
    assertEquals("job#4@1710000000004", parameters.get("jenkins.build.key"));
    assertEquals("conn1", parameters.get("jenkins.connection.id"));
  }

  @Test
  public void syncArtifactMetadataRegistersArtifactListAndLogsSummary() throws Exception {
    CapturingArtifactListPublisher publisher = new CapturingArtifactListPublisher();
    CapturingLogger logger = new CapturingLogger();
    TeamCityBuildMirrorService service = new TeamCityBuildMirrorService(
        null, null, null, null, logger, null, publisher, null, null, null, null, new NoopStore());

    BuildMirror mirror = BuildMirror.create("job#8@1710000000008", "job", buildInfo(8), "buildType", "now");

    service.syncArtifactMetadataIfNeeded(mirror, 77L, artifacts(
        "{\"artifacts\":["
            + "{\"fileName\":\"app.jar\",\"relativePath\":\"target/app.jar\"},"
            + "{\"fileName\":\"report.txt\",\"relativePath\":\"reports/unit/report.txt\"}"
            + "]}"));

    assertTrue(mirror.isArtifactsSynced());
    assertNull(mirror.getArtifactSyncError());
    assertEquals(2, publisher.published.size());
    assertEquals("target/app.jar", publisher.published.get(0).relativePath());
    assertEquals("reports/unit/report.txt", publisher.published.get(1).relativePath());
    assertTrue(logger.texts.get(0).contains("Registered artifacts: 2"));
  }

  @Test
  public void syncArtifactMetadataTreatsMissingArtifactsAsEmptyList() throws Exception {
    CapturingArtifactListPublisher publisher = new CapturingArtifactListPublisher();
    CapturingLogger logger = new CapturingLogger();
    TeamCityBuildMirrorService service = new TeamCityBuildMirrorService(
        null, null, null, null, logger, null, publisher, null, null, null, null, new NoopStore());

    BuildMirror mirror = BuildMirror.create("job#9@1710000000009", "job", buildInfo(9), "buildType", "now");

    service.syncArtifactMetadataIfNeeded(mirror, 77L, artifacts("{}"));

    assertTrue(mirror.isArtifactsSynced());
    assertTrue(publisher.published.isEmpty());
    assertTrue(logger.texts.get(0).contains("Registered artifacts: 0"));
  }

  @Test
  public void syncArtifactMetadataIsIdempotentOnceSynced() throws Exception {
    CapturingArtifactListPublisher publisher = new CapturingArtifactListPublisher();
    TeamCityBuildMirrorService service = new TeamCityBuildMirrorService(
        null, null, null, null, new CapturingLogger(), null, publisher, null, null, null, null, new NoopStore());

    BuildMirror mirror = BuildMirror.create("job#10@1710000000010", "job", buildInfo(10), "buildType", "now");

    service.syncArtifactMetadataIfNeeded(mirror, 77L, artifacts(
        "{\"artifacts\":[{\"fileName\":\"a\",\"relativePath\":\"a\"}]}"));
    service.syncArtifactMetadataIfNeeded(mirror, 77L, artifacts(
        "{\"artifacts\":[{\"fileName\":\"a\",\"relativePath\":\"a\"},{\"fileName\":\"b\",\"relativePath\":\"b\"}]}"));

    assertEquals(1, publisher.published.size());
  }

  @Test
  public void syncArtifactMetadataLeavesArtifactsUnsyncedWhenPublishListThrows() throws Exception {
    CapturingArtifactListPublisher publisher = new CapturingArtifactListPublisher();
    publisher.fail = true;
    CapturingLogger logger = new CapturingLogger();
    TeamCityBuildMirrorService service = new TeamCityBuildMirrorService(
        null, null, null, null, logger, null, publisher, null, null, null, null, new NoopStore());

    BuildMirror mirror = BuildMirror.create("job#11@1710000000011", "job", buildInfo(11), "buildType", "now");

    service.syncArtifactMetadataIfNeeded(mirror, 77L, artifacts(
        "{\"artifacts\":[{\"fileName\":\"a\",\"relativePath\":\"a\"}]}"));

    assertTrue(!mirror.isArtifactsSynced());
    assertTrue(mirror.getArtifactSyncError().contains("IOException"));
    assertTrue(logger.texts.get(0).contains("Failures: 1"));
  }

  @Test(expected = NullPointerException.class)
  public void syncArtifactMetadataDoesNotHideUnexpectedNullPointer() throws Exception {
    CapturingArtifactListPublisher publisher = new CapturingArtifactListPublisher();
    publisher.throwNullPointer = true;
    TeamCityBuildMirrorService service = new TeamCityBuildMirrorService(
        null, null, null, null, new CapturingLogger(), null, publisher,
        null, null, null, null, new NoopStore());

    service.syncArtifactMetadataIfNeeded(
        BuildMirror.create("job#12@1710000000012", "job", buildInfo(12), "buildType", "now"),
        77L,
        artifacts("{\"artifacts\":[{\"fileName\":\"a\",\"relativePath\":\"a\"}]}"));
  }

  @Test
  public void syncBuildNumberUpdatesRunningBuild() throws Exception {
    CapturingBuildNumberPublisher publisher = new CapturingBuildNumberPublisher();
    TeamCityBuildMirrorService service = new TeamCityBuildMirrorService(
        null, null, null, null, null, null, null, null, publisher, null, null, new NoopStore());

    BuildMirror mirror = BuildMirror.create("job#4@1710000000004", "job", buildInfo(4), "buildType", "now");
    mirror.setTeamCityBuildId(99L);

    service.syncBuildNumber(mirror);

    assertEquals(1, publisher.calls);
    assertEquals(Long.valueOf(99L), publisher.lastBuildId);
    assertEquals(4, publisher.lastJenkinsBuildNumber);
  }

  @Test
  public void syncVcsShortCircuitsWhenAlreadySynced() throws Exception {
    CapturingVcsPublisher publisher = new CapturingVcsPublisher();
    TeamCityBuildMirrorService service = new TeamCityBuildMirrorService(
        null, null, null, null, new CapturingLogger(), null, null, publisher, null, null, null, new NoopStore());

    BuildMirror mirror = BuildMirror.create("job#1@1", "job", buildInfo(1), "buildType", "now");
    mirror.setVcsSynced(true);

    service.syncVcsIfNeeded(mirror, gitVcsInfo());

    assertEquals(0, publisher.calls);
  }

  @Test
  public void syncVcsEmptyWhenFinishedMarksSynced() throws Exception {
    CapturingVcsPublisher publisher = new CapturingVcsPublisher();
    TeamCityBuildMirrorService service = new TeamCityBuildMirrorService(
        null, null, null, null, new CapturingLogger(), null, null, publisher, null, null, null, new NoopStore());

    BuildMirror mirror = BuildMirror.create("job#1@1", "job", buildInfo(1), "buildType", "now");

    service.syncVcsIfNeeded(mirror, JenkinsVcsInfo.empty());

    assertTrue(mirror.isVcsSynced());
    assertTrue(mirror.getVcsSyncErrors().isEmpty());
    assertEquals(0, publisher.calls);
  }

  @Test
  public void syncVcsMarksSyncedAndLogsSummaryOnSuccess() throws Exception {
    CapturingVcsPublisher publisher = new CapturingVcsPublisher();
    publisher.result.incrementAttached();
    CapturingLogger logger = new CapturingLogger();
    TeamCityBuildMirrorService service = new TeamCityBuildMirrorService(
        null, null, null, null, logger, null, null, publisher, null, null, null, new NoopStore());

    BuildMirror mirror = BuildMirror.create("job#1@1", "job", buildInfo(1), "buildType", "now");

    service.syncVcsIfNeeded(mirror, gitVcsInfo());

    assertEquals(1, publisher.calls);
    assertTrue(mirror.isVcsSynced());
    assertTrue(mirror.getVcsSyncErrors().isEmpty());
    assertTrue(logger.texts.isEmpty());
  }

  @Test
  public void syncVcsPublisherErrorsRemainRetryable() throws Exception {
    CapturingVcsPublisher publisher = new CapturingVcsPublisher();
    publisher.result.incrementAttached();
    publisher.result.addError("git@host:org/repo.git: auth failed");
    CapturingLogger logger = new CapturingLogger();
    TeamCityBuildMirrorService service = new TeamCityBuildMirrorService(
        null, null, null, null, logger, null, null, publisher, null, null, null, new NoopStore());

    BuildMirror mirror = BuildMirror.create("job#1@1", "job", buildInfo(1), "buildType", "now");

    service.syncVcsIfNeeded(mirror, gitVcsInfo());

    assertFalse(mirror.isVcsSynced());
    assertTrue(hasVcsErrorContaining(mirror, "auth failed"));
    assertTrue(logger.texts.isEmpty());
  }

  @Test
  public void syncVcsPublisherExceptionRemainsRetryable() throws Exception {
    CapturingVcsPublisher publisher = new CapturingVcsPublisher();
    publisher.throwError = true;
    publisher.operationalError = new TeamCityVcsOperationalException(
        new RuntimeException("read only"));
    TeamCityBuildMirrorService service = new TeamCityBuildMirrorService(
        null, null, null, null, new CapturingLogger(), null, null, publisher, null, null, null, new NoopStore());

    BuildMirror mirror = BuildMirror.create("job#1@1", "job", buildInfo(1), "buildType", "now");

    service.syncVcsIfNeeded(mirror, gitVcsInfo());

    assertFalse(mirror.isVcsSynced());
    assertTrue(hasVcsErrorContaining(mirror, "RuntimeException"));
  }

  @Test(expected = IllegalStateException.class)
  public void syncVcsDoesNotHideUnexpectedRuntimeFailure() throws Exception {
    CapturingVcsPublisher publisher = new CapturingVcsPublisher();
    publisher.throwError = true;
    publisher.unexpectedError = new IllegalStateException("unexpected bridge defect");
    TeamCityBuildMirrorService service = new TeamCityBuildMirrorService(
        null, null, null, null, new CapturingLogger(), null, null, publisher, null, null, null, new NoopStore());

    service.syncVcsIfNeeded(
        BuildMirror.create("job#1@1", "job", buildInfo(1), "buildType", "now"), gitVcsInfo());
  }

  @Test
  public void recordVcsFetchFailureLeavesMirrorRetryable() throws Exception {
    TeamCityBuildMirrorService service = new TeamCityBuildMirrorService(
        null, null, null, null, new CapturingLogger(), null, null, null, null, null, null, new NoopStore());
    BuildMirror mirror = BuildMirror.create("job#1@1", "job", buildInfo(1), "buildType", "now");

    service.recordVcsFetchFailure(mirror, new BridgeHttpException("GET", "http://jenkins/job/job/1/api/json", 503, "unavailable"));

    assertFalse(mirror.isVcsSynced());
    assertTrue(hasVcsErrorContaining(mirror, "503"));
  }

  private boolean hasVcsErrorContaining(BuildMirror mirror, String text) {
    for (String error : mirror.getVcsSyncErrors()) {
      if (error.contains(text)) {
        return true;
      }
    }
    return false;
  }

  private JenkinsVcsInfo gitVcsInfo() {
    String json = "{\"actions\":[{\"_class\":\"hudson.plugins.git.util.BuildData\","
        + "\"lastBuiltRevision\":{\"SHA1\":\"8f2fd2f092c3b923e1c7b42c0d6b87aea49d2771\","
        + "\"branch\":[{\"name\":\"refs/remotes/origin/main\"}]},"
        + "\"remoteUrls\":[\"https://github.com/org/repo.git\"]}]}";
    return JenkinsVcsInfo.fromJson(parser.parse(json).getAsJsonObject());
  }

  private JenkinsArtifacts artifacts(String json) {
    return JenkinsArtifacts.fromJson(parser.parse(json).getAsJsonObject());
  }

  private JenkinsBuildInfo buildInfo(int number) {
    String json = "{\"number\":" + number + ",\"building\":true,"
        + "\"timestamp\":" + (1710000000000L + number) + ","
        + "\"url\":\"http://jenkins/job/job/" + number + "/\"}";
    return JenkinsBuildInfo.fromJson(parser.parse(json).getAsJsonObject());
  }

  private static class CapturingArtifactListPublisher extends TeamCityArtifactPublisher {
    List<JenkinsArtifact> published = new ArrayList<>();
    boolean fail;
    boolean throwNullPointer;

    CapturingArtifactListPublisher() {
      super(null);
    }

    @Override
    public void publishArtifactList(long buildId, List<JenkinsArtifact> artifacts) throws IOException {
      if (throwNullPointer) {
        throw new NullPointerException("unexpected bridge defect");
      }
      if (fail) {
        throw new IOException("boom");
      }
      published = new ArrayList<>(artifacts);
    }
  }

  private static class CapturingVcsPublisher extends TeamCityVcsPublisher {
    int calls;
    boolean throwError;
    TeamCityVcsOperationalException operationalError;
    RuntimeException unexpectedError;
    final VcsSyncResult result = new VcsSyncResult();

    CapturingVcsPublisher() {
      super(null, null, null, null);
    }

    @Override
    public VcsSyncResult applyVcsToBuild(BuildMirror mirror, JenkinsVcsInfo vcsInfo)
        throws TeamCityVcsOperationalException {
      calls++;
      if (throwError) {
        if (operationalError != null) {
          throw operationalError;
        }
        if (unexpectedError != null) {
          throw unexpectedError;
        }
        throw new IllegalStateException("boom");
      }
      return result;
    }
  }

  private static class CapturingBuildNumberPublisher extends TeamCityBuildNumberPublisher {
    int calls;
    Long lastBuildId;
    int lastJenkinsBuildNumber;

    CapturingBuildNumberPublisher() {
      super(null);
    }

    @Override
    public boolean publishBuildNumber(long buildId, int jenkinsBuildNumber) {
      calls++;
      lastBuildId = buildId;
      lastJenkinsBuildNumber = jenkinsBuildNumber;
      return true;
    }
  }

  private static class CapturingLogger extends TeamCityBuildLogger {
    final List<String> texts = new ArrayList<String>();

    CapturingLogger() {
      super(null, null);
    }

    @Override
    public void addBuildLog(long buildId, String text) {
      texts.add(text);
    }
  }

  private static class FailingLogger extends TeamCityBuildLogger {
    FailingLogger() {
      super(null, null);
    }

    @Override
    public void addBuildLog(long buildId, String text) {
      throw new TeamCityRunningBuildNotFoundException("TeamCity running build not found for id " + buildId);
    }
  }

  private static ProjectManager mockProjectManager() {
    CustomDataStorage storage = mock(CustomDataStorage.class);
    when(storage.getValues()).thenReturn(new LinkedHashMap<>());

    SProject rootProject = mock(SProject.class);
    when(rootProject.getCustomDataStorage(BuildMirrorStore.CUSTOM_DATA_STORAGE_NAME)).thenReturn(storage);

    ProjectManager projectManager = mock(ProjectManager.class);
    when(projectManager.getRootProject()).thenReturn(rootProject);
    return projectManager;
  }

  private static class NoopStore extends BuildMirrorStore {
    NoopStore() {
      super(mockProjectManager());
    }

    @Override
    public void saveMirror(BuildMirror mirror) {
      // no-op: tests assert on the in-memory mirror, not on disk
    }
  }

  /**
   * Reports that no mirror build exists yet, so the service has to queue one.
   */
  private static class NoExistingBuildLocator extends TeamCityRunningBuildLocator {
    NoExistingBuildLocator() {
      super(null, null, null);
    }

    @Override
    public Long recoverBuildId(String buildTypeId, String jenkinsBuildKey) {
      return null;
    }
  }

  private static class CapturingQueuer extends TeamCityBuildQueuer {
    Map<String, String> bridgeParameters;
    Map<String, String> jenkinsParameters;

    CapturingQueuer() {
      super(null, null, null);
    }

    @Override
    public long queueAgentlessBuild(
        String buildTypeId,
        Map<String, String> properties,
        Map<String, String> jenkinsBuildParameters,
        JenkinsVcsInfo vcsInfo
    ) {
      bridgeParameters = new LinkedHashMap<>(properties);
      jenkinsParameters = new LinkedHashMap<>(jenkinsBuildParameters);
      return 55L;
    }
  }
}
