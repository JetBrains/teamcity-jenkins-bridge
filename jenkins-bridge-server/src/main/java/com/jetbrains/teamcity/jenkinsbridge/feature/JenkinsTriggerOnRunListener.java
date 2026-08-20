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
import jetbrains.buildServer.serverSide.BuildPromotion;
import jetbrains.buildServer.serverSide.BuildServerAdapter;
import jetbrains.buildServer.serverSide.BuildServerListener;
import jetbrains.buildServer.serverSide.SBuildFeatureDescriptor;
import jetbrains.buildServer.serverSide.SBuildType;
import jetbrains.buildServer.serverSide.SQueuedBuild;
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
  private final BuildServerListener listener = new BuildServerAdapter() {
    @Override
    public void buildTypeAddedToQueue(SQueuedBuild queued) {
      triggerJenkinsSafely(queued);
    }
  };

  public JenkinsTriggerOnRunListener(
      @NotNull EventDispatcher<BuildServerListener> eventDispatcher,
      @NotNull JenkinsClientFactory jenkinsClientFactory,
      @NotNull BuildMirrorStore mirrorStore
  ) {
    this.eventDispatcher = eventDispatcher;
    this.jenkinsClientFactory = jenkinsClientFactory;
    this.mirrorStore = mirrorStore;
    this.eventDispatcher.addListener(listener);
  }

  public void dispose() {
    eventDispatcher.removeListener(listener);
  }

  private void triggerJenkinsSafely(SQueuedBuild queued) {
    try {
      triggerJenkins(queued);
    } catch (IOException e) {
      LOG.error("Jenkins Bridge could not read or persist trigger state for a TeamCity queued build", e);
    } catch (RuntimeException e) {
      // Listener isolation only. An unexpected bridge defect is not a known Jenkins trigger
      // failure, so do not run the trigger-failure cleanup here.
      LOG.error("Jenkins Bridge failed to process a TeamCity queued build", e);
    }
  }

  private void triggerJenkins(SQueuedBuild queued) throws IOException {
    if (shouldSkip(queued) || hasPendingTrigger(queued.getBuildPromotion().getId())) {
      return;
    }

    BuildPromotion promotion = queued.getBuildPromotion();
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
    JenkinsClient jenkinsClient = jenkinsClientFactory.forBuildType(buildType);

    if (hasPendingTrigger(promotion.getId())) {
      return;
    }
    String controller = jenkinsClient.getControllerIdentity();
    // Persist an unresolved intent before POST. If TeamCity dies after Jenkins accepts the
    // request but before the queue id can be saved, startup will find this record and cancel the
    // original TeamCity promotion; Jenkins discovery will then import the accepted run normally.
    PendingTrigger provisional = new PendingTrigger(
        promotion.getId(), job, buildType.getExternalId(), "", -1L, controller, now());
    mirrorStore.savePendingTrigger(provisional);

    JenkinsJobParameters parameterDefinitions;
    try {
      parameterDefinitions = jenkinsClient.getJobParameters(job);
    } catch (BridgeHttpException | JenkinsDataException e) {
      abandonTeamCityFirstAttempt(
          queued,
          promotion.getId(),
          "Jenkins Bridge could not load Jenkins parameters; abandoning the TeamCity-first trigger",
          e);
      return;
    }

    JenkinsTriggerResponse trigger;
    try {
      Map<String, String> parameters = jenkinsParameters(parameterDefinitions, promotion);
      trigger = jenkinsClient.triggerBuildWithQueueId(job, parameters);
    } catch (BridgeHttpException | JenkinsDataException e) {
      abandonTeamCityFirstAttempt(
          queued,
          promotion.getId(),
          "Jenkins Bridge could not confirm the Jenkins trigger; regular polling will discover any accepted build",
          e);
      return;
    }

    if (trigger.getQueueId() < 0 || trigger.getQueueItemUrl().trim().isEmpty()) {
      abandonTeamCityFirstAttempt(
          queued,
          promotion.getId(),
          "Jenkins Bridge could not correlate the Jenkins trigger; regular polling will discover any accepted build",
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
    LOG.info("Jenkins Bridge triggered " + job + " from TeamCity promotion " + promotion.getId()
        + " via Jenkins queue item " + trigger.getQueueItemUrl());
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
   * Stops the TeamCity-first attempt after it cannot be correlated to a Jenkins queue item. Any
   * Jenkins build that may have been accepted is intentionally left for the normal Jenkins poller
   * to discover and mirror.
   */
  private void abandonTeamCityFirstAttempt(
      SQueuedBuild queued,
      long promotionId,
      String reason,
      @Nullable Exception cause
  ) throws IOException {
    queued.removeFromQueue(null, reason);
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
}
