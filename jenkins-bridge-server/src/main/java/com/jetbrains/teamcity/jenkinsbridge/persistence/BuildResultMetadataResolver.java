package com.jetbrains.teamcity.jenkinsbridge.persistence;

import com.jetbrains.teamcity.jenkinsbridge.feature.BridgeBuildFeatureConstants;
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
    BuildResultMetadata resultMetadata = findByJenkinsBuildKey(build.getParametersProvider().get(
        BridgeBuildFeatureConstants.JENKINS_BUILD_KEY_PARAM));
    if (resultMetadata != null) {
      return resultMetadata;
    }
    long promotionId = build.getBuildPromotion().getId();
    resultMetadata = mirrorStore.findResultMetadataByTeamCityBuildId(promotionId);
    if (resultMetadata != null) {
      return resultMetadata;
    }
    long buildId = build.getBuildId();
    if (buildId == promotionId) {
      return null;
    }
    return mirrorStore.findResultMetadataByTeamCityBuildId(buildId);
  }

  @Nullable
  private BuildResultMetadata findByJenkinsBuildKey(@Nullable String key) throws IOException {
    if (key == null || key.trim().isEmpty()) {
      return null;
    }
    return mirrorStore.findResultMetadataByJenkinsBuildKey(key);
  }
}
