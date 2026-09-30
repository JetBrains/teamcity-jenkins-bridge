package com.jetbrains.teamcity.jenkinsbridge.vcs;

import jetbrains.buildServer.serverSide.RepositoryVersion;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** VCS data applied to a TeamCity promotion before it is queued. */
public record VcsBuildCustomization(
    VcsSyncResult result,
    Map<Long, RepositoryVersion> upperLimitRevisions,
    String desiredBranchName
) {
  public VcsBuildCustomization(
      VcsSyncResult result,
      Map<Long, RepositoryVersion> upperLimitRevisions,
      String desiredBranchName
  ) {
    this.result = result == null ? new VcsSyncResult() : result;
    this.upperLimitRevisions = upperLimitRevisions == null
        ? Collections.emptyMap()
        : Collections.unmodifiableMap(new LinkedHashMap<>(upperLimitRevisions));
    this.desiredBranchName = desiredBranchName;
  }

  public boolean hasRevisions() {
    return !upperLimitRevisions.isEmpty();
  }
}
