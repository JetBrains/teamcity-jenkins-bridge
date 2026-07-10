package com.jetbrains.teamcity.jenkinsbridge.util;

import com.google.gson.JsonObject;
import jetbrains.buildServer.serverSide.ProjectManager;
import jetbrains.buildServer.serverSide.SBuildType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

public final class Utilities {

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

  @NotNull
  public static String stringValue(@Nullable JsonObject object, @Nullable String key) {
    if (object == null || object.get(key) == null || object.get(key).isJsonNull()) {
      return "";
    }
    return object.get(key).getAsString();
  }

  @NotNull
  public static String describeException(@NotNull Exception e) {
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

    boolean hasScheme = value.contains("://");
    String rest = hasScheme ? value.substring(value.indexOf("://") + 3) : value;

    // Drop user info (anything before '@')
    int firstSlash = rest.indexOf('/');
    int at = rest.indexOf('@');
    if (at >= 0 && (firstSlash < 0 || at < firstSlash)) {
      rest = rest.substring(at + 1);
      firstSlash = rest.indexOf('/');
    }

    String host;
    String path;
    int firstColon = rest.indexOf(':');
    if (!hasScheme && firstColon >= 0 && (firstSlash < 0 || firstColon < firstSlash)) {
      // SCP style (host:path)
      host = rest.substring(0, firstColon);
      path = rest.substring(firstColon + 1);
    } else if (firstSlash < 0) {
      host = rest;
      path = "";
    } else {
      host = rest.substring(0, firstSlash);
      path = rest.substring(firstSlash + 1);
      int hostColon = host.indexOf(':');
      if (hostColon >= 0) {
        host = host.substring(0, hostColon);
      }
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
