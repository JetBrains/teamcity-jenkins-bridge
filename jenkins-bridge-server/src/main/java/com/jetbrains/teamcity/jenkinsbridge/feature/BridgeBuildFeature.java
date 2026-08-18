package com.jetbrains.teamcity.jenkinsbridge.feature;

import jetbrains.buildServer.serverSide.BuildFeature;
import jetbrains.buildServer.serverSide.InvalidProperty;
import jetbrains.buildServer.serverSide.PropertiesProcessor;
import jetbrains.buildServer.web.openapi.PluginDescriptor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * TeamCity build feature that marks a build configuration as a Jenkins Bridge mirror target. The
 * feature names the Jenkins connection to mirror from and the Jenkins job path. The hosting build
 * configuration is the mirror target. Discovered by {@link MirroredJobProvider}.
 */
public class BridgeBuildFeature extends BuildFeature {
  private final String editUrl;

  public BridgeBuildFeature(PluginDescriptor pluginDescriptor) {
    this.editUrl = pluginDescriptor.getPluginResourcesPath(
        BridgeBuildFeatureController.EDIT_PARAMS_RELATIVE_URL);
  }

  @Override
  public String getType() {
    return BridgeBuildFeatureConstants.TYPE;
  }

  @Override
  public String getDisplayName() {
    return "Jenkins Bridge";
  }

  @Override
  public String getEditParametersUrl() {
    return editUrl;
  }

  @Override
  public boolean isMultipleFeaturesPerBuildTypeAllowed() {
    return false;
  }

  @Override
  public boolean isRequiresAgent() {
    // The mirror runs server-side as an agentless build; it never needs a TeamCity agent.
    return false;
  }

  @Override
  public PlaceToShow getPlaceToShow() {
    return PlaceToShow.GENERAL;
  }

  @Override
  public String describeParameters(Map<String, String> params) {
    String job = params.get(BridgeBuildFeatureConstants.PARAM_JENKINS_JOB);
    return isBlank(job) ? "Mirrors a Jenkins job" : "Mirrors Jenkins job '" + job.trim() + "'";
  }

  @Override
  public PropertiesProcessor getParametersProcessor() {
    return props -> {
      List<InvalidProperty> errors = new ArrayList<InvalidProperty>();
      if (isBlank(props.get(BridgeBuildFeatureConstants.PARAM_CONNECTION_ID))) {
        errors.add(new InvalidProperty(BridgeBuildFeatureConstants.PARAM_CONNECTION_ID,
            "A Jenkins connection is required"));
      }
      if (isBlank(props.get(BridgeBuildFeatureConstants.PARAM_JENKINS_JOB))) {
        errors.add(new InvalidProperty(BridgeBuildFeatureConstants.PARAM_JENKINS_JOB,
            "Jenkins job path is required"));
      }
      String limit = props.get(BridgeBuildFeatureConstants.PARAM_RECENT_LIMIT);
      if (!isBlank(limit) && !isNonNegativeInt(limit)) {
        errors.add(new InvalidProperty(BridgeBuildFeatureConstants.PARAM_RECENT_LIMIT,
            "The number of builds to import must be zero or a positive whole number"));
      }
      return errors;
    };
  }

  private static boolean isBlank(String value) {
    return value == null || value.trim().length() == 0;
  }

  private static boolean isNonNegativeInt(String value) {
    try {
      return Integer.parseInt(value.trim()) >= 0;
    } catch (NumberFormatException e) {
      return false;
    }
  }
}
