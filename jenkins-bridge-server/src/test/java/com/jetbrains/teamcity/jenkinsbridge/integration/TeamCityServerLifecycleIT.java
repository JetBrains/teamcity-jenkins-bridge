package com.jetbrains.teamcity.jenkinsbridge.integration;

import com.jetbrains.teamcity.jenkinsbridge.teamcity.TeamCityBuildFinisher;
import com.jetbrains.teamcity.jenkinsbridge.teamcity.TeamCityBuildLogger;
import com.jetbrains.teamcity.jenkinsbridge.teamcity.TeamCityBuildParameters;
import com.jetbrains.teamcity.jenkinsbridge.teamcity.TeamCityBuildNumberPublisher;
import jetbrains.buildServer.messages.Status;
import jetbrains.buildServer.serverSide.BuildPromotion;
import jetbrains.buildServer.serverSide.RunningBuildEx;
import jetbrains.buildServer.serverSide.SBuild;
import org.testng.annotations.Test;

import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Exercises the bridge's server-side build lifecycle against TeamCity's real database-backed test
 * server. This deliberately avoids Mockito: queue, promotion, build, message, and finish state are
 * all TeamCity implementations supplied by {@code server-test-core}.
 */
public class TeamCityServerLifecycleIT extends TeamCityIntegrationTestBase {

  @Test
  public void queuesStartsLogsAndFinishesAgentlessBuild() throws Exception {
    // JenkinsJobImporter marks every mirror configuration this way. TeamCity copies the marker to
    // the promotion's internal attributes when the promotion is created.
    Map<String, String> bridgeParameters = new LinkedHashMap<>();
    bridgeParameters.put("jenkins.job", "folder/job");
    bridgeParameters.put("jenkins.build.number", "42");

    Map<String, String> jenkinsParameters = new LinkedHashMap<>();
    jenkinsParameters.put("DEPLOY_ENV", "staging");

    TeamCityBuildFixture build = queueAndStartBuild(bridgeParameters, jenkinsParameters);
    long promotionId = build.getPromotionId();

    BuildPromotion promotion = myFixture.getBuildPromotionManager().findPromotionById(promotionId);
    assertNotNull(promotion);
    assertNull(promotion.getQueuedBuild());
    assertEquals("true", promotion.getParameterValue(TeamCityBuildParameters.AGENTLESS_BUILD_PROPERTY));
    assertEquals("folder/job", promotion.getParameterValue("jenkins.job"));
    assertEquals("42", promotion.getParameterValue("jenkins.build.number"));
    assertEquals("staging", promotion.getParameterValue("DEPLOY_ENV"));

    RunningBuildEx runningBuild = build.getRunningBuild();
    assertFalse(runningBuild.isFinished());

    TeamCityBuildNumberPublisher buildNumberPublisher = new TeamCityBuildNumberPublisher(build.getLocator());
    assertTrue(buildNumberPublisher.publishBuildNumber(promotionId, 42));
    assertEquals("42", runningBuild.getBuildNumber());

    TeamCityBuildLogger logger = build.logger();
    logger.addBuildLog(promotionId, "first Jenkins line\nsecond Jenkins line");
    build.flush();
    assertTrue(runningBuild.getBuildLog().getMessages().stream()
        .anyMatch(message -> "first Jenkins line".equals(message.getText())));
    assertTrue(runningBuild.getBuildLog().getMessages().stream()
        .anyMatch(message -> "second Jenkins line".equals(message.getText())));

    Date finishTime = new Date();
    TeamCityBuildFinisher finisher = new TeamCityBuildFinisher(build.getLocator(), build.getMessagesQueue());
    finisher.finishBuild(promotionId, finishTime, "SUCCESS");

    // TeamCity finalizes builds on its message-processing thread. Wait for that work and then
    // reload the build instead of asserting against the promotion's potentially stale instance.
    myFixture.waitForBuildFinished(runningBuild.getBuildId());
    SBuild finishedBuild = myFixture.getBuildsManager()
        .findBuildInstanceById(runningBuild.getBuildId());
    assertNotNull(finishedBuild);
    assertTrue(finishedBuild.isFinished());
    assertEquals(Status.NORMAL, finishedBuild.getBuildStatus());
    assertEquals(finishTime, finishedBuild.getFinishDate());

    // Poll retries may attempt to finish the same mirror again. Once TeamCity finalized it, this
    // must remain a harmless no-op.
    finisher.finishBuild(promotionId, new Date(finishTime.getTime() + 1_000), "FAILURE");
    SBuild stillFinished = myFixture.getBuildsManager().findBuildInstanceById(runningBuild.getBuildId());
    assertEquals(Status.NORMAL, stillFinished.getBuildStatus());
  }
}
