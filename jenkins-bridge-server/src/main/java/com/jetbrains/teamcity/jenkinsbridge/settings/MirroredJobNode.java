package com.jetbrains.teamcity.jenkinsbridge.settings;

import org.jetbrains.annotations.NotNull;

/**
 * Runtime Jenkins job within a configured mapping: either the configured job itself or one of
 * its multibranch children. The mapping owns the connection, TeamCity configuration, and polling
 * settings; the node names the concrete Jenkins job being read.
 */
public interface MirroredJobNode {
  /**
   * Returns the configured {@link MirroredJob} that supplies the TeamCity build configuration,
   * Jenkins connection, and polling settings for this node. A configured job returns itself;
   * a multibranch child returns its parent configured job. {@link #jenkinsJob()} identifies the
   * concrete Jenkins job to poll.
   */
  @NotNull
  MirroredJob configuredJob();

  /** Full Jenkins path of this node, including the child path for a multibranch child. */
  @NotNull
  String jenkinsJob();

  /**
   * Namespaces this node's persisted mirrors and polling watermark by TeamCity configuration and
   * concrete Jenkins job. The existing {@code <externalId>::<job>} format is retained.
   */
  @NotNull
  default String getMirrorKeyPrefix() {
    return configuredJob().teamCityBuildTypeExternalId() + "::" + jenkinsJob();
  }

  @NotNull
  default String describeForLog() {
    MirroredJob configuredJob = configuredJob();
    return "job " + jenkinsJob()
        + " -> buildType=" + configuredJob.teamCityBuildTypeExternalId()
        + " via connection " + configuredJob.connectionId()
        + " (recentBuildLimit=" + configuredJob.recentBuildLimit() + ")";
  }
}
