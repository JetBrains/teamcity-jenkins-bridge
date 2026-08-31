package com.jetbrains.teamcity.jenkinsbridge.teamcity;

import com.jetbrains.teamcity.jenkinsbridge.feature.BridgeBuildFeatureConstants;
import com.jetbrains.teamcity.jenkinsbridge.artifactstorage.JenkinsStorageAutomaticActivator;
import com.jetbrains.teamcity.jenkinsbridge.jenkins.JenkinsClientFactory;
import com.jetbrains.teamcity.jenkinsbridge.model.JenkinsVcsInfo;
import com.jetbrains.teamcity.jenkinsbridge.model.JenkinsVcsRepository;
import com.jetbrains.teamcity.jenkinsbridge.util.Utilities;
import jetbrains.buildServer.serverSide.*;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static com.jetbrains.teamcity.jenkinsbridge.util.Utilities.decodeBranchFragment;
import static com.jetbrains.teamcity.jenkinsbridge.util.Utilities.findBuildType;
import static com.jetbrains.teamcity.jenkinsbridge.util.Utilities.lastPathSegment;

public class TeamCityBuildQueuer {
  private static final String TRIGGERED_BY = "Jenkins Bridge";

  private final ProjectManager projectManager;
  private final BuildCustomizerFactory buildCustomizerFactory;
  private final JenkinsClientFactory jenkinsClientFactory;
  private final JenkinsStorageAutomaticActivator storageActivator;

  public TeamCityBuildQueuer(
      ProjectManager projectManager,
      BuildCustomizerFactory buildCustomizerFactory,
      JenkinsClientFactory jenkinsClientFactory,
      com.jetbrains.teamcity.jenkinsbridge.artifactstorage.JenkinsStorageAutomaticActivator storageActivator
  ) {
    this.projectManager = projectManager;
    this.buildCustomizerFactory = buildCustomizerFactory;
    this.jenkinsClientFactory = jenkinsClientFactory;
    this.storageActivator = storageActivator;
  }

  /** Retained for isolated tests that do not exercise artifact storage. */
  public TeamCityBuildQueuer(
      ProjectManager projectManager,
      BuildCustomizerFactory buildCustomizerFactory,
      JenkinsClientFactory jenkinsClientFactory
  ) {
    this(projectManager, buildCustomizerFactory, jenkinsClientFactory, null);
  }

  public long queueAgentlessBuild(
      String buildTypeId,
      Map<String, String> properties,
      Map<String, String> jenkinsBuildParameters,
      @Nullable JenkinsVcsInfo vcsInfo
  ) throws TeamCityBuildQueueException {
    SBuildType buildType = findBuildType(buildTypeId, projectManager);
    if (buildType == null) {
      throw new TeamCityBuildQueueException("TeamCity build type " + buildTypeId + " was not found");
    }

    BranchResolution branchResolution = resolveBranch(buildType, properties, vcsInfo);

    Map<String, String> bridgeParameters = new LinkedHashMap<>(properties);
    bridgeParameters.putAll(branchResolution.pullRequestParameters());

    Map<String, String> parameters = new LinkedHashMap<>();

    // TODO: Replace the line below with parameters.put(BuildPromotionImpl.ALLOW_RESETTING_ACTIVE_REVISIONS, "true"); once merged in core
    parameters.put("teamcity.internal.build.resetRevisions.allow", "true");

    parameters.put(TeamCityBuildParameters.AGENTLESS_BUILD_PROPERTY, "true");
    parameters.putAll(TeamCityBuildParameters.mergeWithJenkinsParameters(
        bridgeParameters,
        jenkinsBuildParameters));

    BuildCustomizer customizer = buildCustomizerFactory.createBuildCustomizer(buildType, null);
    customizer.setParameters(parameters);
    if (branchResolution.branchName() != null && customizer instanceof BuildCustomizerEx customizerEx) {
      customizerEx.setDesiredBranchName(branchResolution.branchName(), false);
    }

    BuildPromotion promotion = customizer.createPromotion();
    if (storageActivator != null && !(promotion instanceof BuildPromotionEx)) {
      throw new TeamCityBuildQueueException("Jenkins mirror promotion does not support build attributes");
    }
    if (storageActivator != null) {
      String storageId = storageActivator.ensureJenkinsStorage(buildType.getProject().getExternalId());
      if (storageId == null || storageId.isEmpty()) {
        throw new TeamCityBuildQueueException("Could not create Jenkins artifact storage definition");
      }
      ((BuildPromotionEx) promotion).setAttribute(BuildAttributes.STORAGE_SETTINGS_REFERENCE, storageId);
    }
    SQueuedBuild queuedBuild = promotion.addToQueue(TRIGGERED_BY);
    if (queuedBuild == null) {
      throw new TeamCityBuildQueueException(
          "Failed to add TeamCity build type " + buildTypeId + " to the queue");
    }

    return queuedBuild.getBuildPromotion().getId();
  }

  /**
   * Determines the branch to pin the build to before the promotion is queued, so TeamCity finalizes
   * the immutable branch name correctly, and any pull/merge request parameters to publish alongside it.
   */
  private BranchResolution resolveBranch(SBuildType buildType, Map<String, String> properties,
                                          @Nullable JenkinsVcsInfo vcsInfo) {
    String branchName = null;
    Map<String, String> pullRequestParameters = Collections.emptyMap();
    if (Utilities.isBuildConfigMultibranch(buildType)) {
      // Check the name of the nested branch job for the branch name
      String job = properties.get("jenkins.job");
      if (job == null || job.trim().isEmpty()) {
        SBuildFeatureDescriptor feature = buildType.getBuildFeaturesOfType(BridgeBuildFeatureConstants.TYPE).stream().findFirst().orElse(null);
        job = feature != null ? feature.getParameters().get(BridgeBuildFeatureConstants.PARAM_JENKINS_JOB) : null;
      }
      if (job != null && !job.trim().isEmpty()) {
        job = job.trim();
        branchName = decodeBranchFragment(lastPathSegment(job));
        if (Utilities.looksLikePullOrMergeRequestBranch(branchName)) {
          var pullRequestInfoResult = jenkinsClientFactory.forBuildType(buildType).getPullRequestInfo(job);
          if (pullRequestInfoResult.isPresent()) {
            var pullRequestInfo = pullRequestInfoResult.get();
            branchName = pullRequestInfo.sourceBranch();
            pullRequestParameters = TeamCityBuildParameters.pullRequestParameters(pullRequestInfo);
          }
        }
      }
    } else if (vcsInfo != null) {
      for (JenkinsVcsRepository repo : vcsInfo.repositories()) {
        TeamCityBranch branch = TeamCityBranch.fromJenkinsGit(repo.rawBranchName());
        if (!branch.isDefault()) {
          branchName = branch.displayName();
        }
      }
    }
    return new BranchResolution(branchName, pullRequestParameters);
  }

  private record BranchResolution(@Nullable String branchName, Map<String, String> pullRequestParameters) {
  }
}
