package com.jetbrains.teamcity.jenkinsbridge.model;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.jetbrains.teamcity.jenkinsbridge.vcs.VcsProvider;

import java.util.*;
import org.jetbrains.annotations.NotNull;

import static com.jetbrains.teamcity.jenkinsbridge.util.Utilities.stringValue;

public record JenkinsVcsInfo(
    @NotNull List<JenkinsVcsRepository> repositories,
    Optional<JenkinsScmRevision> primaryRevision
) {

  public JenkinsVcsInfo {
    repositories = List.copyOf(repositories);
    primaryRevision = primaryRevision == null ? Optional.empty() : primaryRevision;
  }

  public JenkinsVcsInfo(@NotNull List<JenkinsVcsRepository> repositories) {
    this(repositories, Optional.empty());
  }

  public static JenkinsVcsInfo empty() {
    return new JenkinsVcsInfo(Collections.emptyList(), Optional.empty());
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
    JenkinsScmRevision primaryRevision = null;
    for (JsonElement element : actions) {
      if (element == null || !element.isJsonObject()) {
        continue;
      }
      JsonObject action = element.getAsJsonObject();
      String vcsClass = stringValue(action, "_class");
      if (vcsClass.endsWith("SCMRevisionAction")) {
        JenkinsScmRevision candidate = scmRevision(action);
        if (candidate != null && primaryRevision == null) {
          primaryRevision = candidate;
        }
        continue;
      }
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
      return new JenkinsVcsInfo(Collections.emptyList(), Optional.ofNullable(primaryRevision));
    }
    return new JenkinsVcsInfo(result, Optional.ofNullable(primaryRevision));
  }

  private static JenkinsScmRevision scmRevision(JsonObject action) {
    JsonElement revisionElement = action.get("revision");
    if (revisionElement == null || !revisionElement.isJsonObject()) {
      return null;
    }
    JsonObject revision = revisionElement.getAsJsonObject();
    String hash = stringValue(revision, "hash");
    JsonElement headElement = revision.get("head");
    String headName = headElement != null && headElement.isJsonObject()
        ? stringValue(headElement.getAsJsonObject(), "name") : "";
    return hash.isEmpty() || headName.isEmpty() ? null : new JenkinsScmRevision(headName, hash);
  }
}
