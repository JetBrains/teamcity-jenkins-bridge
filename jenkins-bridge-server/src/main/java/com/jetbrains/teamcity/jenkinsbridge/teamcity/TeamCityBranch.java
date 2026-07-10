package com.jetbrains.teamcity.jenkinsbridge.teamcity;

import static com.jetbrains.teamcity.jenkinsbridge.util.Utilities.nullToEmpty;

/**
 * The ref to record on the revision and the display name shown in the UI.
 * The default branch is represented by empty strings.
 */
public record TeamCityBranch(String ref, String displayName) {
  public TeamCityBranch {
    ref = nullToEmpty(ref);
    displayName = nullToEmpty(displayName);
  }

  public static TeamCityBranch defaultBranch() {
    return new TeamCityBranch("", "");
  }

  public boolean isDefault() {
    return displayName.isEmpty();
  }

  /**
   * Translates a branch name Jenkins gives for a git branch into
   * the format expected by TeamCity.
   */
  public static TeamCityBranch fromJenkinsGit(String jenkinsBranch) {
    final String REFS_HEADS = "refs/heads/";
    final String REFS_REMOTES = "refs/remotes/";
    final String REFS_TAGS = "refs/tags/";
    final String DEFAULT_REMOTE_PREFIX = "origin/";

    if (jenkinsBranch == null) {
      return TeamCityBranch.defaultBranch();
    }
    String branch = jenkinsBranch.trim();
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

  private static String afterFirstSlash(String value) {
    int slash = value.indexOf('/');
    return slash < 0 ? "" : value.substring(slash + 1);
  }

  private static String lastSegment(String value) {
    int slash = value.lastIndexOf('/');
    return slash < 0 || slash == value.length() - 1 ? value : value.substring(slash + 1);
  }
}
