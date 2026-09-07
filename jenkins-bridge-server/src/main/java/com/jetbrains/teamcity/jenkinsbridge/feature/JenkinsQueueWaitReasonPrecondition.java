package com.jetbrains.teamcity.jenkinsbridge.feature;

import jetbrains.buildServer.BuildAgent;
import jetbrains.buildServer.serverSide.BuildPromotionEx;
import jetbrains.buildServer.serverSide.QueuedBuildEx;
import jetbrains.buildServer.serverSide.SBuildType;
import jetbrains.buildServer.serverSide.buildDistribution.BuildDistributorInput;
import jetbrains.buildServer.serverSide.buildDistribution.QueuedBuildInfo;
import jetbrains.buildServer.serverSide.buildDistribution.SimpleWaitReason;
import jetbrains.buildServer.serverSide.buildDistribution.StartBuildPrecondition;
import jetbrains.buildServer.serverSide.buildDistribution.WaitReason;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;

/**
 * Keeps a TeamCity-first Jenkins Bridge promotion queued until Jenkins has produced its build.
 *
 * <p>This runs before TeamCity's agent/executor distribution. Consequently it supplies a useful
 * queue explanation for the bridge's statically agentless configurations without changing their
 * native agentless state or their direct-start lifecycle.</p>
 */
public class JenkinsQueueWaitReasonPrecondition implements StartBuildPrecondition {
  public static final String WAITING_FOR_JENKINS_BUILD = "Waiting for Jenkins build";
  private static final WaitReason WAIT_REASON = new SimpleWaitReason(WAITING_FOR_JENKINS_BUILD);

  @Nullable
  @Override
  public WaitReason canStart(@NotNull QueuedBuildInfo queuedBuild,
                             @NotNull Map<QueuedBuildInfo, BuildAgent> canBeStarted,
                             @NotNull BuildDistributorInput buildDistributorInput,
                             boolean emulationMode) {
    if (!(queuedBuild instanceof QueuedBuildEx)) {
      return null;
    }

    BuildPromotionEx promotion = ((QueuedBuildEx) queuedBuild).getBuildPromotion();
    SBuildType buildType = promotion.getBuildType();
    if (buildType == null
        || !promotion.isAgentLessBuild()
        || promotion.getCustomParameters().containsKey(BridgeBuildFeatureConstants.JENKINS_BUILD_KEY_PARAM)
        || buildType.getBuildFeaturesOfType(BridgeBuildFeatureConstants.TYPE).isEmpty()) {
      return null;
    }
    return WAIT_REASON;
  }
}
