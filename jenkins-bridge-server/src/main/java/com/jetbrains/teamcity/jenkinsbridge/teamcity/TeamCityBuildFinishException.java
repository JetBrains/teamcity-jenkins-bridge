package com.jetbrains.teamcity.jenkinsbridge.teamcity;

/**
 * Indicates that the bridge could not finish a TeamCity build for a Jenkins mirror.
 */
public class TeamCityBuildFinishException extends IllegalStateException {
  public TeamCityBuildFinishException(String message, Throwable cause) {
    super(message, cause);
  }
}
