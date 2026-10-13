package com.jetbrains.teamcity.jenkinsbridge.teamcity;

import com.jetbrains.teamcity.jenkinsbridge.artifactstorage.JenkinsStorageAutomaticActivator;
import com.jetbrains.teamcity.jenkinsbridge.jenkins.JenkinsClientFactory;
import com.jetbrains.teamcity.jenkinsbridge.model.JenkinsVcsInfo;
import com.jetbrains.teamcity.jenkinsbridge.model.JenkinsVcsRepository;
import com.jetbrains.teamcity.jenkinsbridge.util.Utilities;
import com.jetbrains.teamcity.jenkinsbridge.vcs.VcsBuildCustomization;
import com.jetbrains.teamcity.jenkinsbridge.vcs.VcsRefType;
import com.jetbrains.teamcity.jenkinsbridge.model.JenkinsScmHeadInfo;
import com.intellij.openapi.diagnostic.Logger;
import jetbrains.buildServer.serverSide.*;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static com.jetbrains.teamcity.jenkinsbridge.util.Utilities.findBuildType;

public class TeamCityBuildQueuer {
  private static final String TRIGGERED_BY = "Jenkins Bridge";

  private static final Logger LOG = Logger.getInstance(TeamCityBuildQueuer.class.getName());
  private final TeamCityBranchResolver branchResolver;
  private final ProjectManager projectManager;
  private final BuildCustomizerFactory buildCustomizerFactory;
  private final JenkinsClientFactory jenkinsClientFactory;
  private final JenkinsStorageAutomaticActivator storageActivator;

  public TeamCityBuildQueuer(
      ProjectManager projectManager,
      BuildCustomizerFactory buildCustomizerFactory,
      JenkinsClientFactory jenkinsClientFactory,
      JenkinsStorageAutomaticActivator storageActivator,
      TeamCityBranchResolver branchResolver
  ) {
    this.branchResolver = branchResolver;
    this.projectManager = projectManager;
    this.buildCustomizerFactory = buildCustomizerFactory;
    this.jenkinsClientFactory = jenkinsClientFactory;
    this.storageActivator = storageActivator;
  }

  public long queueAgentlessBuild(
      String buildTypeId,
      Map<String, String> properties,
      Map<String, String> jenkinsBuildParameters,
      @Nullable JenkinsVcsInfo vcsInfo
  ) throws TeamCityBuildQueueException {
    return queueAgentlessBuild(buildTypeId, properties, jenkinsBuildParameters, vcsInfo, null);
  }

  public long queueAgentlessBuild(
      String buildTypeId,
      Map<String, String> properties,
      Map<String, String> jenkinsBuildParameters,
      @Nullable JenkinsVcsInfo vcsInfo,
      @Nullable VcsBuildCustomization vcsCustomization
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

    TeamCityVcsFailurePolicy.configure(parameters);

    BuildCustomizer customizer = buildCustomizerFactory.createBuildCustomizer(buildType, null);
    customizer.setParameters(parameters);
    if (branchResolution.branchName() != null && customizer instanceof BuildCustomizerEx customizerEx) {
      try {
        customizerEx.setDesiredBranchName(branchResolution.branchName(), false);
      } catch (RuntimeException e) {
        LOG.warn("Jenkins Bridge: optional desired branch assignment failed", e);
      }
    }
    applyVcsCustomization(customizer, vcsCustomization);

    BuildPromotion promotion = customizer.createPromotion();
    if (storageActivator != null) {
      storageActivator.configurePromotionStorage(
          promotion, buildType.getProject().getExternalId());
    }
    SQueuedBuild queuedBuild = promotion.addToQueue(TRIGGERED_BY);
    if (queuedBuild == null) {
      throw new TeamCityBuildQueueException(
          "Failed to add TeamCity build type " + buildTypeId + " to the queue");
    }

    return queuedBuild.getBuildPromotion().getId();
  }

  private void applyVcsCustomization(
      BuildCustomizer customizer,
      @Nullable VcsBuildCustomization vcsCustomization
  ) {
    if (vcsCustomization == null || !(customizer instanceof BuildCustomizerEx customizerEx)) {
      return;
    }
    if (vcsCustomization.hasRevisions()) {
      try {
        customizerEx.setProvidedUpperLimitRevisions(vcsCustomization.upperLimitRevisions());
      } catch (RuntimeException e) {
        vcsCustomization.result().addError("Pre-queue revision pinning failed: " + Utilities.describeException(e));
        LOG.warn("Jenkins Bridge: optional pre-queue revision pinning failed", e);
      }
    }
  }

  /**
   * Determines the branch to pin the build to before the promotion is queued, so TeamCity finalizes
   * the immutable branch name correctly, and any pull/merge request parameters to publish alongside it.
   */
  private BranchResolution resolveBranch(SBuildType buildType, Map<String, String> properties,
                                          @Nullable JenkinsVcsInfo vcsInfo) {
    String branchName = null;
    Map<String, String> pullRequestParameters = Collections.emptyMap();
    try {
      if (Utilities.isBuildConfigMultibranch(buildType)) {
        String job = properties.get("jenkins.job");
        if (job == null || job.isBlank()) return new BranchResolution(null, pullRequestParameters);
        var client = jenkinsClientFactory.forBuildType(buildType);
        var snapshot = jenkinsClientFactory.getMultibranchScmSnapshot(buildType);
        var child = snapshot.flatMap(data -> data.child(job));
        var scm = child.map(c -> c.scmInfo());
        if (scm.isEmpty()) scm = client.getBranchScmInfo(job);
        if (scm.isPresent()) {
          JenkinsScmHeadInfo head = scm.get();
          String name = head.headName();
          if (Utilities.looksLikePullOrMergeRequestBranch(name) || child.map(c -> c.changeRequest()).orElse(false)) {
            var pr = client.getPullRequestInfo(job);
            if (pr.isEmpty()) return new BranchResolution(null, pullRequestParameters);
            name = pr.get().sourceBranch();
            pullRequestParameters = TeamCityBuildParameters.pullRequestParameters(pr.get());
          }
          String ref = (head.refType() == VcsRefType.TAGS ? "refs/tags/" : "refs/heads/") + name;
          branchName = branchResolver.resolve(buildType, ref, head.remoteUrl()).branchName();
        }
      } else if (vcsInfo != null) {
        for (JenkinsVcsRepository repo : vcsInfo.repositories()) {
          TeamCityBranch branch = TeamCityBranch.fromJenkinsGit(repo.rawBranchName());
          if (!branch.isDefault()) {
            branchName = branchResolver.resolve(buildType, branch.ref(), repo.remoteUrl()).branchName();
            break;
          }
        }
      }
    } catch (Exception e) {
      LOG.warn("Jenkins Bridge: optional queue branch mapping failed for " + buildType.getExternalId(), e);
    }
    return new BranchResolution(branchName, pullRequestParameters);
  }

  private record BranchResolution(@Nullable String branchName, Map<String, String> pullRequestParameters) {
  }
}
