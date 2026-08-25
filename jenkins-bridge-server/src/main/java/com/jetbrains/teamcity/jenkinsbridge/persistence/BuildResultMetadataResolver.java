package com.jetbrains.teamcity.jenkinsbridge.persistence;

import jetbrains.buildServer.serverSide.SBuild;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;

/** Resolves durable result-page metadata, independently of active synchronization state. */
public class BuildResultMetadataResolver {
  private final BuildMirrorStore mirrorStore;

  public BuildResultMetadataResolver(@NotNull BuildMirrorStore mirrorStore) {
    this.mirrorStore = mirrorStore;
  }

  @Nullable
  public BuildResultMetadata resolve(@NotNull SBuild build) throws IOException {
    String key = build.getParametersProvider().get(
        com.jetbrains.teamcity.jenkinsbridge.feature.BridgeBuildFeatureConstants.JENKINS_BUILD_KEY_PARAM);
    if (key != null && !key.trim().isEmpty()) {
      BuildResultMetadata metadata = mirrorStore.findResultMetadataByJenkinsBuildKey(key);
      if (metadata != null) {
        return metadata;
      }
    }
    BuildResultMetadata metadata = mirrorStore.findResultMetadataByTeamCityBuildId(
        build.getBuildPromotion().getId());
    if (metadata != null) {
      return metadata;
    }
    return build.getBuildId() == build.getBuildPromotion().getId()
        ? null : mirrorStore.findResultMetadataByTeamCityBuildId(build.getBuildId());
  }
}
