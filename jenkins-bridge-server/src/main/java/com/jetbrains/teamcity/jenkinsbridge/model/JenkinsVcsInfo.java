package com.jetbrains.teamcity.jenkinsbridge.model;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.jetbrains.teamcity.jenkinsbridge.vcs.VcsProvider;

import java.util.*;
import org.jetbrains.annotations.NotNull;

import static com.jetbrains.teamcity.jenkinsbridge.util.Utilities.stringValue;

public record JenkinsVcsInfo(@NotNull List<JenkinsVcsRepository> repositories) {

  public JenkinsVcsInfo {
    repositories = List.copyOf(repositories);
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
      if (seen.add(repository.identityKey())) {
        result.add(repository);
      }
    }

    if (result.isEmpty()) {
      return empty();
    }
    return new JenkinsVcsInfo(result);
  }
}
