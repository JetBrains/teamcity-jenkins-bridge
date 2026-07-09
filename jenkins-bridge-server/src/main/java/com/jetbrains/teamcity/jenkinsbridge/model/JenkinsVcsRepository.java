package com.jetbrains.teamcity.jenkinsbridge.model;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;

import static com.jetbrains.teamcity.jenkinsbridge.util.Utilities.nullToEmpty;
import static com.jetbrains.teamcity.jenkinsbridge.util.Utilities.stringValue;

public record JenkinsVcsRepository(String vcsClass, String remoteUrl, String sha1, String rawBranchName) {
  public JenkinsVcsRepository {
    vcsClass = nullToEmpty(vcsClass);
    remoteUrl = nullToEmpty(remoteUrl);
    sha1 = nullToEmpty(sha1);
    rawBranchName = nullToEmpty(rawBranchName);
  }

  public static JenkinsVcsRepository fromBuildDataJson(JsonObject action) {
    if (action == null) {
      return null;
    }
    String vcsClass = stringValue(action, "_class");
    Optional<String> remoteUrl = firstNonEmptyString(action.getAsJsonArray("remoteUrls"));

    String sha1 = "";
    Optional<String> branch = Optional.empty();
    JsonElement revision = action.get("lastBuiltRevision");
    if (revision != null && revision.isJsonObject()) {
      JsonObject revisionObject = revision.getAsJsonObject();
      sha1 = stringValue(revisionObject, "SHA1");
      branch = firstBranchName(revisionObject.getAsJsonArray("branch"));
    }

    if (sha1.isEmpty() || remoteUrl.isEmpty()) {
      return null;
    }
    return new JenkinsVcsRepository(vcsClass, remoteUrl.get(), sha1, branch.orElse(""));
  }

  @NotNull
  private static Optional<String> firstNonEmptyString(JsonArray array) {
    if (array == null) {
      return Optional.empty();
    }
    for (JsonElement element : array) {
      if (element != null && element.isJsonPrimitive()) {
        String value = element.getAsString();
        if (value != null && !value.isEmpty()) {
          return Optional.of(value);
        }
      }
    }
    return Optional.empty();
  }

  @NotNull
  private static Optional<String> firstBranchName(JsonArray branches) {
    if (branches == null) {
      return Optional.empty();
    }
    for (JsonElement element : branches) {
      if (element != null && element.isJsonObject()) {
        String name = stringValue(element.getAsJsonObject(), "name");
        if (!name.isEmpty()) {
          return Optional.of(name);
        }
      }
    }
    return Optional.empty();
  }

}
