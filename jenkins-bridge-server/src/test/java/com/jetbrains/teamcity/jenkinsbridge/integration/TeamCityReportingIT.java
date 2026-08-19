package com.jetbrains.teamcity.jenkinsbridge.integration;

import com.jetbrains.teamcity.jenkinsbridge.teamcity.TeamCityStageReporter;
import com.jetbrains.teamcity.jenkinsbridge.teamcity.TeamCityTestReporter;
import jetbrains.buildServer.serverSide.RunningBuildEx;
import org.testng.annotations.Test;

import java.util.Collections;
import java.util.Map;

/** Verifies that reporting adapters persist their server messages on a real TeamCity build. */
public class TeamCityReportingIT extends TeamCityIntegrationTestBase {
  @Test
  public void persistsStageAndTestMessagesOnTargetBuild() throws Exception {
    Map<String, String> bridgeParameters = Collections.singletonMap("jenkins.build.key", "reporting-1");
    TeamCityBuildFixture build = queueAndStartBuild(bridgeParameters, Collections.emptyMap());
    RunningBuildEx runningBuild = build.getRunningBuild();
    build.logger().addBuildLog(build.getPromotionId(), "outside-stage");
    TeamCityStageReporter stages = new TeamCityStageReporter(
        build.getLocator(), build.getMessagesQueue());
    stages.report(build.getPromotionId(), stages.messagesForStage(
        "Build", null, null, true, "inside-stage", true));

    new TeamCityTestReporter(build.getLocator(), build.getMessagesQueue()).reportTests(
        build.getPromotionId(), JenkinsTestReportFixtures.passedTest("unit", "ExampleTest", "passes"));

    build.flush();
    assertTrue(runningBuild.getBuildLog().getMessages().stream()
        .anyMatch(message -> "outside-stage".equals(message.getText())));
    assertTrue(runningBuild.getBuildLog().getMessages().stream()
        .anyMatch(message -> "inside-stage".equals(message.getText())));
    assertTrue(runningBuild.getBuildLog().getMessages().stream()
        .anyMatch(message -> message.getText() != null && message.getText().contains("ExampleTest.passes")));
  }
}
