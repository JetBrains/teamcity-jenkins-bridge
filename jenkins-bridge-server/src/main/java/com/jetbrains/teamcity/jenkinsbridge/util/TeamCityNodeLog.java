package com.jetbrains.teamcity.jenkinsbridge.util;

import jetbrains.buildServer.serverSide.TeamCityNode;
import jetbrains.buildServer.serverSide.TeamCityNodes;

/** Formats the current TeamCity node identity for operational log messages. */
public final class TeamCityNodeLog {
  private TeamCityNodeLog() {
  }

  public static String currentNode(TeamCityNodes nodes) {
    if (nodes == null) {
      return "[nodeId=unknown, role=unknown]";
    }
    TeamCityNode node = nodes.getCurrentNode();
    if (node == null) {
      return "[nodeId=unknown, role=unknown]";
    }
    return "[nodeId=" + node.getId() + ", role=" + (node.isMainNode() ? "main" : "secondary") + "]";
  }
}
