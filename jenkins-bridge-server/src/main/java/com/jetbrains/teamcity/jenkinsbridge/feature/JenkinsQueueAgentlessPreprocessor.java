package com.jetbrains.teamcity.jenkinsbridge.feature;

import com.intellij.openapi.diagnostic.Logger;
import com.jetbrains.teamcity.jenkinsbridge.teamcity.TeamCityBuildParameters;
import jetbrains.buildServer.AgentRestrictor;
import jetbrains.buildServer.serverSide.BuildAttributes;
import jetbrains.buildServer.serverSide.AddToQueuePreprocessor;
import jetbrains.buildServer.serverSide.BuildPromotion;
import jetbrains.buildServer.serverSide.BuildPromotionEx;
import jetbrains.buildServer.clouds.server.executors.BuildExecutorDescriptor;
import jetbrains.buildServer.clouds.server.executors.BuildExecutorsManager;
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
 * pass through untouched after a cheap feature check. Artifact-storage setup is performed later
 * by the main-node Jenkins trigger listener, so secondary nodes do not mutate project storage.
 *
 * <p>Registered as a Spring bean; TeamCity auto-discovers {@code ServerExtension} beans.
 */
public class JenkinsQueueAgentlessPreprocessor implements AddToQueuePreprocessor {
  private static final Logger LOG = Logger.getInstance(JenkinsQueueAgentlessPreprocessor.class.getName());
  private final BuildExecutorsManager buildExecutorsManager;

  public JenkinsQueueAgentlessPreprocessor() {
    this(null);
  }

  public JenkinsQueueAgentlessPreprocessor(BuildExecutorsManager buildExecutorsManager) {
    this.buildExecutorsManager = buildExecutorsManager;
  }

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
        // Never break queueing for a bug in the bridge; the build just stays non-agentless and the
        // existing post-queue listener + poller still mirror it (only the live-in-queue view suffers).
        LOG.error("Jenkins Bridge: failed to mark promotion "
            + safeId(promotion) + " agentless before queueing", e);
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
    if (!ex.isAgentLessBuild()) {
      parameters.put(TeamCityBuildParameters.AGENTLESS_BUILD_PROPERTY, "true");
    }
    selectJenkinsBridgeExecutor(promotion, parameters);
    if (!parameters.containsKey(TeamCityBuildParameters.TEAMCITY_PROMOTION_ID)) {
      parameters.put(TeamCityBuildParameters.TEAMCITY_PROMOTION_ID,
          JenkinsTriggerCorrelation.encode(promotion.getId()));
    }
    ex.setCustomParameters(parameters);
    LOG.info("Jenkins Bridge: marked TeamCity promotion " + promotion.getId()
        + " agentless before queueing");
  }

  private void selectJenkinsBridgeExecutor(BuildPromotion promotion, Map<String, String> parameters) {
    if (buildExecutorsManager == null || promotion.getBuildType() == null) {
      return;
    }
    BuildExecutorDescriptor descriptor = buildExecutorsManager
        .getOwnAvailableExecutors(promotion.getBuildType().getProject()).stream()
        .filter(candidate -> JenkinsBridgeExecutorType.EXECUTOR_TYPE.equals(
            candidate.getExecutorType().getExecutorType()))
        .findFirst()
        .orElseGet(() -> buildExecutorsManager.addExecutor(
            promotion.getBuildType().getProject(),
            executorProfileParameters(),
            new JenkinsBridgeExecutorType()));
    parameters.put(BuildAttributes.AGENT_LESS_BUILD_EXECUTOR, descriptor.getId());
  }

  private Map<String, String> executorProfileParameters() {
    Map<String, String> parameters = new LinkedHashMap<>();
    parameters.put("profileName", "Jenkins Bridge");
    parameters.put("profileDescription", "Mirrors the queued build through Jenkins Bridge");
    return parameters;
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
