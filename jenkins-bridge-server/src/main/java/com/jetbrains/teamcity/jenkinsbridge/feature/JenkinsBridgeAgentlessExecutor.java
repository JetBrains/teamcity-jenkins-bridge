package com.jetbrains.teamcity.jenkinsbridge.feature;

import jetbrains.buildServer.serverSide.BuildPromotion;
import jetbrains.buildServer.serverSide.SBuildType;
import jetbrains.buildServer.serverSide.agentless.AgentlessBuildExecutor;
import jetbrains.buildServer.serverSide.agentless.AgentlessBuildStartResult;
import jetbrains.buildServer.serverSide.buildDistribution.SimpleWaitReason;
import org.jetbrains.annotations.NotNull;

/**
 * Supplies the queue explanation for TeamCity-first Jenkins Bridge promotions.
 *
 * <p>The promotion is intentionally kept in TeamCity's queue until the bridge resolves the
 * external Jenkins queue item and starts the detached TeamCity build. Without an executor-specific
 * wait result, TeamCity falls back to its generic agent-compatibility text, which is misleading for
 * an agentless build.</p>
 *
 * <p>This is a spike for the queue UX. The bridge's existing {@code TeamCityBuildStarter} remains
 * responsible for starting the promotion once Jenkins has produced a build.</p>
 */
public class JenkinsBridgeAgentlessExecutor implements AgentlessBuildExecutor {
  public static final String EXECUTOR_NAME = "Jenkins Bridge";
  public static final String WAITING_FOR_JENKINS_BUILD = "Waiting for Jenkins build to start";

  @Override
  public boolean supports(@NotNull BuildPromotion buildPromotion) {
    SBuildType buildType = buildPromotion.getBuildType();
    return buildType != null
        && !buildPromotion.getCustomParameters()
            .containsKey(BridgeBuildFeatureConstants.JENKINS_BUILD_KEY_PARAM)
        && !buildType.getBuildFeaturesOfType(BridgeBuildFeatureConstants.TYPE).isEmpty();
  }

  @Override
  @NotNull
  public AgentlessBuildStartResult checkCanStart(@NotNull BuildPromotion buildPromotion) {
    return new AgentlessBuildStartResult(
        EXECUTOR_NAME, new SimpleWaitReason(WAITING_FOR_JENKINS_BUILD));
  }
}
