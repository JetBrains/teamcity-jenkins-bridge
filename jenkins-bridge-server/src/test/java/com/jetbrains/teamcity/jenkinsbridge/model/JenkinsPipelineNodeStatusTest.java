package com.jetbrains.teamcity.jenkinsbridge.model;

import org.junit.Test;

import static com.jetbrains.teamcity.jenkinsbridge.model.JenkinsPipelineNodeStatus.ABORTED;
import static com.jetbrains.teamcity.jenkinsbridge.model.JenkinsPipelineNodeStatus.FAILURE;
import static com.jetbrains.teamcity.jenkinsbridge.model.JenkinsPipelineNodeStatus.IN_PROGRESS;
import static com.jetbrains.teamcity.jenkinsbridge.model.JenkinsPipelineNodeStatus.NOT_EXECUTED;
import static com.jetbrains.teamcity.jenkinsbridge.model.JenkinsPipelineNodeStatus.PAUSED_PENDING_INPUT;
import static com.jetbrains.teamcity.jenkinsbridge.model.JenkinsPipelineNodeStatus.QUEUED;
import static com.jetbrains.teamcity.jenkinsbridge.model.JenkinsPipelineNodeStatus.SUCCESS;
import static com.jetbrains.teamcity.jenkinsbridge.model.JenkinsPipelineNodeStatus.UNKNOWN;
import static com.jetbrains.teamcity.jenkinsbridge.model.JenkinsPipelineNodeStatus.UNSTABLE;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class JenkinsPipelineNodeStatusTest {

  @Test
  public void fromNormalizesWfapiAndGenericStrings() {
    assertEquals(SUCCESS, JenkinsPipelineNodeStatus.from("SUCCESS"));
    assertEquals(SUCCESS, JenkinsPipelineNodeStatus.from("success")); // case-insensitive
    assertEquals(FAILURE, JenkinsPipelineNodeStatus.from("FAILED"));
    assertEquals(FAILURE, JenkinsPipelineNodeStatus.from("FAILURE"));
    assertEquals(NOT_EXECUTED, JenkinsPipelineNodeStatus.from("NOT_EXECUTED"));
    assertEquals(NOT_EXECUTED, JenkinsPipelineNodeStatus.from("NOT_BUILT"));
    assertEquals(NOT_EXECUTED, JenkinsPipelineNodeStatus.from("SKIPPED"));
    assertEquals(PAUSED_PENDING_INPUT, JenkinsPipelineNodeStatus.from("PAUSED"));
    assertEquals(PAUSED_PENDING_INPUT, JenkinsPipelineNodeStatus.from("PAUSED_PENDING_INPUT"));
    assertEquals(IN_PROGRESS, JenkinsPipelineNodeStatus.from("RUNNING"));
    assertEquals(QUEUED, JenkinsPipelineNodeStatus.from("QUEUED"));
    assertEquals(UNSTABLE, JenkinsPipelineNodeStatus.from("UNSTABLE"));
    assertEquals(ABORTED, JenkinsPipelineNodeStatus.from("ABORTED"));
  }

  @Test
  public void fromMapsBlankNullAndGarbageToUnknown() {
    assertEquals(UNKNOWN, JenkinsPipelineNodeStatus.from(""));
    assertEquals(UNKNOWN, JenkinsPipelineNodeStatus.from("   "));
    assertEquals(UNKNOWN, JenkinsPipelineNodeStatus.from(null));
    assertEquals(UNKNOWN, JenkinsPipelineNodeStatus.from("something-else"));
  }

  @Test
  public void fromBlueOceanPrefersResultThenState() {
    // A concrete result wins over state.
    assertEquals(SUCCESS, JenkinsPipelineNodeStatus.fromBlueOcean("SUCCESS", "FINISHED"));
    assertEquals(FAILURE, JenkinsPipelineNodeStatus.fromBlueOcean("FAILED", "FINISHED"));
    assertEquals(NOT_EXECUTED, JenkinsPipelineNodeStatus.fromBlueOcean("NOT_BUILT", "FINISHED"));
    // No usable result -> map the lifecycle state.
    assertEquals(IN_PROGRESS, JenkinsPipelineNodeStatus.fromBlueOcean("", "RUNNING"));
    assertEquals(QUEUED, JenkinsPipelineNodeStatus.fromBlueOcean("UNKNOWN", "QUEUED"));
    assertEquals(PAUSED_PENDING_INPUT, JenkinsPipelineNodeStatus.fromBlueOcean(null, "PAUSED"));
    // The bug fix: SKIPPED must not become blank (which left generated builds stuck queued).
    assertEquals(NOT_EXECUTED, JenkinsPipelineNodeStatus.fromBlueOcean(null, "SKIPPED"));
    // Nothing recognizable -> UNKNOWN, never blank.
    assertEquals(UNKNOWN, JenkinsPipelineNodeStatus.fromBlueOcean("", "WEIRD"));
    assertEquals(UNKNOWN, JenkinsPipelineNodeStatus.fromBlueOcean(null, null));
  }

  @Test
  public void isActiveCoversQueuedRunningPaused() {
    assertTrue(QUEUED.isActive());
    assertTrue(IN_PROGRESS.isActive());
    assertTrue(PAUSED_PENDING_INPUT.isActive());
    assertFalse(SUCCESS.isActive());
    assertFalse(NOT_EXECUTED.isActive());
    assertFalse(UNKNOWN.isActive());
  }

  @Test
  public void isStartedFalseForQueuedAndUnknownOnly() {
    assertFalse(QUEUED.isStarted());
    assertFalse(UNKNOWN.isStarted());
    assertTrue(IN_PROGRESS.isStarted());
    assertTrue(PAUSED_PENDING_INPUT.isStarted());
    assertTrue(SUCCESS.isStarted());
    assertTrue(NOT_EXECUTED.isStarted());
    assertTrue(FAILURE.isStarted());
  }

  @Test
  public void isTerminalExcludesUnknownAndActive() {
    assertTrue(SUCCESS.isTerminal());
    assertTrue(UNSTABLE.isTerminal());
    assertTrue(FAILURE.isTerminal());
    assertTrue(ABORTED.isTerminal());
    assertTrue(NOT_EXECUTED.isTerminal());
    assertFalse(QUEUED.isTerminal());
    assertFalse(IN_PROGRESS.isTerminal());
    assertFalse(PAUSED_PENDING_INPUT.isTerminal());
    assertFalse(UNKNOWN.isTerminal());
  }

  @Test
  public void toTeamCityResultMapsSkipToGreen() {
    assertEquals("SUCCESS", SUCCESS.toTeamCityResult());
    assertEquals("SUCCESS", NOT_EXECUTED.toTeamCityResult()); // skipped -> green, not red
    assertEquals("UNSTABLE", UNSTABLE.toTeamCityResult());
    assertEquals("FAILURE", FAILURE.toTeamCityResult());
    assertEquals("ABORTED", ABORTED.toTeamCityResult());
    assertEquals("UNKNOWN", UNKNOWN.toTeamCityResult());
    assertEquals("UNKNOWN", QUEUED.toTeamCityResult()); // non-terminal has no terminal result
  }

  @Test
  public void isSkippedOnlyForNotExecuted() {
    assertTrue(NOT_EXECUTED.isSkipped());
    assertFalse(SUCCESS.isSkipped());
    assertFalse(UNKNOWN.isSkipped());
  }
}
