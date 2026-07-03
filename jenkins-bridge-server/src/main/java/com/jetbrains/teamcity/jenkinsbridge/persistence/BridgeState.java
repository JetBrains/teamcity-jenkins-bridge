package com.jetbrains.teamcity.jenkinsbridge.persistence;

import java.util.LinkedHashMap;
import java.util.Map;

public class BridgeState implements Cloneable {
  private int version = 2;
  private Map<String, BuildMirror> builds = new LinkedHashMap<String, BuildMirror>();
  // Highest Jenkins build number already discovered per job, used as an incremental-polling watermark.
  private Map<String, Integer> lastSeenBuildNumbers = new LinkedHashMap<String, Integer>();
  private String lastPollTime;
  private String lastError;

  public BridgeState() {
  }

  private BridgeState(int version, Map<String, BuildMirror> builds, Map<String, Integer> lastSeenBuildNumbers, String lastPollTime, String lastError) {
    this.version = version;
    this.builds = builds;
    this.lastSeenBuildNumbers = lastSeenBuildNumbers;
    this.lastPollTime = lastPollTime;
    this.lastError = lastError;
  }

  public int getVersion() {
    return version;
  }

  public void setVersion(int version) {
    this.version = version;
  }

  public Map<String, BuildMirror> getBuilds() {
    if (builds == null) {
      builds = new LinkedHashMap<String, BuildMirror>();
    }
    return builds;
  }

  public Map<String, Integer> getLastSeenBuildNumbers() {
    if (lastSeenBuildNumbers == null) {
      lastSeenBuildNumbers = new LinkedHashMap<String, Integer>();
    }
    return lastSeenBuildNumbers;
  }

  public String getLastPollTime() {
    return lastPollTime;
  }

  public void setLastPollTime(String lastPollTime) {
    this.lastPollTime = lastPollTime;
  }

  public String getLastError() {
    return lastError;
  }

  public void setLastError(String lastError) {
    this.lastError = lastError;
  }

  @Override
  public Object clone() throws CloneNotSupportedException {
    super.clone();
    return new BridgeState(version, new LinkedHashMap<>(builds), new LinkedHashMap<>(lastSeenBuildNumbers), lastPollTime, lastError);
  }
}
