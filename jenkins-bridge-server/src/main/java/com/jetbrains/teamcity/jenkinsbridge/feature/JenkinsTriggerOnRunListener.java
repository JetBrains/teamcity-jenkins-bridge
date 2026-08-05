package com.jetbrains.teamcity.jenkinsbridge.feature;

import com.intellij.openapi.diagnostic.Logger;
import com.jetbrains.teamcity.jenkinsbridge.jenkins.JenkinsClient;
import com.jetbrains.teamcity.jenkinsbridge.jenkins.JenkinsClientFactory;
import com.jetbrains.teamcity.jenkinsbridge.model.JenkinsJobParameters;
import com.jetbrains.teamcity.jenkinsbridge.persistence.BuildMirrorStore;
import com.jetbrains.teamcity.jenkinsbridge.persistence.PendingTrigger;
import com.jetbrains.teamcity.jenkinsbridge.model.JenkinsTriggerResponse;
import com.jetbrains.teamcity.jenkinsbridge.polling.JenkinsJobCoordinator;
import com.jetbrains.teamcity.jenkinsbridge.teamcity.TeamCityBuildParameters;
import jetbrains.buildServer.serverSide.BuildPromotion;
import jetbrains.buildServer.serverSide.BuildQueue;
import jetbrains.buildServer.serverSide.BuildServerAdapter;
import jetbrains.buildServer.serverSide.BuildServerListener;
import jetbrains.buildServer.serverSide.SBuildFeatureDescriptor;
import jetbrains.buildServer.serverSide.SBuildType;
import jetbrains.buildServer.serverSide.SQueuedBuild;
import jetbrains.buildServer.util.EventDispatcher;

import java.io.IOException;
import java.time.Instant;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
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
  private final BuildQueue buildQueue;
  private final JenkinsJobCoordinator jobCoordinator = new JenkinsJobCoordinator();
  private final BuildServerListener listener = new BuildServerAdapter() {
    @Override
    public void buildTypeAddedToQueue(SBuildType buildType) {
      triggerLatestQueuedBuildSafely(buildType);
    }

    @Override
    public void buildTypeAddedToQueue(SQueuedBuild queued) {
      triggerJenkinsSafely(queued);
    }
  };

  public JenkinsTriggerOnRunListener(
      EventDispatcher<BuildServerListener> eventDispatcher,
      JenkinsClientFactory jenkinsClientFactory,
      BuildMirrorStore mirrorStore,
      BuildQueue buildQueue
  ) {
    this.eventDispatcher = eventDispatcher;
    this.jenkinsClientFactory = jenkinsClientFactory;
    this.mirrorStore = mirrorStore;
    this.buildQueue = buildQueue;
    this.eventDispatcher.addListener(listener);
  }

  public void dispose() {
    eventDispatcher.removeListener(listener);
  }

  private void triggerJenkinsSafely(SQueuedBuild queued) {
    try {
      triggerJenkins(queued);
    } catch (Exception e) {
      LOG.warn("Jenkins Bridge failed to process a TeamCity queued build", e);
      removeFromQueueQuietly(queued, "Jenkins trigger failed: " + message(e));
    }
  }

  private void triggerLatestQueuedBuildSafely(SBuildType buildType) {
    try {
      SQueuedBuild queued = latestQueuedBuild(buildType);
      if (queued != null) {
        triggerJenkins(queued);
      }
    } catch (Exception e) {
      LOG.warn("Jenkins Bridge failed to process a TeamCity queued build type", e);
    }
  }

  private void triggerJenkins(SQueuedBuild queued) throws Exception {
    if (queued == null || shouldSkip(queued) || hasPendingTrigger(queued.getBuildPromotion().getId())) {
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

    synchronized (jobCoordinator.lockFor(jenkinsClient.getControllerIdentity(), job)) {
      // The callback can be delivered more than once. Re-check after acquiring the same lock used
      // by discovery so a duplicate callback cannot submit a second Jenkins request.
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

      JenkinsTriggerResponse trigger;
      try {
        JenkinsJobParameters parameterDefinitions = jenkinsClient.getJobParameters(job);
        Map<String, String> parameters = jenkinsParameters(parameterDefinitions, promotion);
        trigger = jenkinsClient.triggerBuildWithQueueId(job, parameters);
      } catch (Exception e) {
        mirrorStore.removePendingTrigger(promotion.getId());
        throw e;
      }
      if (trigger.getQueueId() < 0 || trigger.getQueueItemUrl().trim().isEmpty()) {
        mirrorStore.removePendingTrigger(promotion.getId());
        removeFromQueueQuietly(queued, "Jenkins Bridge could not correlate the Jenkins trigger request");
        throw new IllegalStateException("Jenkins did not return a valid queue item Location");
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
  }

  private SQueuedBuild latestQueuedBuild(SBuildType buildType) {
    if (buildType == null) {
      return null;
    }
    SQueuedBuild latest = null;
    List<SQueuedBuild> queuedBuilds = buildQueue.getItems(buildType.getInternalId());
    for (SQueuedBuild queued : queuedBuilds) {
      if (latest == null || laterThan(queued.getWhenQueued(), latest.getWhenQueued())) {
        latest = queued;
      }
    }
    return latest;
  }

  private boolean laterThan(Date left, Date right) {
    if (right == null) {
      return true;
    }
    return left != null && left.after(right);
  }

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

  private SBuildFeatureDescriptor bridgeFeatureOrNull(SBuildType buildType) {
    for (SBuildFeatureDescriptor descriptor
        : buildType.getBuildFeaturesOfType(BridgeBuildFeatureConstants.TYPE)) {
      return descriptor;
    }
    return null;
  }

  private Map<String, String> jenkinsParameters(JenkinsJobParameters parameterDefinitions, BuildPromotion promotion) {
    Map<String, String> result = new LinkedHashMap<String, String>();
    result.putAll(promotion.getDefaultParameters());
    result.putAll(promotion.getCustomParameters());
    result.remove(BridgeBuildFeatureConstants.JENKINS_BUILD_KEY_PARAM);
    result.remove(TeamCityBuildParameters.AGENTLESS_BUILD_PROPERTY);
    return JenkinsParameterPayloadBuilder.build(parameterDefinitions, result);
  }

  private void removeFromQueueQuietly(SQueuedBuild queued, String comment) {
    try {
      if (queued != null) {
        queued.removeFromQueue(null, comment);
      }
    } catch (Exception removeError) {
      LOG.warn("Jenkins Bridge failed to remove a queued TeamCity build after Jenkins trigger failure", removeError);
    }
  }

  private static String now() {
    return Instant.now().toString();
  }

  private static String message(Exception e) {
    return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
  }
}
