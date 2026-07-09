package com.jetbrains.teamcity.jenkinsbridge.model;

import com.google.gson.JsonObject;
import org.jetbrains.annotations.NotNull;

import static com.jetbrains.teamcity.jenkinsbridge.util.Utilities.stringValue;

/**
 * @param size in bytes
 */
public record JenkinsArtifact(@NotNull String fileName, @NotNull String relativePath, long size) {
  public JenkinsArtifact(String fileName, String relativePath) {
    this(fileName == null ? "" : fileName, relativePath == null ? "" : relativePath, 0L);
  }

  public JenkinsArtifact(String fileName, String relativePath, int size) {
    this(fileName == null ? "" : fileName, relativePath == null ? "" : relativePath, size < 0 ? 0L : size);
  }

  public static JenkinsArtifact fromJson(JsonObject json) {
    return new JenkinsArtifact(stringValue(json, "fileName"), stringValue(json, "relativePath"));
  }

}
