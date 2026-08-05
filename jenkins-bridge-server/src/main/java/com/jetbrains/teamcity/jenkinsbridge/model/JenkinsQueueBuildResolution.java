package com.jetbrains.teamcity.jenkinsbridge.model;

public class JenkinsQueueBuildResolution {
  public enum State {
    PENDING,
    RESOLVED,
    CANCELLED
  }

  private final State state;
  private final int buildNumber;

  private JenkinsQueueBuildResolution(State state, int buildNumber) {
    this.state = state;
    this.buildNumber = buildNumber;
  }

  public static JenkinsQueueBuildResolution pending() {
    return new JenkinsQueueBuildResolution(State.PENDING, 0);
  }

  public static JenkinsQueueBuildResolution resolved(int buildNumber) {
    return new JenkinsQueueBuildResolution(State.RESOLVED, buildNumber);
  }

  public static JenkinsQueueBuildResolution cancelled() {
    return new JenkinsQueueBuildResolution(State.CANCELLED, 0);
  }

  public State getState() {
    return state;
  }

  public int getBuildNumber() {
    return buildNumber;
  }

  public boolean isPending() {
    return state == State.PENDING;
  }

  public boolean isResolved() {
    return state == State.RESOLVED;
  }

  public boolean isCancelled() {
    return state == State.CANCELLED;
  }
}
