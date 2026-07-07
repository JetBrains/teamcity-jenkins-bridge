package com.jetbrains.teamcity.jenkinsbridge.model;

import com.google.gson.JsonObject;
import org.jetbrains.annotations.NotNull;

public class JenkinsArtifact {
  @NotNull
  private final String fileName;

  @NotNull
  private final String relativePath;

  private final long size; // in bytes

  public JenkinsArtifact(String fileName, String relativePath) {
    this.fileName = fileName == null ? "" : fileName;
    this.relativePath = relativePath == null ? "" : relativePath;
    this.size = 0L;
  }

  public JenkinsArtifact(String fileName, String relativePath, int size) {
    this.fileName = fileName == null ? "" : fileName;
    this.relativePath = relativePath == null ? "" : relativePath;
    this.size = size < 0 ? 0L : size;
  }

  public static JenkinsArtifact fromJson(JsonObject json) {
    return new JenkinsArtifact(stringValue(json, "fileName"), stringValue(json, "relativePath"));
  }

  @NotNull
  public String getFileName() {
    return fileName;
  }

  @NotNull
  public String getRelativePath() {
    return relativePath;
  }

  public long getSize() {
    return size;
  }

  private static String stringValue(JsonObject object, String key) {
    if (object == null || object.get(key) == null || object.get(key).isJsonNull()) {
      return "";
    }
    return object.get(key).getAsString();
  }
}
