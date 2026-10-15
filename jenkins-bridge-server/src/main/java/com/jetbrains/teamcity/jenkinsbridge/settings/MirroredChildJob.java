package com.jetbrains.teamcity.jenkinsbridge.settings;

import org.jetbrains.annotations.NotNull;

import java.util.Objects;

/**
 * Runtime child discovered under a configured multibranch parent. Uses the full job path returned
 * by Jenkins and retains its parent mapping instead of copying the parent's configuration.
 * Execution identity is persisted separately in each build mirror.
 */
public record MirroredChildJob(@NotNull MirroredJob parent, @NotNull String jenkinsJob)
    implements MirroredJobNode {
  public MirroredChildJob {
    Objects.requireNonNull(parent, "parent");
    if (!parent.isMultibranch()) {
      throw new IllegalArgumentException("A mirrored child requires a multibranch parent mapping");
    }
    jenkinsJob = Objects.requireNonNull(jenkinsJob, "jenkinsJob").trim();
    if (jenkinsJob.isEmpty()) {
      throw new IllegalArgumentException("A mirrored child requires a Jenkins job path");
    }
  }

  @Override
  @NotNull
  public MirroredJob configuredJob() {
    return parent;
  }
}
