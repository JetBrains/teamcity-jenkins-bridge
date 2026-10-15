package com.jetbrains.teamcity.jenkinsbridge.model;

import com.google.gson.*;
import com.jetbrains.teamcity.jenkinsbridge.vcs.VcsRefType;
import org.jetbrains.annotations.NotNull;

import java.util.*;

import static com.jetbrains.teamcity.jenkinsbridge.util.Utilities.normalizeRepositoryUrl;

/** Immutable SCM discovery data; a build's checkout is never evidence of the repository default. */
public record JenkinsMultibranchScmSnapshot(@NotNull List<Child> children) {
  public static final String PRIMARY_ACTION = "jenkins.scm.api.metadata.PrimaryInstanceMetadataAction";
  public JenkinsMultibranchScmSnapshot { children = List.copyOf(children); }

  public record Child(@NotNull String path, @NotNull String headName,
                      @NotNull VcsRefType refType, @NotNull String remoteUrl,
                      boolean primary, boolean changeRequest) {
    @NotNull public String ref() { return (refType == VcsRefType.TAGS ? "refs/tags/" : "refs/heads/") + headName; }
    @NotNull public JenkinsScmHeadInfo scmInfo() { return new JenkinsScmHeadInfo(refType, headName, "", remoteUrl); }
  }

  @NotNull public Optional<Child> child(@NotNull String path) {
    List<Child> matches = children.stream().filter(c -> c.path().equals(path)).toList();
    return matches.size() == 1 ? Optional.of(matches.getFirst()) : Optional.empty();
  }

  /** Exactly one primary ordinary head for this repository is required, even if names coincide. */
  @NotNull public Optional<String> defaultRef(@NotNull String remoteUrl) {
    String normalized = normalizeRepositoryUrl(remoteUrl);
    if (normalized == null) return Optional.empty();
    List<Child> primary = children.stream().filter(c -> c.primary() && !c.changeRequest()
        && c.refType() == VcsRefType.HEADS
        && normalized.equals(normalizeRepositoryUrl(c.remoteUrl()))).toList();
    return primary.size() == 1 ? Optional.of(primary.getFirst().ref()) : Optional.empty();
  }

  @NotNull public static JenkinsMultibranchScmSnapshot fromJson(@NotNull JsonObject json) {
    List<Child> children = new ArrayList<>();
    for (JsonElement element : array(json, "jobs")) {
      if (!element.isJsonObject()) continue;
      JsonObject job = element.getAsJsonObject();
      String path = string(job, "fullName");
      if (path.isEmpty()) continue;
      for (JsonElement property : array(job, "property")) {
        if (!property.isJsonObject()) continue;
        JsonObject branch = object(property.getAsJsonObject(), "branch");
        JsonObject head = object(branch, "head"), scm = object(branch, "scm");
        String name = string(head, "name"), type = string(head, "_class");
        if (name.isEmpty() || type.isEmpty() || !"hudson.plugins.git.GitSCM".equals(string(scm, "_class"))) continue;
        Map<String, String> remotes = new LinkedHashMap<>();
        for (JsonElement remote : array(scm, "userRemoteConfigs")) {
          if (!remote.isJsonObject()) continue;
          String url = string(remote.getAsJsonObject(), "url"), normalized = normalizeRepositoryUrl(url);
          if (normalized != null) remotes.putIfAbsent(normalized, url);
        }
        if (remotes.size() != 1) continue;
        boolean pr = type.contains("PullRequest") || type.contains("MergeRequest") || type.contains("ChangeRequest");
        children.add(new Child(path, name, VcsRefType.fromHeadClass(type), remotes.values().iterator().next(),
            primary(job) || primary(branch), pr));
      }
    }
    return new JenkinsMultibranchScmSnapshot(children);
  }
  private static boolean primary(JsonObject object) {
    for (JsonElement action : array(object, "actions")) {
      if (action.isJsonObject() && PRIMARY_ACTION.equals(string(action.getAsJsonObject(), "_class"))) return true;
    }
    return false;
  }
  private static JsonObject object(JsonObject parent, String key) {
    JsonElement value = parent.get(key);
    return value != null && value.isJsonObject() ? value.getAsJsonObject() : new JsonObject();
  }
  private static JsonArray array(JsonObject parent, String key) {
    JsonElement value = parent.get(key);
    return value != null && value.isJsonArray() ? value.getAsJsonArray() : new JsonArray();
  }
  private static String string(JsonObject parent, String key) {
    JsonElement value = parent.get(key);
    return value != null && value.isJsonPrimitive() ? value.getAsString() : "";
  }
}
