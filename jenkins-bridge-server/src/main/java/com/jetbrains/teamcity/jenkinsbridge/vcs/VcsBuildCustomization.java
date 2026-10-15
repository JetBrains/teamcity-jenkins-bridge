package com.jetbrains.teamcity.jenkinsbridge.vcs;

import jetbrains.buildServer.serverSide.RepositoryVersion;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** VCS data applied to a TeamCity promotion before it is queued. */
public record VcsBuildCustomization(
    @NotNull VcsSyncResult result,
    @NotNull Map<Long, RepositoryVersion> upperLimitRevisions
) {
  public VcsBuildCustomization(
      @Nullable VcsSyncResult result,
      @Nullable Map<Long, RepositoryVersion> upperLimitRevisions
  ) {
    this.result = result == null ? new VcsSyncResult() : result;
    this.upperLimitRevisions = upperLimitRevisions == null
        ? Collections.emptyMap()
        : Collections.unmodifiableMap(new LinkedHashMap<>(upperLimitRevisions));
  }

  public boolean hasRevisions() {
    return !upperLimitRevisions.isEmpty();
  }
}
