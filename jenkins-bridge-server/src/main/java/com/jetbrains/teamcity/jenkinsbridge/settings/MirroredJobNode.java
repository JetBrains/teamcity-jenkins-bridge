package com.jetbrains.teamcity.jenkinsbridge.settings;

import org.jetbrains.annotations.NotNull;

/**
 * Runtime Jenkins job within a configured mapping: either the configured job itself or one of
 * its multibranch children. The mapping owns the connection, TeamCity configuration, and polling
 * settings; the node names the concrete Jenkins job being read.
 */
public interface MirroredJobNode {
  /** The configured mapping that owns this node. */
  @NotNull
  MirroredJob mapping();

  /** Full Jenkins path of this node, including the child path for a multibranch child. */
  @NotNull
  String jenkinsJob();

  /**
   * Namespaces this node's persisted mirrors and polling watermark by TeamCity configuration and
   * concrete Jenkins job. The existing {@code <externalId>::<job>} format is retained.
   */
  @NotNull
  default String getMirrorKeyPrefix() {
    return mapping().teamCityBuildTypeExternalId() + "::" + jenkinsJob();
  }

  @NotNull
  default String describeForLog() {
    MirroredJob mapping = mapping();
    return "job " + jenkinsJob()
        + " -> buildType=" + mapping.teamCityBuildTypeExternalId()
        + " via connection " + mapping.connectionId()
        + " (recentBuildLimit=" + mapping.recentBuildLimit() + ")";
  }
}
