package com.jetbrains.teamcity.jenkinsbridge.teamcity;

import com.jetbrains.teamcity.jenkinsbridge.feature.BridgeBuildFeatureConstants;
import jetbrains.buildServer.serverSide.SBuildFeatureDescriptor;
import jetbrains.buildServer.serverSide.SBuildType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** Looks up the Jenkins Bridge build feature attached to a TeamCity build configuration. */
public final class TeamCityBridgeFeatureLookup {
  private TeamCityBridgeFeatureLookup() {
  }

  /**
   * Returns the first Jenkins Bridge feature, or {@code null} when none is attached.
   * TeamCity disallows multiple Jenkins Bridge features through its UI. Callers that need to
   * process every descriptor should iterate over them directly.
   */
  @Nullable
  public static SBuildFeatureDescriptor findBridgeFeature(@NotNull SBuildType buildType) {
    return buildType.getBuildFeaturesOfType(BridgeBuildFeatureConstants.TYPE)
        .stream().findFirst().orElse(null);
  }

  public static boolean hasBridgeFeature(@NotNull SBuildType buildType) {
    return findBridgeFeature(buildType) != null;
  }
}
