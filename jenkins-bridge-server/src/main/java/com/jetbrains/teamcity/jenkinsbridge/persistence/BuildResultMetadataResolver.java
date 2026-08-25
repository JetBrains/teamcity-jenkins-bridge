package com.jetbrains.teamcity.jenkinsbridge.persistence;

import jetbrains.buildServer.serverSide.SBuild;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;

/**
 * Resolves durable result-page metadata, independently of active synchronization state. Lookup first
 * uses the Jenkins build key and then TeamCity promotion/build IDs because either TeamCity-first or
 * Jenkins-first builds may lack one of those correlation values.
 */
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
      BuildResultMetadata resultMetadata = mirrorStore.findResultMetadataByJenkinsBuildKey(key);
      if (resultMetadata != null) {
        return resultMetadata;
      }
    }
    long promotionId = build.getBuildPromotion().getId();
    BuildResultMetadata resultMetadata = mirrorStore.findResultMetadataByTeamCityBuildId(promotionId);
    if (resultMetadata != null) {
      return resultMetadata;
    }
    long buildId = build.getBuildId();
    if (buildId == promotionId) {
      return null;
    }
    return mirrorStore.findResultMetadataByTeamCityBuildId(buildId);
  }
}
