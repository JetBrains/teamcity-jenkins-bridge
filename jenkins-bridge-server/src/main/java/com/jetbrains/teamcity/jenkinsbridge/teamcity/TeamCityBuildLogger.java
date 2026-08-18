package com.jetbrains.teamcity.jenkinsbridge.teamcity;

import jetbrains.buildServer.messages.BuildMessage1;
import jetbrains.buildServer.messages.DefaultMessagesInfo;
import jetbrains.buildServer.serverSide.RunningBuildEx;
import jetbrains.buildServer.serverSide.impl.BuildAgentMessagesQueue;

import java.util.ArrayList;
import java.util.List;

public class TeamCityBuildLogger {
  private final TeamCityRunningBuildLocator buildLocator;
  private final BuildAgentMessagesQueue buildAgentMessagesQueue;

  public TeamCityBuildLogger(
      TeamCityRunningBuildLocator buildLocator,
      BuildAgentMessagesQueue buildAgentMessagesQueue
  ) {
    this.buildLocator = buildLocator;
    this.buildAgentMessagesQueue = buildAgentMessagesQueue;
  }

  public void addBuildLog(long buildId, String text) throws TeamCityRunningBuildNotFoundException {
    if (text == null || text.length() == 0) {
      return;
    }

    RunningBuildEx runningBuild = buildLocator.requireRunningBuild(buildId);

    try {
      // Split by lines to avoid giant messages (P6 in RELIABILITY_AND_PERFORMANCE.md)
      // This also ensures that very large deltas don't exceed TeamCity's message size limits or cause heap pressure.
      String[] lines = text.split("(\r\n|\n|\r)", -1);
      List<BuildMessage1> messages = new ArrayList<>(lines.length);
      for (String line : lines) {
        messages.add(DefaultMessagesInfo.createTextMessage(line).updateTags(DefaultMessagesInfo.TAG_SERVER));
      }

      buildAgentMessagesQueue.processMessages(runningBuild, messages);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted while logging TeamCity build message", e);
    } catch (BuildAgentMessagesQueue.BuildMessagesQueueFullException e) {
      throw new IllegalStateException("TeamCity build messages queue is full", e);
    }
  }
}
