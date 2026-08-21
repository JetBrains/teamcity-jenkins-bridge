package com.jetbrains.teamcity.jenkinsbridge.util;

import jetbrains.buildServer.serverSide.SProject;

/** TeamCity UI-only settings that also need to protect plugin mutation endpoints. */
public final class TeamCityUiSettings {
  private TeamCityUiSettings() {
  }

  public static boolean isReadOnly(SProject project) {
    return project != null && project.isReadOnly();
  }
}
