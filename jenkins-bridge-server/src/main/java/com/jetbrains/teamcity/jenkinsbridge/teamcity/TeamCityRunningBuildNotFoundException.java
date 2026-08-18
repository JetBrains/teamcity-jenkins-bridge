package com.jetbrains.teamcity.jenkinsbridge.teamcity;

/**
 * Indicates that the TeamCity build expected to receive bridge data is not currently running.
 */
public class TeamCityRunningBuildNotFoundException extends IllegalStateException {
  public TeamCityRunningBuildNotFoundException(String message) {
    super(message);
  }

  public TeamCityRunningBuildNotFoundException(String message, Throwable cause) {
    super(message, cause);
  }
}
