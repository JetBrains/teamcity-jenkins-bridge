package com.jetbrains.teamcity.jenkinsbridge.model;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class JenkinsArtifacts {
  private final List<JenkinsArtifact> artifacts;

  public JenkinsArtifacts(List<JenkinsArtifact> artifacts) {
    this.artifacts = artifacts;
  }

  public static JenkinsArtifacts empty() {
    return new JenkinsArtifacts(Collections.<JenkinsArtifact>emptyList());
  }

  public static JenkinsArtifacts fromJson(JsonObject json) {
    if (json == null) {
      return empty();
    }

    JsonArray array = json.getAsJsonArray("artifacts");
    if (array == null) {
      return empty();
    }

    List<JenkinsArtifact> result = new ArrayList<JenkinsArtifact>();
    for (JsonElement element : array) {
      if (element != null && element.isJsonObject()) {
        result.add(JenkinsArtifact.fromJson(element.getAsJsonObject()));
      }
    }
    return new JenkinsArtifacts(result);
  }

  public List<JenkinsArtifact> getArtifacts() {
    return Collections.unmodifiableList(artifacts);
  }

  public boolean isEmpty() {
    return artifacts.isEmpty();
  }

  public int size() {
    return artifacts.size();
  }
}
