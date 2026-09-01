package com.jetbrains.teamcity.jenkinsbridge.teamcity;

import jetbrains.buildServer.messages.BuildMessage1;
import jetbrains.buildServer.messages.DefaultMessagesInfo;
import jetbrains.buildServer.serverSide.RunningBuildEx;
import jetbrains.buildServer.serverSide.impl.BuildAgentMessagesQueue;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * Emits explicit stage updates into a TeamCity build as build-step blocks. The polling service no
 * longer mirrors Pipeline stage logs into the main build log; this low-level reporter remains
 * available to direct integrations and tests. It turns stage decisions into {@link BuildMessage1}s
 * and submits them, mirroring
 * {@link TeamCityTestReporter}.
 */
public class TeamCityStageReporter {
  private final TeamCityRunningBuildLocator buildLocator;
  private final BuildAgentMessagesQueue buildAgentMessagesQueue;

  public TeamCityStageReporter(
      TeamCityRunningBuildLocator buildLocator,
      BuildAgentMessagesQueue buildAgentMessagesQueue
  ) {
    this.buildLocator = buildLocator;
    this.buildAgentMessagesQueue = buildAgentMessagesQueue;
  }

  /**
   * Builds the messages for one stage's update this poll: an optional block-start, the new console
   * lines since the last poll, and an optional block-end. {@code start}/{@code end} may be null
   * (the message then carries the current time) — used for skipped stages with no real timing.
   */
  public List<BuildMessage1> messagesForStage(
      String name,
      Date start,
      Date end,
      boolean open,
      String appendText,
      boolean close
  ) {
    List<BuildMessage1> messages = new ArrayList<BuildMessage1>();

    if (open) {
      messages.add(serverMessage(start != null
          ? DefaultMessagesInfo.createBlockStart(name, DefaultMessagesInfo.BLOCK_TYPE_BUILD_STEP, start)
          : DefaultMessagesInfo.createBlockStart(name, DefaultMessagesInfo.BLOCK_TYPE_BUILD_STEP)));
    }

    if (appendText != null && appendText.length() > 0) {
      // Split by lines to avoid giant messages, same as TeamCityBuildLogger.
      String[] lines = appendText.split("(\r\n|\n|\r)", -1);
      for (String line : lines) {
        messages.add(serverMessage(DefaultMessagesInfo.createTextMessage(line)));
      }
    }

    if (close) {
      messages.add(serverMessage(end != null
          ? DefaultMessagesInfo.createBlockEnd(name, DefaultMessagesInfo.BLOCK_TYPE_BUILD_STEP, end)
          : DefaultMessagesInfo.createBlockEnd(name, DefaultMessagesInfo.BLOCK_TYPE_BUILD_STEP)));
    }

    return messages;
  }

  /**
   * A progress message sets the running build's short status text (TeamCity {@code MSG_PROGRESS_STAGE}).
   * Used to surface the current Jenkins stage ("Jenkins stage N/M: name") so a long build's position is
   * visible at a glance without reading the log.
   */
  public BuildMessage1 progressMessage(String text) {
    return serverMessage(DefaultMessagesInfo.createProgressMessage(text));
  }

  public void report(long buildId, List<BuildMessage1> messages)
      throws TeamCityRunningBuildNotFoundException {
    if (messages == null || messages.isEmpty()) {
      return;
    }

    RunningBuildEx runningBuild = buildLocator.requireRunningBuild(buildId);

    try {
      buildAgentMessagesQueue.processMessages(runningBuild, messages);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted while reporting Jenkins stages to TeamCity", e);
    } catch (BuildAgentMessagesQueue.BuildMessagesQueueFullException e) {
      throw new IllegalStateException("TeamCity build messages queue is full", e);
    }
  }

  private BuildMessage1 serverMessage(BuildMessage1 message) {
    return message.updateTags(DefaultMessagesInfo.TAG_SERVER);
  }
}
