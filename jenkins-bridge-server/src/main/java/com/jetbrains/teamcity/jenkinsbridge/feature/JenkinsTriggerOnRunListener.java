package com.jetbrains.teamcity.jenkinsbridge.feature;

import com.intellij.openapi.diagnostic.Logger;
import com.jetbrains.teamcity.jenkinsbridge.http.BridgeHttpException;
import com.jetbrains.teamcity.jenkinsbridge.jenkins.JenkinsClient;
import com.jetbrains.teamcity.jenkinsbridge.jenkins.JenkinsClientFactory;
import com.jetbrains.teamcity.jenkinsbridge.jenkins.JenkinsDataException;
import com.jetbrains.teamcity.jenkinsbridge.model.JenkinsJobParameters;
import com.jetbrains.teamcity.jenkinsbridge.persistence.BuildMirrorStore;
import com.jetbrains.teamcity.jenkinsbridge.persistence.PendingTrigger;
import com.jetbrains.teamcity.jenkinsbridge.model.JenkinsTriggerResponse;
import com.jetbrains.teamcity.jenkinsbridge.teamcity.TeamCityBuildParameters;
import com.jetbrains.teamcity.jenkinsbridge.teamcity.TeamCityQueuedBuildFailureService;
import com.jetbrains.teamcity.jenkinsbridge.util.TeamCityNodeLog;
import jetbrains.buildServer.serverSide.BuildPromotion;
import jetbrains.buildServer.serverSide.BuildServerAdapter;
import jetbrains.buildServer.serverSide.BuildServerListener;
import jetbrains.buildServer.serverSide.SBuildFeatureDescriptor;
import jetbrains.buildServer.serverSide.SBuildType;
import jetbrains.buildServer.serverSide.SQueuedBuild;
import jetbrains.buildServer.serverSide.TeamCityNode;
import jetbrains.buildServer.serverSide.TeamCityNodes;
import jetbrains.buildServer.util.EventDispatcher;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * TeamCity-first trigger path: when a user queues a build configuration carrying the Jenkins Bridge
 * feature, trigger Jenkins and later bind the resulting Jenkins build back to this same promotion.
 */
public class JenkinsTriggerOnRunListener {
  private static final Logger LOG = Logger.getInstance(JenkinsTriggerOnRunListener.class.getName());
  private static final String TRIGGERED_BY_BRIDGE = "Jenkins Bridge";

  private final EventDispatcher<BuildServerListener> eventDispatcher;
  private final JenkinsClientFactory jenkinsClientFactory;
  private final BuildMirrorStore mirrorStore;
  private final TeamCityQueuedBuildFailureService failureService;
  private final TeamCityNodes teamCityNodes;
  private final JenkinsParameterSynchronizer parameterSynchronizer;
  private final BuildServerListener listener = new BuildServerAdapter() {
    @Override
    public void buildTypeAddedToQueue(SQueuedBuild queued) {
      LOG.debug(TeamCityNodeLog.currentNode(teamCityNodes)
          + " [Jenkins Bridge DEBUG] Queue callback received for TeamCity promotion "
          + queued.getBuildPromotion().getId());
      if (!mayTriggerOnCurrentNode()) {
        LOG.info(TeamCityNodeLog.currentNode(teamCityNodes)
            + " Jenkins Bridge trigger skipped because this node is not main");
        return;
      }
      triggerJenkinsSafely(queued);
    }
  };

  public JenkinsTriggerOnRunListener(
      @NotNull EventDispatcher<BuildServerListener> eventDispatcher,
      @NotNull JenkinsClientFactory jenkinsClientFactory,
      @NotNull BuildMirrorStore mirrorStore,
      @NotNull TeamCityQueuedBuildFailureService failureService,
      @NotNull TeamCityNodes teamCityNodes,
      @NotNull JenkinsParameterSynchronizer parameterSynchronizer
  ) {
    this.eventDispatcher = eventDispatcher;
    this.jenkinsClientFactory = jenkinsClientFactory;
    this.mirrorStore = mirrorStore;
    this.failureService = failureService;
    this.teamCityNodes = teamCityNodes;
    this.parameterSynchronizer = parameterSynchronizer;
    this.eventDispatcher.addListener(listener);
  }

  private boolean mayTriggerOnCurrentNode() {
    TeamCityNode currentNode = teamCityNodes.getCurrentNode();
    return currentNode != null && currentNode.isMainNode();
  }

  public void dispose() {
    eventDispatcher.removeListener(listener);
  }

  private void triggerJenkinsSafely(SQueuedBuild queued) {
    TriggerAttemptContext attempt = new TriggerAttemptContext();
    LOG.debug(TeamCityNodeLog.currentNode(teamCityNodes)
        + " [Jenkins Bridge DEBUG] Evaluating TeamCity promotion "
        + queued.getBuildPromotion().getId() + " for Jenkins trigger");
    try {
      triggerJenkins(queued, attempt);
    } catch (IOException e) {
      if (attempt.isJenkinsRequestStarted()) {
        failAfterJenkinsRequestIfNeeded(attempt, e);
      } else {
        cleanupBeforeJenkinsRequestIfNeeded(queued, attempt, e);
      }
      LOG.error("Jenkins Bridge could not read or persist trigger state for a TeamCity queued build", e);
    } catch (RuntimeException e) {
      if (attempt.isJenkinsRequestStarted()) {
        failAfterJenkinsRequestIfNeeded(attempt, e);
      } else {
        cleanupBeforeJenkinsRequestIfNeeded(queued, attempt, e);
      }
      LOG.error("Jenkins Bridge failed to process a TeamCity queued build", e);
    }
  }

  private void failAfterJenkinsRequestIfNeeded(TriggerAttemptContext attempt, Throwable originalFailure) {
    long promotionId = attempt.getPromotionId();
    try {
      failureService.failQueuedPromotion(promotionId,
          "Jenkins Bridge called Jenkins, but did not receive a confirmed trigger response. "
              + "The result is uncertain; check Jenkins for an accepted build.");
      mirrorStore.removePendingTrigger(promotionId);
    } catch (IOException | RuntimeException failureHandlingFailure) {
      originalFailure.addSuppressed(failureHandlingFailure);
      LOG.error("Jenkins Bridge could not record the uncertain Jenkins trigger for TeamCity promotion "
          + promotionId, failureHandlingFailure);
    }
  }

  private void triggerJenkins(SQueuedBuild queued, TriggerAttemptContext attempt) throws IOException {
    BuildPromotion promotion = queued.getBuildPromotion();
    attempt.setPromotionId(promotion.getId());
    if (shouldSkip(queued) || hasPendingTrigger(queued.getBuildPromotion().getId())) {
      LOG.debug(TeamCityNodeLog.currentNode(teamCityNodes)
          + " [Jenkins Bridge DEBUG] Skipping TeamCity promotion "
          + queued.getBuildPromotion().getId()
          + " because it is not bridge-triggerable or already pending");
      return;
    }

    SBuildType buildType = promotion.getBuildType();
    if (buildType == null) {
      return;
    }

    SBuildFeatureDescriptor descriptor = bridgeFeatureOrNull(buildType);
    if (descriptor == null) {
      return;
    }

    String job = descriptor.getParameters().get(BridgeBuildFeatureConstants.PARAM_JENKINS_JOB);
    if (job == null || job.trim().isEmpty()) {
      return;
    }

    // The Jenkins server is whichever connection this build configuration mirrors from.
    attempt.markCleanupEligible();
    JenkinsClient jenkinsClient = jenkinsClientFactory.forBuildType(buildType);

    if (hasPendingTrigger(promotion.getId())) {
      return;
    }
    String controller = jenkinsClient.getControllerIdentity();
    // Persist an unresolved intent before POST. If TeamCity dies around the request boundary,
    // startup can find this record and either bind the accepted Jenkins run or fail the TeamCity
    // promotion with an explicit uncertain-trigger reason.
    PendingTrigger provisional = new PendingTrigger(
        promotion.getId(), job, buildType.getExternalId(), "", -1L, controller, now());
    mirrorStore.savePendingTrigger(provisional);
    LOG.debug(TeamCityNodeLog.currentNode(teamCityNodes)
        + " [Jenkins Bridge DEBUG] Saved provisional Jenkins trigger for TeamCity promotion "
        + promotion.getId());

    JenkinsParameterSynchronizer.SynchronizationResult paramSynchronisationResult;
    try {
      paramSynchronisationResult = parameterSynchronizer.synchronize(jenkinsClient, buildType, job);
    } catch (BridgeHttpException | JenkinsDataException e) {
      failTeamCityFirstAttempt(
          queued,
          promotion.getId(),
          "Jenkins Bridge did not call Jenkins because Jenkins parameters could not be loaded. "
              + "See this failed TeamCity build for details.",
          e);
      return;
    }
    // Jenkins REST was read above, but the Jenkins build-trigger POST has not started yet.
    if (paramSynchronisationResult.isTeamCityChanged()) {
      buildType.schedulePersisting(
          "Jenkins Bridge: update Jenkins Run Custom Build parameters")
          .awaitUninterruptibly();
    }

    JenkinsJobParameters parameterDefinitions = paramSynchronisationResult.getDefinitions();
    if (paramSynchronisationResult.isDefinitionChanged()) {
      failTeamCityFirstAttempt(
          queued,
          promotion.getId(),
          parameterDefinitionsChangedReason(paramSynchronisationResult.getPreviousSnapshot(), parameterDefinitions),
          null);
      return;
    }

    JenkinsTriggerResponse trigger;
    try {
      Map<String, String> parameters = jenkinsParameters(parameterDefinitions, promotion);
      String invalidChoiceValues = JenkinsTeamCityRunParameterFactory.invalidChoiceValues(
          parameterDefinitions, parameters);
      if (!invalidChoiceValues.isEmpty()) {
        failTeamCityFirstAttempt(
            queued,
            promotion.getId(),
            "Jenkins Bridge did not start the build because submitted parameter values are no longer valid: "
                + invalidChoiceValues,
            null);
        return;
      }
      attempt.markJenkinsRequestStarted();
      LOG.debug(TeamCityNodeLog.currentNode(teamCityNodes)
          + " [Jenkins Bridge DEBUG] Sending Jenkins trigger request for TeamCity promotion "
          + promotion.getId());
      trigger = jenkinsClient.triggerBuildWithQueueId(job, parameters);
    } catch (BridgeHttpException | JenkinsDataException e) {
      failTeamCityFirstAttempt(
          queued,
          promotion.getId(),
          "Jenkins Bridge called Jenkins but did not receive a confirmed trigger response. "
              + "This build is failed because the result is uncertain; check Jenkins for an accepted build.",
          e);
      return;
    }

    if (!trigger.hasQueueId() || trigger.getQueueItemUrl().trim().isEmpty()) {
      failTeamCityFirstAttempt(
          queued,
          promotion.getId(),
          "Jenkins Bridge called Jenkins but could not correlate the trigger response. "
              + "This build is failed because the result is uncertain; check Jenkins for an accepted build.",
          null);
      return;
    }

    PendingTrigger pendingTrigger = new PendingTrigger(
        promotion.getId(),
        job,
        buildType.getExternalId(),
        trigger.getQueueItemUrl(),
        trigger.getQueueId(),
        controller,
        now());
    mirrorStore.savePendingTrigger(pendingTrigger);
    LOG.info(TeamCityNodeLog.currentNode(teamCityNodes) + " Jenkins Bridge triggered " + job
        + " from TeamCity promotion " + promotion.getId()
        + " via Jenkins queue item " + trigger.getQueueItemUrl());
  }

  private String parameterDefinitionsChangedReason(String previousSnapshot,
                                                   JenkinsJobParameters currentDefinitions) {
    return "Jenkins Bridge did not start the build because Jenkins parameter definitions changed. "
        + "The TeamCity parameters were refreshed; run the build again.\n"
        + "Before: " + (previousSnapshot == null ? "<not recorded>" : previousSnapshot) + "\n"
        + "After: " + JenkinsTeamCityRunParameterFactory.describe(currentDefinitions);
  }

  private void cleanupBeforeJenkinsRequestIfNeeded(
      SQueuedBuild queued,
      TriggerAttemptContext attempt,
      Throwable originalFailure
  ) {
    if (!attempt.isCleanupEligible() || attempt.isJenkinsRequestStarted()) {
      return;
    }

    long promotionId = attempt.getPromotionId();
    boolean teamCityBuildFailed = false;
    try {
      failureService.failQueuedPromotion(promotionId,
          "Jenkins Bridge did not call Jenkins because it could not prepare or persist the trigger. "
              + "See the TeamCity build problem for details.");
      teamCityBuildFailed = true;
    } catch (RuntimeException cleanupFailure) {
      originalFailure.addSuppressed(cleanupFailure);
      LOG.error("Jenkins Bridge could not fail the TeamCity promotion " + promotionId, cleanupFailure);
    }

    if (teamCityBuildFailed) {
      try {
        mirrorStore.removePendingTrigger(promotionId);
      } catch (IOException | RuntimeException cleanupFailure) {
        originalFailure.addSuppressed(cleanupFailure);
        LOG.error(
            "Jenkins Bridge could not remove the provisional pending trigger for TeamCity promotion "
                + promotionId,
            cleanupFailure);
      }
    }
  }

  /**
   * @throws IOException when persisted pending-trigger state cannot be loaded safely.
   */
  private boolean hasPendingTrigger(long promotionId) throws IOException {
    for (PendingTrigger pendingTrigger : mirrorStore.getPendingTriggers()) {
      if (pendingTrigger.getTeamCityPromotionId() == promotionId) {
        return true;
      }
    }
    return false;
  }

  private boolean shouldSkip(SQueuedBuild queued) {
    BuildPromotion promotion = queued.getBuildPromotion();
    Map<String, String> parameters = promotion.getCustomParameters();
    if (parameters.containsKey(BridgeBuildFeatureConstants.JENKINS_BUILD_KEY_PARAM)) {
      return true;
    }

    String requestor = queued.getRequestor();
    if (TRIGGERED_BY_BRIDGE.equals(requestor)) {
      return true;
    }

    return queued.getTriggeredBy() != null
        && TRIGGERED_BY_BRIDGE.equals(queued.getTriggeredBy().getRawTriggeredBy());
  }

  @Nullable
  private SBuildFeatureDescriptor bridgeFeatureOrNull(SBuildType buildType) {
    for (SBuildFeatureDescriptor descriptor
        : buildType.getBuildFeaturesOfType(BridgeBuildFeatureConstants.TYPE)) {
      return descriptor;
    }
    return null;
  }

  private Map<String, String> jenkinsParameters(
      JenkinsJobParameters parameterDefinitions,
      BuildPromotion promotion
  ) {
    Map<String, String> result = new LinkedHashMap<String, String>();
    result.putAll(promotion.getDefaultParameters());
    result.putAll(promotion.getCustomParameters());
    result.remove(BridgeBuildFeatureConstants.JENKINS_BUILD_KEY_PARAM);
    result.remove(TeamCityBuildParameters.AGENTLESS_BUILD_PROPERTY);
    return JenkinsParameterPayloadBuilder.build(parameterDefinitions, result);
  }

  /**
   * Fails the TeamCity build after a trigger attempt cannot be completed. The promotion is kept as
   * a real build so the UI clearly records whether Jenkins was called and whether the response was
   * confirmed.
   */
  private void failTeamCityFirstAttempt(
      SQueuedBuild queued,
      long promotionId,
      String reason,
      @Nullable Exception cause
  ) throws IOException {
    failureService.failQueuedPromotion(promotionId, reason);
    mirrorStore.removePendingTrigger(promotionId);
    if (cause == null) {
      LOG.warn(reason);
    } else {
      LOG.warn(reason, cause);
    }
  }

  private static String now() {
    return Instant.now().toString();
  }

  private static class TriggerAttemptContext {
    private long promotionId = -1L;
    private boolean cleanupEligible;
    private boolean jenkinsRequestStarted;

    private void setPromotionId(long promotionId) {
      this.promotionId = promotionId;
    }

    private long getPromotionId() {
      return promotionId;
    }

    private void markCleanupEligible() {
      cleanupEligible = true;
    }

    private boolean isCleanupEligible() {
      return cleanupEligible;
    }

    private void markJenkinsRequestStarted() {
      jenkinsRequestStarted = true;
    }

    private boolean isJenkinsRequestStarted() {
      return jenkinsRequestStarted;
    }
  }
}
