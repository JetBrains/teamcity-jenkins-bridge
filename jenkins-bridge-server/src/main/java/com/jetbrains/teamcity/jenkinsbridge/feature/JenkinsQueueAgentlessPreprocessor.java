package com.jetbrains.teamcity.jenkinsbridge.feature;

import com.intellij.openapi.diagnostic.Logger;
import com.jetbrains.teamcity.jenkinsbridge.teamcity.TeamCityBuildParameters;
import jetbrains.buildServer.AgentRestrictor;
import jetbrains.buildServer.serverSide.AddToQueuePreprocessor;
import jetbrains.buildServer.serverSide.BuildPromotion;
import jetbrains.buildServer.serverSide.BuildPromotionEx;
import jetbrains.buildServer.serverSide.SBuildType;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Marks TeamCity-first bridge builds agentless <em>before</em> they enter the queue.
 *
 * <p>When a user clicks Run on a configuration carrying the Jenkins Bridge feature, TeamCity would
 * otherwise queue a normal build that competes for an agent and runs the config's own steps. The
 * bridge instead mirrors the Jenkins run into that build, so it must be agentless (bridge-controlled
 * running/finish state). Agent scheduling is decided at queue time, so the only place to flip it is
 * here — {@link AddToQueuePreprocessor} fires on the queueing path just before insert.
 *
 * <p>Scope is deliberately narrow: this only sets {@code teamcity.build.agentLess}. It performs no
 * Jenkins I/O — triggering Jenkins and recording the {@link com.jetbrains.teamcity.jenkinsbridge.persistence.PendingTrigger}
 * stay in {@link JenkinsTriggerOnRunListener}, off this latency-sensitive path. Non-bridge builds
 * pass through untouched after a cheap feature check, and any failure is swallowed so queueing is
 * never broken.
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
        markAgentlessIfBridge(promotion);
      } catch (RuntimeException e) {
        // Never break queueing for a bug in the bridge; the build just stays non-agentless and the
        // existing post-queue listener + poller still mirror it (only the live-in-queue view suffers).
        LOG.error("Jenkins Bridge: failed to mark promotion "
            + safeId(promotion) + " agentless before queueing", e);
      }
    }
    // We only mutate promotions in place; the queued set and agent restrictions are left unchanged.
    return promotions;
  }

  private void markAgentlessIfBridge(BuildPromotion promotion) {
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
    if (ex.isAgentLessBuild()) {
      return;
    }
    Map<String, String> parameters = new LinkedHashMap<String, String>(promotion.getCustomParameters());
    parameters.put(TeamCityBuildParameters.AGENTLESS_BUILD_PROPERTY, "true");
    ex.setCustomParameters(parameters);
    LOG.info("Jenkins Bridge: marked TeamCity promotion " + promotion.getId()
        + " agentless before queueing");
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
