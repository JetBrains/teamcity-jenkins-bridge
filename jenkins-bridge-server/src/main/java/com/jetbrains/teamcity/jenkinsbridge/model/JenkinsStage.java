package com.jetbrains.teamcity.jenkinsbridge.model;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * One Jenkins Pipeline stage from {@code wfapi/describe}. A stage maps to a TeamCity build-step
 * block in the mirrored log.
 */
public class JenkinsStage {
  // Status classification (active/terminal/skipped) lives in JenkinsPipelineNodeStatus so the rules
  // cannot drift between the log-block path and the chain path.

  private String id;
  private String name;
  private String status;
  private long startTimeMillis;
  private long durationMillis;

  public static JenkinsStage fromJson(JsonObject json) {
    JenkinsStage stage = new JenkinsStage();
    stage.id = getString(json, "id", "");
    stage.name = getString(json, "name", "");
    stage.status = getString(json, "status", "");
    stage.startTimeMillis = getLong(json, "startTimeMillis");
    stage.durationMillis = getLong(json, "durationMillis");
    return stage;
  }

  public String getId() {
    return id == null ? "" : id;
  }

  public String getName() {
    return name == null || name.trim().length() == 0 ? "Stage " + getId() : name;
  }

  public String getStatus() {
    return status == null ? "" : status;
  }

  public long getStartTimeMillis() {
    return startTimeMillis;
  }

  public long getDurationMillis() {
    return durationMillis;
  }

  /** A stage Jenkins skipped (no node, no log); terminal but emitted as an empty block. */
  public boolean isSkipped() {
    return JenkinsPipelineNodeStatus.from(getStatus()).isSkipped();
  }

  /**
   * True once the stage can no longer change: its block can be closed and never reopened. Terminal for
   * log-block purposes means "no longer active" (not queued/running/paused), so a blank or unknown
   * status still counts as terminal and closes the block.
   */
  public boolean isTerminal() {
    return !JenkinsPipelineNodeStatus.from(getStatus()).isActive();
  }

  /** True before the stage has actually started running (queued / not yet scheduled). */
  public boolean isNotStarted() {
    return startTimeMillis <= 0 && !isSkipped();
  }

  private static String getString(JsonObject json, String name, String defaultValue) {
    JsonElement element = json.get(name);
    if (element == null || element.isJsonNull()) {
      return defaultValue;
    }
    return element.getAsString();
  }

  private static long getLong(JsonObject json, String name) {
    JsonElement element = json.get(name);
    if (element == null || element.isJsonNull()) {
      return 0L;
    }
    try {
      return element.getAsLong();
    } catch (NumberFormatException e) {
      return 0L;
    }
  }
}
