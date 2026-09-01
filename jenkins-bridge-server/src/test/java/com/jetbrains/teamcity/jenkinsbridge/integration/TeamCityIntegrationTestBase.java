package com.jetbrains.teamcity.jenkinsbridge.integration;

import com.jetbrains.teamcity.jenkinsbridge.teamcity.TeamCityBuildFinisher;
import com.jetbrains.teamcity.jenkinsbridge.teamcity.TeamCityBuildLogger;
import com.jetbrains.teamcity.jenkinsbridge.teamcity.TeamCityBuildParameters;
import com.jetbrains.teamcity.jenkinsbridge.teamcity.TeamCityBuildQueuer;
import com.jetbrains.teamcity.jenkinsbridge.teamcity.TeamCityBuildStarter;
import com.jetbrains.teamcity.jenkinsbridge.teamcity.TeamCityRunningBuildLocator;
import jetbrains.buildServer.serverSide.BuildCustomizerFactory;
import jetbrains.buildServer.serverSide.BuildPromotion;
import jetbrains.buildServer.serverSide.RunningBuildEx;
import jetbrains.buildServer.serverSide.SBuild;
import jetbrains.buildServer.serverSide.impl.BaseServerTestCase;
import jetbrains.buildServer.serverSide.impl.BuildAgentMessagesQueue;
import jetbrains.buildServer.serverSide.parameters.ParameterFactory;

import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;

/** Shared real-TeamCity fixture plumbing for server integration tests. */
public abstract class TeamCityIntegrationTestBase extends BaseServerTestCase {
  protected TeamCityBuildFixture queueAndStartBuild(
      Map<String, String> bridgeParameters,
      Map<String, String> jenkinsParameters
  ) throws Exception {
    ParameterFactory parameterFactory = myFixture.getSingletonService(ParameterFactory.class);
    myBuildType.addConfigParameter(parameterFactory.createSimpleParameter(
        TeamCityBuildParameters.AGENTLESS_BUILD_PROPERTY, "true"));
    myBuildType.schedulePersisting("Jenkins Bridge test: configure agentless build")
        .awaitUninterruptibly();

    TeamCityRunningBuildLocator locator = new TeamCityRunningBuildLocator(
        myFixture.getBuildsManager(), myFixture.getBuildPromotionManager(), myProjectManager);
    TeamCityBuildQueuer queuer = new TeamCityBuildQueuer(
        myProjectManager,
        myFixture.getSingletonService(BuildCustomizerFactory.class),
        null,
        null);
    long promotionId = queuer.queueAgentlessBuild(
        myBuildType.getExternalId(),
        new LinkedHashMap<>(bridgeParameters),
        new LinkedHashMap<>(jenkinsParameters),
        null);

    BuildPromotion promotion = myFixture.getBuildPromotionManager().findPromotionById(promotionId);
    assertNotNull(promotion);
    new TeamCityBuildStarter(myFixture.getBuildsManager(), locator)
        .markBuildAsRunning(promotionId, "Jenkins Bridge integration test");

    RunningBuildEx runningBuild = locator.findRunningBuild(promotionId);
    assertNotNull(runningBuild);
    return new TeamCityBuildFixture(promotionId, runningBuild, locator,
        myFixture.getBuildAgentMessagesQueue());
  }

  protected final class TeamCityBuildFixture {
    private final long promotionId;
    private final RunningBuildEx runningBuild;
    private final TeamCityRunningBuildLocator locator;
    private final BuildAgentMessagesQueue messagesQueue;

    private TeamCityBuildFixture(long promotionId, RunningBuildEx runningBuild,
        TeamCityRunningBuildLocator locator, BuildAgentMessagesQueue messagesQueue) {
      this.promotionId = promotionId;
      this.runningBuild = runningBuild;
      this.locator = locator;
      this.messagesQueue = messagesQueue;
    }

    public long getPromotionId() {
      return promotionId;
    }

    public long getBuildId() {
      return runningBuild.getBuildId();
    }

    public RunningBuildEx getRunningBuild() {
      return runningBuild;
    }

    public TeamCityRunningBuildLocator getLocator() {
      return locator;
    }

    public BuildAgentMessagesQueue getMessagesQueue() {
      return messagesQueue;
    }

    public TeamCityBuildLogger logger() {
      return new TeamCityBuildLogger(locator, messagesQueue);
    }

    public void flush() throws Exception {
      assertTrue(messagesQueue.waitUntilBuildMessagesProcessedAndFlushedOnDisk(runningBuild, 10_000));
    }

    public void finish(String jenkinsResult, Date finishTime) throws Exception {
      new TeamCityBuildFinisher(locator, messagesQueue)
          .finishBuild(promotionId, finishTime, jenkinsResult);
      myFixture.waitForBuildFinished(getBuildId());
    }

    public SBuild reload() {
      return myFixture.getBuildsManager().findBuildInstanceById(getBuildId());
    }
  }
}
