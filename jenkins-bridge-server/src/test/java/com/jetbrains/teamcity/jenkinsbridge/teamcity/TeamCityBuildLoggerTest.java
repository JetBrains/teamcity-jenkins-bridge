package com.jetbrains.teamcity.jenkinsbridge.teamcity;

import jetbrains.buildServer.serverSide.RunningBuildEx;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class TeamCityBuildLoggerTest {
  @Test
  public void addBridgeLogMarksEachLineWithoutChangingJenkinsConsoleLogging() throws Exception {
    CapturingLogger logger = new CapturingLogger();

    logger.addBridgeLog(17L, "first\nsecond");

    assertEquals("[Jenkins Bridge] first\n[Jenkins Bridge] second", logger.text);
  }

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

  private static class CapturingLogger extends TeamCityBuildLogger {
    private String text;

    private CapturingLogger() {
      super(null, null);
    }

    @Override
    public void addBuildLog(long buildId, String text) {
      this.text = text;
    }
  }
}
