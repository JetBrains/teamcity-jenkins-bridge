package com.jetbrains.teamcity.jenkinsbridge.integration;

import com.jetbrains.teamcity.jenkinsbridge.teamcity.TeamCityBuildFinisher;
import jetbrains.buildServer.messages.Status;
import jetbrains.buildServer.serverSide.SBuild;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.util.Collections;
import java.util.Date;

/** Verifies Jenkins terminal results as persisted TeamCity outcomes. */
public class TeamCityFinishResultIT extends TeamCityIntegrationTestBase {
  @DataProvider(name = "failedResults")
  public Object[][] failedResults() {
    return new Object[][]{{"FAILURE"}, {"UNKNOWN_RESULT"}};
  }

  @Test
  public void successFinishesNormallyAndRepeatedFinishIsNoOp() throws Exception {
    TeamCityBuildFixture build = queueAndStartBuild(
        Collections.singletonMap("jenkins.build.key", "result-success"), Collections.emptyMap());
    Date finishTime = new Date();
    build.finish("SUCCESS", finishTime);

    SBuild finished = build.reload();
    assertTrue(finished.isFinished());
    assertEquals(Status.NORMAL, finished.getBuildStatus());
    assertEquals(finishTime, finished.getFinishDate());

    build.finish("FAILURE", new Date(finishTime.getTime() + 1_000));
    assertEquals(Status.NORMAL, build.reload().getBuildStatus());
  }

  @Test
  public void unstableFinishesSuccessfullyWithStatusText() throws Exception {
    TeamCityBuildFixture build = queueAndStartBuild(
        Collections.singletonMap("jenkins.build.key", "result-unstable"), Collections.emptyMap());
    build.finish("UNSTABLE", new Date());

    SBuild finished = build.reload();
    assertTrue(finished.isFinished());
    assertEquals(Status.NORMAL, finished.getBuildStatus());
  }

  @Test(dataProvider = "failedResults")
  public void failureResultsPersistOneJenkinsBuildProblem(String jenkinsResult) throws Exception {
    TeamCityBuildFixture build = queueAndStartBuild(
        Collections.singletonMap("jenkins.build.key", "result-" + jenkinsResult), Collections.emptyMap());
    build.finish(jenkinsResult, new Date());

    SBuild finished = build.reload();
    assertTrue(finished.isFinished());
    assertEquals(Status.FAILURE, finished.getBuildStatus());
    assertTrue(finished.hasBuildProblemOfType("jenkinsBuildResult"));

    new TeamCityBuildFinisher(build.getLocator(), build.getMessagesQueue())
        .finishBuild(build.getPromotionId(), new Date(), jenkinsResult);
    assertTrue(finished.hasBuildProblemOfType("jenkinsBuildResult"));
  }

  @Test
  public void abortedAndNotBuiltInterruptTheRunningBuild() throws Exception {
    assertInterrupted("ABORTED", "result-aborted");
    assertInterrupted("NOT_BUILT", "result-not-built");
  }

  private void assertInterrupted(String result, String key) throws Exception {
    TeamCityBuildFixture build = queueAndStartBuild(
        Collections.singletonMap("jenkins.build.key", key), Collections.emptyMap());
    build.finish(result, new Date());
    assertTrue(build.getRunningBuild().isInterrupted());
    assertTrue(build.reload().isFinished());
  }

}
