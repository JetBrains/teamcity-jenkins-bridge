package com.jetbrains.teamcity.jenkinsbridge.model;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class JenkinsBuildInfo {
  public static final long UNKNOWN_QUEUE_ID = -1L;
  private int number;
  // queueId is not a uinqure identifier for the specific build, It is used in TC first runs to avoid duplicate builds
  private long queueId = UNKNOWN_QUEUE_ID;
  @NotNull private String url = "";
  private boolean building;
  @Nullable private String result;
  private long timestamp;
  private long duration;
  private long estimatedDuration;

  @NotNull
  public static JenkinsBuildInfo fromJson(@NotNull JsonObject json) {
    JenkinsBuildInfo info = new JenkinsBuildInfo();
    info.number = getInt(json, "number", 0);
    info.queueId = getLong(json, "queueId", UNKNOWN_QUEUE_ID);
    info.url = getString(json, "url", "");
    info.building = getBoolean(json, "building", false);
    info.result = getNullableString(json, "result");
    info.timestamp = getLong(json, "timestamp", 0L);
    info.duration = getLong(json, "duration", 0L);
    info.estimatedDuration = getLong(json, "estimatedDuration", 0L);
    return info;
  }

  public int getNumber() {
    return number;
  }

  public long getQueueId() {
    return queueId;
  }

  @NotNull
  public String getUrl() {
    return url;
  }

  public boolean isBuilding() {
    return building;
  }

  /** Jenkins omits the result while a build is still running. */
  @Nullable
  public String getResult() {
    return result;
  }

  public long getTimestamp() {
    return timestamp;
  }

  public long getDuration() {
    return duration;
  }

  public long getEstimatedDuration() {
    return estimatedDuration;
  }

  @NotNull
  private static String getString(
      @NotNull JsonObject json,
      @NotNull String name,
      @NotNull String defaultValue
  ) {
    JsonElement element = json.get(name);
    if (element == null || element.isJsonNull()) {
      return defaultValue;
    }
    return element.getAsString();
  }

  @Nullable
  private static String getNullableString(@NotNull JsonObject json, @NotNull String name) {
    JsonElement element = json.get(name);
    if (element == null || element.isJsonNull()) {
      return null;
    }
    return element.getAsString();
  }

  private static boolean getBoolean(@NotNull JsonObject json, @NotNull String name, boolean defaultValue) {
    JsonElement element = json.get(name);
    if (element == null || element.isJsonNull()) {
      return defaultValue;
    }
    return element.getAsBoolean();
  }

  private static int getInt(@NotNull JsonObject json, @NotNull String name, int defaultValue) {
    JsonElement element = json.get(name);
    if (element == null || element.isJsonNull()) {
      return defaultValue;
    }
    return element.getAsInt();
  }

  private static long getLong(@NotNull JsonObject json, @NotNull String name, long defaultValue) {
    JsonElement element = json.get(name);
    if (element == null || element.isJsonNull()) {
      return defaultValue;
    }
    return element.getAsLong();
  }
}
