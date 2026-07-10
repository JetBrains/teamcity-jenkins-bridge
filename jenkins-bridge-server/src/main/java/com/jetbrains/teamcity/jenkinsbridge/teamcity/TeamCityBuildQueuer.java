package com.jetbrains.teamcity.jenkinsbridge.teamcity;

import com.jetbrains.teamcity.jenkinsbridge.vcs.VcsBuildCustomization;
import jetbrains.buildServer.serverSide.BuildCustomizer;
import jetbrains.buildServer.serverSide.BuildCustomizerEx;
import jetbrains.buildServer.serverSide.BuildCustomizerFactory;
import jetbrains.buildServer.serverSide.BuildPromotion;
import jetbrains.buildServer.serverSide.ProjectManager;
import jetbrains.buildServer.serverSide.SBuildType;
import jetbrains.buildServer.serverSide.SQueuedBuild;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static com.jetbrains.teamcity.jenkinsbridge.util.Utilities.findBuildType;

public class TeamCityBuildQueuer {
  private static final String TRIGGERED_BY = "Jenkins Bridge";

  private final ProjectManager projectManager;
  private final BuildCustomizerFactory buildCustomizerFactory;

  public TeamCityBuildQueuer(ProjectManager projectManager, BuildCustomizerFactory buildCustomizerFactory) {
    this.projectManager = projectManager;
    this.buildCustomizerFactory = buildCustomizerFactory;
  }

  public long queueAgentlessBuild(String buildTypeId, Map<String, String> properties) {
    return queueAgentlessBuild(buildTypeId, properties, Collections.<String, String>emptyMap());
  }

  public long queueAgentlessBuild(
      String buildTypeId,
      Map<String, String> properties,
      Map<String, String> jenkinsBuildParameters
  ) {
    return queueAgentlessBuild(buildTypeId, properties, jenkinsBuildParameters, null);
  }

  public long queueAgentlessBuild(
      String buildTypeId,
      Map<String, String> properties,
      Map<String, String> jenkinsBuildParameters,
      VcsBuildCustomization vcsCustomization
  ) {
    SBuildType buildType = findBuildType(buildTypeId, projectManager);
    if (buildType == null) {
      throw new IllegalStateException("TeamCity build type " + buildTypeId + " was not found");
    }


    Map<String, String> parameters = new LinkedHashMap<String, String>();
    parameters.put(TeamCityBuildParameters.AGENTLESS_BUILD_PROPERTY, "true");
    parameters.putAll(TeamCityBuildParameters.mergeWithJenkinsParameters(
        properties,
        jenkinsBuildParameters,
        buildType.getParametersProvider().getAll().keySet()));

    BuildCustomizer customizer = buildCustomizerFactory.createBuildCustomizer(buildType, null);
    customizer.setParameters(parameters);
    applyVcsCustomization(customizer, vcsCustomization);

    BuildPromotion promotion = customizer.createPromotion();
    SQueuedBuild queuedBuild = promotion.addToQueue(TRIGGERED_BY);
    if (queuedBuild == null) {
      throw new IllegalStateException("Failed to add TeamCity build type " + buildTypeId + " to the queue");
    }

    return queuedBuild.getBuildPromotion().getId();
  }

  private void applyVcsCustomization(BuildCustomizer customizer, VcsBuildCustomization vcsCustomization) {
    if (vcsCustomization == null || !vcsCustomization.hasRevisions()) {
      return;
    }
    if (!(customizer instanceof BuildCustomizerEx customizerEx)) {
      throw new IllegalStateException("TeamCity BuildCustomizer does not support the VCS revision customization");
    }

    if (vcsCustomization.desiredBranchName() != null) {
      customizerEx.setDesiredBranchName(vcsCustomization.desiredBranchName(), false);
    }
    customizerEx.setProvidedUpperLimitRevisions(vcsCustomization.upperLimitRevisions());
  }
}
