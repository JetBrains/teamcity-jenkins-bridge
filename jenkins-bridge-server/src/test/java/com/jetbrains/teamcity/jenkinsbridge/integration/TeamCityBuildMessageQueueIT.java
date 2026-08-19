package com.jetbrains.teamcity.jenkinsbridge.integration;

import com.jetbrains.teamcity.jenkinsbridge.teamcity.TeamCityRunningBuildNotFoundException;
import com.jetbrains.teamcity.jenkinsbridge.teamcity.TeamCityStageReporter;
import com.jetbrains.teamcity.jenkinsbridge.teamcity.TeamCityTestReporter;
import jetbrains.buildServer.serverSide.RunningBuildEx;
import jetbrains.buildServer.serverSide.SBuild;
import org.testng.annotations.Test;

import java.util.Collections;

/** Verifies production message adapters against TeamCity's real BuildAgentMessagesQueue. */
public class TeamCityBuildMessageQueueIT extends TeamCityIntegrationTestBase {
  @Test
  public void logStageAndTestMessagesPersistAfterQueueFlushAndReload() throws Exception {
    TeamCityBuildFixture build = queueAndStartBuild(
        Collections.singletonMap("jenkins.build.key", "message-queue-1"), Collections.emptyMap());
    RunningBuildEx runningBuild = build.getRunningBuild();

    build.logger().addBuildLog(build.getPromotionId(), "log-line");
    TeamCityStageReporter stageReporter = new TeamCityStageReporter(
        build.getLocator(), build.getMessagesQueue());
    stageReporter.report(build.getPromotionId(), stageReporter.messagesForStage(
        "Compile", null, null, true, "stage-line", true));
    new TeamCityTestReporter(build.getLocator(), build.getMessagesQueue()).reportTests(
        build.getPromotionId(), JenkinsTestReportFixtures.passedTest("unit", "BuildTest", "works"));

    build.flush();
    SBuild reloaded = build.reload();
    assertNotNull(reloaded);
    assertTrue(reloaded.getBuildLog().getMessages().stream()
        .anyMatch(message -> "log-line".equals(message.getText())));
    assertTrue(reloaded.getBuildLog().getMessages().stream()
        .anyMatch(message -> "stage-line".equals(message.getText())));
    assertTrue(reloaded.getBuildLog().getMessages().stream()
        .anyMatch(message -> message.getText() != null && message.getText().contains("BuildTest.works")));
    assertFalse(reloaded.isFinished());
    assertFalse(runningBuild.isFinished());
  }

  @Test
  public void messageAdaptersRejectFinishedBuildsInsteadOfSilentlyNoOp() throws Exception {
    TeamCityBuildFixture build = queueAndStartBuild(
        Collections.singletonMap("jenkins.build.key", "message-queue-finished"), Collections.emptyMap());
    build.finish("SUCCESS", new java.util.Date());

    expectMissingBuild(() -> build.logger().addBuildLog(build.getPromotionId(), "late-log"));
    TeamCityStageReporter stageReporter = new TeamCityStageReporter(
        build.getLocator(), build.getMessagesQueue());
    expectMissingBuild(() -> stageReporter.report(build.getPromotionId(),
        stageReporter.messagesForStage("Late stage", null, null, true, "late-stage", true)));
    TeamCityTestReporter testReporter = new TeamCityTestReporter(
        build.getLocator(), build.getMessagesQueue());
    expectMissingBuild(() -> testReporter.reportTests(build.getPromotionId(),
        JenkinsTestReportFixtures.passedTest("unit", "LateTest", "notDelivered")));
  }

  private void expectMissingBuild(ThrowingOperation operation) throws Exception {
    try {
      operation.run();
      fail("Expected a missing-running-build failure");
    } catch (TeamCityRunningBuildNotFoundException expected) {
      assertTrue(expected.getMessage().contains("build"));
    }
  }

  @FunctionalInterface
  private interface ThrowingOperation {
    void run() throws Exception;
  }
}
