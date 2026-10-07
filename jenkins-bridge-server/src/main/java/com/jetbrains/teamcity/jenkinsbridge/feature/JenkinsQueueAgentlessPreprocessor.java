package com.jetbrains.teamcity.jenkinsbridge.feature;

import com.intellij.openapi.diagnostic.Logger;
import com.jetbrains.teamcity.jenkinsbridge.teamcity.TeamCityBranchResolver;
import com.jetbrains.teamcity.jenkinsbridge.teamcity.TeamCityBuildParameters;
import com.jetbrains.teamcity.jenkinsbridge.util.Utilities;
import jetbrains.buildServer.AgentRestrictor;
import jetbrains.buildServer.serverSide.AddToQueuePreprocessor;
import jetbrains.buildServer.serverSide.BuildPromotion;
import jetbrains.buildServer.serverSide.BuildPromotionEx;
import jetbrains.buildServer.serverSide.SBuildFeatureDescriptor;
import jetbrains.buildServer.serverSide.SBuildType;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Prepares TeamCity-first bridge builds before they enter the queue.
 *
 * <p>When a user clicks Run on a configuration carrying the Jenkins Bridge feature, TeamCity would
 * otherwise queue a normal build that competes for an agent and runs the config's own steps. The
 * bridge instead mirrors the Jenkins run into that build. Imported configurations are statically
 * agentless, so TeamCity's normal agent/executor selection is not involved.
 *
 * <p>Scope is deliberately narrow: this adds the bridge correlation parameter and pins a selected
 * multibranch child to a matching TeamCity Git branch. It performs no Jenkins I/O — triggering Jenkins and recording the
 * {@link com.jetbrains.teamcity.jenkinsbridge.persistence.PendingTrigger}
 * stay in {@link JenkinsTriggerOnRunListener}, off this latency-sensitive path. Non-bridge builds
 * pass through untouched after a cheap feature check. Artifact-storage setup is performed later
 * by the main-node Jenkins trigger listener, so secondary nodes do not mutate project storage.
 *
 * <p>Registered as a Spring bean; TeamCity auto-discovers {@code ServerExtension} beans.
 */
public class JenkinsQueueAgentlessPreprocessor implements AddToQueuePreprocessor {
  private static final Logger LOG = Logger.getInstance(JenkinsQueueAgentlessPreprocessor.class.getName());

  @Override
  public Map<BuildPromotion, AgentRestrictor> preprocess(
      Map<BuildPromotion, AgentRestrictor> promotions, String triggeredBy) {
    if (promotions == null || promotions.isEmpty()) {
      return promotions;
    }
    for (BuildPromotion promotion : promotions.keySet()) {
      try {
        addBridgeParametersIfNeeded(promotion, triggeredBy);
      } catch (RuntimeException e) {
        // Never break queueing for a bug in the bridge; the existing listener and poller still
        // mirror the build, but correlation falls back to the pending-trigger lookup.
        LOG.error("Jenkins Bridge: failed to prepare promotion "
            + safeId(promotion) + " before queueing", e);
      }
    }
    // We only mutate promotions in place; the queued set and agent restrictions are left unchanged.
    return promotions;
  }

  private void addBridgeParametersIfNeeded(BuildPromotion promotion, String triggeredBy) {
    SBuildType buildType = promotion.getBuildType();
    if (buildType == null || !hasBridgeFeature(buildType)) {
      return;
    }
    // Jenkins-first builds are already created agentless by the bridge and carry jenkins.build.key.
    if (promotion.getCustomParameters().containsKey(BridgeBuildFeatureConstants.JENKINS_BUILD_KEY_PARAM)) {
      return;
    }
    if (!(promotion instanceof BuildPromotionEx)) {
      return;
    }
    BuildPromotionEx ex = (BuildPromotionEx) promotion;
    Map<String, String> parameters = new LinkedHashMap<String, String>(promotion.getCustomParameters());
    if (!parameters.containsKey(TeamCityBuildParameters.TEAMCITY_PROMOTION_ID)) {
      parameters.put(TeamCityBuildParameters.TEAMCITY_PROMOTION_ID,
          JenkinsTriggerCorrelation.encode(promotion.getId()));
    }
    ex.setCustomParameters(parameters);

    setMultibranchBranchIfMonitored(promotion, buildType);
  }

  private void setMultibranchBranchIfMonitored(BuildPromotion promotion, SBuildType buildType) {
    if (!Utilities.isBuildConfigMultibranch(buildType)) {
      return;
    }

    String selectedChildPath = promotion.getCustomParameters().get(
        BridgeBuildFeatureConstants.MULTIBRANCH_BRANCH_PARAM);
    if (selectedChildPath == null || selectedChildPath.trim().isEmpty()) {
      return;
    }

    SBuildFeatureDescriptor descriptor = buildType
        .getBuildFeaturesOfType(BridgeBuildFeatureConstants.TYPE).stream().findFirst().orElse(null);
    String parentPath = descriptor == null
        ? null
        : descriptor.getParameters().get(BridgeBuildFeatureConstants.PARAM_JENKINS_JOB);
    if (parentPath == null || parentPath.trim().isEmpty()) {
      return;
    }

    String childPrefix = parentPath.trim() + "/";
    if (!selectedChildPath.startsWith(childPrefix)) {
      LOG.warn("Jenkins Bridge: cannot derive TeamCity branch from selected Jenkins child '"
          + selectedChildPath + "' because it is not under configured parent '" + parentPath + "'");
      return;
    }

    String branchName = Utilities.decodeBranchFragment(
        selectedChildPath.substring(childPrefix.length()));
    String teamCityBranch = TeamCityBranchResolver.resolveMultibranchSelection(buildType, branchName);
    if (teamCityBranch != null) {
      promotion.setDesiredBranchName(teamCityBranch);
      LOG.info("Jenkins Bridge: assigning TeamCity branch '" + teamCityBranch
          + "' for selected Jenkins child '" + selectedChildPath + "'");
    }
  }

  private boolean hasBridgeFeature(SBuildType buildType) {
    return !buildType.getBuildFeaturesOfType(BridgeBuildFeatureConstants.TYPE).isEmpty();
  }

  private static String safeId(BuildPromotion promotion) {
    try {
      return String.valueOf(promotion.getId());
    } catch (RuntimeException e) {
      return "<unknown>";
    }
  }

}
