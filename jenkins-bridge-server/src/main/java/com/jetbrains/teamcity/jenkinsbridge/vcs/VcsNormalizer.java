package com.jetbrains.teamcity.jenkinsbridge.vcs;

import java.util.Locale;

import com.jetbrains.teamcity.jenkinsbridge.teamcity.TeamCityBranch;

public class VcsNormalizer {
  private static final String REFS_HEADS = "refs/heads/";
  private static final String REFS_REMOTES = "refs/remotes/";
  private static final String REFS_TAGS = "refs/tags/";
  private static final String DEFAULT_REMOTE_PREFIX = "origin/";

  /**
   * Used to decide whether two repository URLs point at the same repository.
   * <p>
   * Handles the common git URL shapes: https, ssh, and scp style (git@host:org/repo).
   * <p>
   * Returns null when the URL cannot be parsed into a host and a path.
   * TODO: Handle SVN, Perforce, and Mercurial.
   */
  public String normalizeRepoUrl(String url) {
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

  /**
   * Translates a raw Jenkins branch name into a TeamCity branch.
   * TODO: Handle SVN, Perforce, and Mercurial.
   */
  public TeamCityBranch toTeamCityBranch(String rawBranch) {
    if (rawBranch == null) {
      return TeamCityBranch.defaultBranch();
    }
    String branch = rawBranch.trim();
    if (branch.isEmpty()) {
      return TeamCityBranch.defaultBranch();
    }

    if (branch.startsWith(REFS_TAGS)) {
      return new TeamCityBranch(branch, lastSegment(branch));
    }
    if (branch.startsWith(REFS_HEADS)) {
      return new TeamCityBranch(branch, branch.substring(REFS_HEADS.length()));
    }
    if (branch.startsWith(REFS_REMOTES)) {
      // For refs/remotes/<remote>/<name>, drop the <remote> segment and keep the rest as the branch name.
      String remainder = branch.substring(REFS_REMOTES.length());
      String name = afterFirstSlash(remainder);
      if (name.isEmpty()) {
        name = remainder;
      }
      return new TeamCityBranch(REFS_HEADS + name, name);
    }
    if (branch.startsWith(DEFAULT_REMOTE_PREFIX)) {
      String name = branch.substring(DEFAULT_REMOTE_PREFIX.length());
      return new TeamCityBranch(REFS_HEADS + name, name);
    }
    return new TeamCityBranch(REFS_HEADS + branch, branch);
  }

  private String stripSlashes(String value) {
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

  private String afterFirstSlash(String value) {
    int slash = value.indexOf('/');
    return slash < 0 ? "" : value.substring(slash + 1);
  }

  private String lastSegment(String value) {
    int slash = value.lastIndexOf('/');
    return slash < 0 || slash == value.length() - 1 ? value : value.substring(slash + 1);
  }
}
