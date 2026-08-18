package com.jetbrains.teamcity.jenkinsbridge.teamcity;

import jetbrains.buildServer.messages.BlockData;
import jetbrains.buildServer.messages.BuildMessage1;
import jetbrains.buildServer.messages.DefaultMessagesInfo;
import jetbrains.buildServer.serverSide.RunningBuildEx;
import org.junit.Test;

import java.util.Date;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class TeamCityStageReporterTest {
  private final TeamCityStageReporter reporter = new TeamCityStageReporter(null, null);

  @Test
  public void openAppendCloseProducesBuildStepBlockAroundLines() {
    List<BuildMessage1> messages = reporter.messagesForStage(
        "Build", new Date(1000L), new Date(1500L), true, "+ mvn compile\nBUILD SUCCESS", true);

    assertEquals(4, messages.size());

    assertEquals(DefaultMessagesInfo.MSG_BLOCK_START, messages.get(0).getTypeId());
    BlockData start = (BlockData) messages.get(0).getValue();
    assertEquals("Build", start.blockName);
    assertEquals(DefaultMessagesInfo.BLOCK_TYPE_BUILD_STEP, start.blockType);

    assertEquals("+ mvn compile", textOf(messages.get(1)));
    assertEquals("BUILD SUCCESS", textOf(messages.get(2)));

    assertEquals(DefaultMessagesInfo.MSG_BLOCK_END, messages.get(3).getTypeId());
    BlockData end = (BlockData) messages.get(3).getValue();
    assertEquals("Build", end.blockName);
    assertEquals(DefaultMessagesInfo.BLOCK_TYPE_BUILD_STEP, end.blockType);

    for (BuildMessage1 message : messages) {
      assertTrue(message.hasTag(DefaultMessagesInfo.TAG_SERVER));
    }
  }

  @Test
  public void appendOnlyEmitsNoBlockMessages() {
    List<BuildMessage1> messages = reporter.messagesForStage(
        "Test", new Date(2000L), new Date(2000L), false, "still running", false);

    assertEquals(1, messages.size());
    assertFalse(DefaultMessagesInfo.MSG_BLOCK_START.equals(messages.get(0).getTypeId()));
    assertFalse(DefaultMessagesInfo.MSG_BLOCK_END.equals(messages.get(0).getTypeId()));
    assertEquals("still running", textOf(messages.get(0)));
  }

  @Test
  public void nullDatesStillProduceWellFormedBlock() {
    List<BuildMessage1> messages = reporter.messagesForStage(
        "Deploy", null, null, true, "(stage skipped)", true);

    assertEquals(3, messages.size());
    assertEquals(DefaultMessagesInfo.MSG_BLOCK_START, messages.get(0).getTypeId());
    assertEquals(DefaultMessagesInfo.MSG_BLOCK_END, messages.get(2).getTypeId());
  }

  @Test
  public void emptyAppendEmitsNoTextMessage() {
    List<BuildMessage1> messages = reporter.messagesForStage(
        "Build", new Date(1000L), new Date(1500L), true, "", true);

    assertEquals(2, messages.size());
    assertEquals(DefaultMessagesInfo.MSG_BLOCK_START, messages.get(0).getTypeId());
    assertEquals(DefaultMessagesInfo.MSG_BLOCK_END, messages.get(1).getTypeId());
  }

  @Test(expected = TeamCityRunningBuildNotFoundException.class)
  public void reportFailsWhenTeamCityBuildIsNotRunning() {
    TeamCityRunningBuildLocator locator = new TeamCityRunningBuildLocator(null, null, null) {
      @Override
      public RunningBuildEx findRunningBuild(long id) {
        return null;
      }
    };
    TeamCityStageReporter missingBuildReporter = new TeamCityStageReporter(locator, null);

    missingBuildReporter.report(17L, reporter.messagesForStage("Build", null, null, true, "log", false));
  }

  private static String textOf(BuildMessage1 message) {
    Object value = message.getValue();
    return value == null ? null : value.toString();
  }
}
