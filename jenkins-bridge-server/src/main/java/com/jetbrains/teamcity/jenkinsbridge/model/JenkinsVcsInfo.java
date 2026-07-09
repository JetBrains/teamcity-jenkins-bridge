package com.jetbrains.teamcity.jenkinsbridge.model;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.jetbrains.teamcity.jenkinsbridge.vcs.VcsNormalizer;
import com.jetbrains.teamcity.jenkinsbridge.vcs.VcsProvider;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static com.jetbrains.teamcity.jenkinsbridge.util.Utilities.stringValue;

public class JenkinsVcsInfo {
  private static final VcsNormalizer NORMALIZER = new VcsNormalizer(); // TODO: Replace with dependency injection
  private final List<JenkinsVcsRepository> myRepositories;

  private JenkinsVcsInfo(List<JenkinsVcsRepository> repositories) {
    myRepositories = repositories;
  }

  public static JenkinsVcsInfo empty() {
    return new JenkinsVcsInfo(Collections.emptyList());
  }

  public static JenkinsVcsInfo fromJson(JsonObject json) {
    if (json == null) {
      return empty();
    }
    JsonArray actions = json.getAsJsonArray("actions");
    if (actions == null || actions.isEmpty()) {
      return empty();
    }

    // Deduplicate if the same repository is included multiple times (e.g., for multiple branches)
    Set<String> seen = new LinkedHashSet<>();
    List<JenkinsVcsRepository> result = new ArrayList<>();
    for (JsonElement element : actions) {
      if (element == null || !element.isJsonObject()) {
        continue;
      }
      JsonObject action = element.getAsJsonObject();
      String vcsClass = stringValue(action, "_class");
      if (VcsProvider.fromJenkinsClass(vcsClass) == null) {
        continue;
      }
      JenkinsVcsRepository repository = JenkinsVcsRepository.fromBuildDataJson(action);
      if (repository == null) {
        continue;
      }
      if (seen.add(identityKey(repository))) {
        result.add(repository);
      }
    }

    if (result.isEmpty()) {
      return empty();
    }
    return new JenkinsVcsInfo(Collections.unmodifiableList(result));
  }

  public List<JenkinsVcsRepository> getRepositories() {
    return Collections.unmodifiableList(myRepositories);
  }

  public boolean isEmpty() {
    return myRepositories.isEmpty();
  }

  public int size() {
    return myRepositories.size();
  }

  private static String identityKey(JenkinsVcsRepository repository) {
    String normalized = NORMALIZER.normalizeRepoUrl(repository.remoteUrl());
    return normalized == null ? repository.remoteUrl() : normalized;
  }
}
