package com.jetbrains.teamcity.jenkinsbridge.teamcity;

/**
 * Indicates that the optional native TeamCity Pipeline chain could not be constructed.
 */
public class TeamCityPipelineChainException extends Exception {
  public TeamCityPipelineChainException(String message) {
    super(message);
  }

  public TeamCityPipelineChainException(String message, Throwable cause) {
    super(message, cause);
  }
}
