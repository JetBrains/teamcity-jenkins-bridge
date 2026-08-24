package com.jetbrains.teamcity.jenkinsbridge.util;

import com.google.gson.JsonObject;
import com.jetbrains.teamcity.jenkinsbridge.feature.BridgeBuildFeatureConstants;
import jetbrains.buildServer.serverSide.ProjectManager;
import jetbrains.buildServer.serverSide.SBuildType;
import jetbrains.buildServer.serverSide.SProject;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class Utilities {
  private static final Pattern SCP_REPOSITORY_URL = Pattern.compile("^(?:[^@/]+@)?([^:/]+):/?(.+)$");

  /**
   * Matches the branch name Jenkins assigns to pull and merge request builds in multibranch
   * pipelines (PR-N, MR-N, potentially with a head/merge suffix).
   */
  public static final Pattern PULL_OR_MERGE_REQUEST_BRANCH_NAME = Pattern.compile("[PM]R-\\d+(-(head|merge))?");

  private Utilities() {
  }

  @NotNull
  public static String nullToEmpty(@Nullable String value) {
    return value == null ? "" : value;
  }

  @Nullable
  public static SBuildType findBuildType(@Nullable String buildTypeId, @NotNull ProjectManager projectManager) {
    if (buildTypeId == null || buildTypeId.trim().isEmpty()) {
      return null;
    }
    SBuildType buildType = projectManager.findBuildTypeByExternalId(buildTypeId);
    if (buildType != null) {
      return buildType;
    }
    return projectManager.findBuildTypeById(buildTypeId);
  }

  @Nullable
  public static SProject findProject(@Nullable String projectId, @NotNull ProjectManager projectManager) {
    if (projectId == null || projectId.trim().isEmpty()) {
      return null;
    }
    SProject project = projectManager.findProjectByExternalId(projectId);
    return project != null ? project : projectManager.findProjectById(projectId);
  }

  @NotNull
  public static String stringValue(@Nullable JsonObject object, @Nullable String key) {
    if (object == null || object.get(key) == null || object.get(key).isJsonNull()) {
      return "";
    }
    return object.get(key).getAsString();
  }

  /**
   * The segment after the final {@code /} in a Jenkins job path.
   */
  @NotNull
  public static String lastPathSegment(@NotNull String path) {
    int slash = path.lastIndexOf('/');
    return slash >= 0 && slash < path.length() - 1 ? path.substring(slash + 1) : path;
  }

  /**
   * Decodes the URL-encoded branch fragment of the full name of a branch job inside a multibranch pipeline.
   * TODO: This is not needed if we use displayName instead of fullName from the API. Decide if that should be used instead.
   */
  @NotNull
  public static String decodeBranchFragment(@NotNull String name) {
    if (name.indexOf('%') < 0) {
      return name;
    }
    try {
      // Prevents literal "+"s from becoming spaces
      return URLDecoder.decode(name.replace("+", "%2B"), StandardCharsets.UTF_8);
    } catch (IllegalArgumentException malformedEscape) {
      return name;
    }
  }

  /** Whether the build configuration mirrors a Jenkins multibranch pipeline. */
  public static boolean isBuildConfigMultibranch(@NotNull SBuildType buildType) {
    return Boolean.parseBoolean(buildType.getParameterValue(BridgeBuildFeatureConstants.INTERNAL_MULTIBRANCH_PARAM));
  }

  @NotNull
  public static String describeException(@NotNull Throwable e) {
    return e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage());
  }

  /**
   * Used to decide whether two repository URLs point at the same repository.
   * <p>
   * Handles the common git URL shapes: https, ssh, and scp style (git@host:org/repo).
   * <p>
   * Returns null when the URL cannot be parsed into a host and a path.
   * TODO: Check if this covers SVN, Perforce, and Mercurial.
   */
  public static String normalizeRepositoryUrl(String url) {
    if (url == null) {
      return null;
    }
    String value = url.trim();
    if (value.isEmpty()) {
      return null;
    }

    Matcher scpUrl = SCP_REPOSITORY_URL.matcher(value);
    if (!value.contains("://") && scpUrl.matches()) {
      return normalizedRepositoryKey(scpUrl.group(1), scpUrl.group(2));
    }

    URI uri;
    try {
      uri = URI.create(value.contains("://") ? value : "https://" + value);
    } catch (IllegalArgumentException e) {
      return null;
    }

    return normalizedRepositoryKey(uri.getHost(), uri.getPath());
  }

  /**
   * Whether a Jenkins multibranch branch job name matches an internal Jenkins pull or merge request pattern.
   */
  public static boolean looksLikePullOrMergeRequestBranch(@NotNull String branchName) {
    return PULL_OR_MERGE_REQUEST_BRANCH_NAME.matcher(branchName.trim()).matches();
  }

  @NotNull
  public static String firstNonBlankString(@Nullable String... values) {
    if (values == null) return "";
    for (String value : values) {
      if (value != null && !value.trim().isEmpty()) {
        return value.trim();
      }
    }
    return "";
  }

  private static String normalizedRepositoryKey(String host, String path) {
    if (host == null || path == null) {
      return null;
    }
    host = host.toLowerCase(Locale.ROOT);
    path = stripSlashes(path);
    if (path.endsWith(".git")) {
      path = path.substring(0, path.length() - ".git".length());
    }
    path = stripSlashes(path);

    if (host.isEmpty() || path.isEmpty()) {
      return null;
    }
    return host + "/" + path;
  }

  private static String stripSlashes(String value) {
    int start = 0;
    int end = value.length();
    while (start < end && value.charAt(start) == '/') {
      start++;
    }
    while (end > start && value.charAt(end - 1) == '/') {
      end--;
    }
    return value.substring(start, end);
  }
}
