package com.jetbrains.teamcity.jenkinsbridge.teamcity;

/**
 * Indicates that the bridge could not create or queue the TeamCity mirror build.
 */
public class TeamCityBuildQueueException extends Exception {
  public TeamCityBuildQueueException(String message) {
    super(message);
  }
}
