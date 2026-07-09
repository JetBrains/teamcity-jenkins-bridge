package com.jetbrains.teamcity.jenkinsbridge.teamcity;

/**
 * The git ref to record on the revision and the display name shown in the UI.
 * The default branch is represented by empty strings.
 */
public record TeamCityBranch(String ref, String displayName) {
  public TeamCityBranch(String ref, String displayName) {
    this.ref = ref == null ? "" : ref;
    this.displayName = displayName == null ? "" : displayName;
  }

  public static TeamCityBranch defaultBranch() {
    return new TeamCityBranch("", "");
  }

  public boolean isDefault() {
    return displayName.isEmpty();
  }
}
