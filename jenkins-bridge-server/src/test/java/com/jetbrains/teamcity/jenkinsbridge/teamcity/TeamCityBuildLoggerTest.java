package com.jetbrains.teamcity.jenkinsbridge.teamcity;

import jetbrains.buildServer.serverSide.RunningBuildEx;
import org.junit.Test;

public class TeamCityBuildLoggerTest {
  @Test(expected = TeamCityRunningBuildNotFoundException.class)
  public void addBuildLogFailsWhenTeamCityBuildIsNotRunning() {
    TeamCityRunningBuildLocator locator = new TeamCityRunningBuildLocator(null, null, null) {
      @Override
      public RunningBuildEx findRunningBuild(long id) {
        return null;
      }
    };
    TeamCityBuildLogger logger = new TeamCityBuildLogger(locator, null);

    logger.addBuildLog(17L, "log");
  }
}
