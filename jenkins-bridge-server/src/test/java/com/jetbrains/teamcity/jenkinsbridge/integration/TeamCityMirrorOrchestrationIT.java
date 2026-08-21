package com.jetbrains.teamcity.jenkinsbridge.integration;

import com.jetbrains.teamcity.jenkinsbridge.artifactstorage.JenkinsStorageAutomaticActivator;
import com.jetbrains.teamcity.jenkinsbridge.http.BridgeHttpException;
import com.jetbrains.teamcity.jenkinsbridge.jenkins.JenkinsDataException;
import com.jetbrains.teamcity.jenkinsbridge.model.JenkinsBuildInfo;
import com.jetbrains.teamcity.jenkinsbridge.model.JenkinsLogChunk;
import com.jetbrains.teamcity.jenkinsbridge.persistence.BuildMirror;
import com.jetbrains.teamcity.jenkinsbridge.persistence.BuildMirrorStore;
import com.jetbrains.teamcity.jenkinsbridge.polling.JenkinsBridgePollingService;
import com.jetbrains.teamcity.jenkinsbridge.settings.JenkinsBridgeSettingsProvider;
import com.jetbrains.teamcity.jenkinsbridge.teamcity.TeamCityArtifactPublisher;
import com.jetbrains.teamcity.jenkinsbridge.teamcity.TeamCityBuildFinisher;
import com.jetbrains.teamcity.jenkinsbridge.teamcity.TeamCityBuildLogger;
import com.jetbrains.teamcity.jenkinsbridge.teamcity.TeamCityBuildMirrorService;
import com.jetbrains.teamcity.jenkinsbridge.teamcity.TeamCityBuildNumberPublisher;
import com.jetbrains.teamcity.jenkinsbridge.teamcity.TeamCityBuildParameters;
import com.jetbrains.teamcity.jenkinsbridge.teamcity.TeamCityBuildQueuer;
import com.jetbrains.teamcity.jenkinsbridge.teamcity.TeamCityBuildStarter;
import com.jetbrains.teamcity.jenkinsbridge.teamcity.TeamCityRunningBuildLocator;
import com.jetbrains.teamcity.jenkinsbridge.teamcity.TeamCityStageReporter;
import com.jetbrains.teamcity.jenkinsbridge.teamcity.TeamCityTestReporter;
import jetbrains.buildServer.serverSide.BuildCustomizerFactory;
import jetbrains.buildServer.serverSide.SBuild;
import jetbrains.buildServer.serverSide.parameters.ParameterFactory;
import org.mockito.Mockito;
import org.testng.annotations.Test;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Collections;

/** Full in-process orchestration over real TeamCity adapters and a scenario-driven Jenkins boundary. */
public class TeamCityMirrorOrchestrationIT extends TeamCityIntegrationTestBase {
  @Test
  public void finishedFreestyleScenarioQueuesLogsTestsArtifactsAndFinishes() throws Exception {
    JenkinsScenarioClient jenkins = new JenkinsScenarioClient();
    JenkinsBuildInfo finished = buildInfo(31, false, "SUCCESS");
    jenkins.withProgressiveLog(new JenkinsLogChunk("compile complete", 16L, false));

    BuildMirror mirror = newMirror(finished);
    TeamCityBuildFixture fixture = newFixtureService(false);
    invokeSyncBuild(fixture.service, fixture.mirrorStore, jenkins, mirror, finished);

    SBuild persisted = fixture.reload(mirror);
    assertNotNull(persisted);
    myFixture.waitForBuildFinished(persisted.getBuildId());
    persisted = fixture.reload(mirror);
    assertTrue(persisted.isFinished());
    assertTrue(persisted.getBuildLog().getMessages().stream()
        .anyMatch(message -> "compile complete".equals(message.getText())));
    assertEquals("31", persisted.getBuildNumber());
    assertTrue(mirror.getSyncState().name().contains("FINISHED"));
  }

  @Test
  public void mirrorStateIOExceptionPropagatesAndDoesNotFinishBuild() throws Exception {
    JenkinsScenarioClient jenkins = new JenkinsScenarioClient();
    JenkinsBuildInfo running = buildInfo(32, true, null);

    BuildMirror mirror = newMirror(running);
    TeamCityBuildFixture fixture = newFixtureService(true);

    IOException failure = expectIOException(() -> invokeSyncBuild(
        fixture.service, fixture.mirrorStore, jenkins, mirror, running));
    assertEquals("simulated mirror-state persistence outage", failure.getMessage());
    assertNotNull(mirror.getTeamCityBuildId());
    assertNull(fixture.reload(mirror));
  }

  @Test
  public void JenkinsDataExceptionDuringClassificationPropagatesBeforeTeamCityCreation() throws Exception {
    JenkinsScenarioClient jenkins = new JenkinsScenarioClient()
        .failStagesWith(new JenkinsDataException("malformed wfapi response"));
    JenkinsBuildInfo running = buildInfo(33, true, null);
    BuildMirror mirror = newMirror(running);
    TeamCityBuildFixture fixture = newFixtureService(false);

    JenkinsDataException failure = expectFailure(JenkinsDataException.class,
        () -> invokeSyncBuild(fixture.service, fixture.mirrorStore, jenkins, mirror, running));
    assertEquals("malformed wfapi response", failure.getMessage());
    assertNull(mirror.getTeamCityBuildId());
  }

  @Test
  public void JenkinsHttpExceptionDuringTestFetchStopsCompletion() throws Exception {
    JenkinsScenarioClient jenkins = new JenkinsScenarioClient()
        .failTestReportWith(new BridgeHttpException("GET", "http://jenkins/testReport", 500, "down"));
    JenkinsBuildInfo finished = buildInfo(34, false, "SUCCESS");
    BuildMirror mirror = newMirror(finished);
    TeamCityBuildFixture fixture = newFixtureService(false);

    BridgeHttpException failure = expectFailure(BridgeHttpException.class,
        () -> invokeSyncBuild(fixture.service, fixture.mirrorStore, jenkins, mirror, finished));
    assertEquals(500, failure.getStatusCode());
    assertFalse(fixture.reload(mirror).isFinished());
  }

  @Test
  public void optionalArtifactAndVcsFailuresDoNotBlockJenkinsCompletion() throws Exception {
    // The poller deliberately reports optional enrichment failures at error level. The state
    // assertions below are the contract under test, so do not let the fixture rethrow that log.
    myTestLogger.doNotFailOnErrorMessages();
    JenkinsScenarioClient jenkins = new JenkinsScenarioClient()
        .failArtifactsWith(new BridgeHttpException("GET", "http://jenkins/artifacts", 503, "down"))
        .failVcsWith(new JenkinsDataException("malformed change data"));
    JenkinsBuildInfo finished = buildInfo(35, false, "SUCCESS");
    BuildMirror mirror = newMirror(finished);
    TeamCityBuildFixture fixture = newFixtureService(false);

    invokeSyncBuild(fixture.service, fixture.mirrorStore, jenkins, mirror, finished);

    SBuild persisted = fixture.reload(mirror);
    assertNotNull(persisted);
    myFixture.waitForBuildFinished(persisted.getBuildId());
    assertTrue(fixture.reload(mirror).isFinished());
    assertFalse(mirror.isArtifactsSynced());
    assertNotNull(mirror.getArtifactSyncError());
    assertFalse(mirror.isVcsSynced());
    assertFalse(mirror.getVcsSyncErrors().isEmpty());
  }

  private static <T extends Exception> T expectFailure(Class<T> type, ThrowingOperation operation)
      throws Exception {
    try {
      operation.run();
      throw new AssertionError("Expected " + type.getSimpleName());
    } catch (Exception failure) {
      if (type.isInstance(failure)) {
        return type.cast(failure);
      }
      throw failure;
    }
  }

  private TeamCityBuildFixture newFixtureService(boolean failStore) {
    ParameterFactory parameters = myFixture.getSingletonService(ParameterFactory.class);
    myBuildType.addConfigParameter(parameters.createSimpleParameter(
        TeamCityBuildParameters.AGENTLESS_BUILD_PROPERTY, "true"));
    myBuildType.schedulePersisting("Jenkins Bridge test: create build configuration")
        .awaitUninterruptibly();
    TeamCityRunningBuildLocator locator = new TeamCityRunningBuildLocator(
        myFixture.getBuildsManager(), myFixture.getBuildPromotionManager(), myProjectManager);
    TeamCityBuildQueuer queuer = new TeamCityBuildQueuer(
        myProjectManager, myFixture.getSingletonService(BuildCustomizerFactory.class), null);
    BuildMirrorStore mirrorStore = new BuildMirrorStore(null, null, null) {
      private int saves;

      @Override
      public synchronized void saveMirror(BuildMirror mirror) throws IOException {
        if (failStore && saves++ == 2) {
          throw new IOException("simulated mirror-state persistence outage");
        }
      }
    };
    TeamCityBuildMirrorService service = new TeamCityBuildMirrorService(
        Mockito.mock(JenkinsBridgeSettingsProvider.class),
        locator,
        queuer,
        new TeamCityBuildStarter(myFixture.getBuildsManager(), locator),
        new TeamCityBuildLogger(locator, myFixture.getBuildAgentMessagesQueue()),
        new TeamCityTestReporter(locator, myFixture.getBuildAgentMessagesQueue()),
        new TeamCityStageReporter(locator, myFixture.getBuildAgentMessagesQueue()),
        new TeamCityArtifactPublisher(locator, new FixedStorageActivator()),
        null,
        new TeamCityBuildNumberPublisher(locator),
        new TeamCityBuildFinisher(locator, myFixture.getBuildAgentMessagesQueue()),
        null,
        mirrorStore);
    return new TeamCityBuildFixture(service, locator, mirrorStore);
  }

  private BuildMirror newMirror(JenkinsBuildInfo info) {
    return BuildMirror.create("orchestration-job#" + info.getNumber(), "orchestration-job", info,
        myBuildType.getExternalId(), "orchestration-now");
  }

  private static JenkinsBuildInfo buildInfo(int number, boolean building, String result) {
    com.google.gson.JsonObject json = new com.google.gson.JsonObject();
    json.addProperty("number", number);
    json.addProperty("timestamp", 1710000000000L + number);
    json.addProperty("url", "http://jenkins/job/orchestration-job/" + number + "/");
    json.addProperty("building", building);
    if (result != null) {
      json.addProperty("result", result);
    }
    return JenkinsBuildInfo.fromJson(json);
  }

  private static void invokeSyncBuild(TeamCityBuildMirrorService service, BuildMirrorStore mirrorStore,
      JenkinsScenarioClient jenkins, BuildMirror mirror, JenkinsBuildInfo info) throws Exception {
    JenkinsBridgePollingService polling = new JenkinsBridgePollingService(
        null, null, null, service, mirrorStore, null, null, null, null, null);
    Method method = JenkinsBridgePollingService.class.getDeclaredMethod(
        "syncBuild", com.jetbrains.teamcity.jenkinsbridge.jenkins.JenkinsClient.class,
        String.class, BuildMirror.class, JenkinsBuildInfo.class);
    method.setAccessible(true);
    try {
      method.invoke(polling, jenkins, "orchestration-connection", mirror, info);
    } catch (InvocationTargetException exception) {
      Throwable cause = exception.getCause();
      if (cause instanceof Exception) {
        throw (Exception) cause;
      }
      throw exception;
    }
  }

  private static IOException expectIOException(ThrowingOperation operation) throws Exception {
    try {
      operation.run();
      throw new AssertionError("Expected IOException");
    } catch (IOException expected) {
      return expected;
    }
  }

  private interface ThrowingOperation {
    void run() throws Exception;
  }

  private static final class TeamCityBuildFixture {
    private final TeamCityBuildMirrorService service;
    private final TeamCityRunningBuildLocator locator;
    private final BuildMirrorStore mirrorStore;

    private TeamCityBuildFixture(TeamCityBuildMirrorService service, TeamCityRunningBuildLocator locator,
        BuildMirrorStore mirrorStore) {
      this.service = service;
      this.locator = locator;
      this.mirrorStore = mirrorStore;
    }

    private SBuild reload(BuildMirror mirror) {
      return locator.findPromotion(mirror.getTeamCityBuildId()).getAssociatedBuild();
    }
  }

  private static final class FixedStorageActivator extends JenkinsStorageAutomaticActivator {
    private FixedStorageActivator() {
      super(null, null, null);
    }

    @Override
    public String activateJenkinsStorage(String externalProjectId) {
      return "IT-JENKINS-STORAGE";
    }
  }
}
