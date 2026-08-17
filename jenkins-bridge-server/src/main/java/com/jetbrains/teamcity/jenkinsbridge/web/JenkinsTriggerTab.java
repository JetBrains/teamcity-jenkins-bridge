package com.jetbrains.teamcity.jenkinsbridge.web;

import com.google.gson.Gson;
import com.jetbrains.teamcity.jenkinsbridge.feature.BridgeBuildFeatureConstants;
import jetbrains.buildServer.serverSide.ProjectManager;
import jetbrains.buildServer.serverSide.SBuildFeatureDescriptor;
import jetbrains.buildServer.serverSide.SBuildType;
import jetbrains.buildServer.users.SUser;
import jetbrains.buildServer.web.openapi.PluginDescriptor;
import jetbrains.buildServer.web.openapi.WebControllerManager;
import jetbrains.buildServer.web.openapi.buildType.BuildTypeTab;

import javax.servlet.http.HttpServletRequest;
import java.util.Map;

/**
 * "Trigger Jenkins" tab on a build configuration. Shown only for build configs that carry the
 * Jenkins Bridge feature. The JSP makes AJAX calls to {@link JenkinsTriggerController} to load the
 * mapped Jenkins job's parameters and to trigger a build.
 */
public class JenkinsTriggerTab extends BuildTypeTab {
  private static final Gson GSON = new Gson();
  public JenkinsTriggerTab(
      WebControllerManager webControllerManager,
      ProjectManager projectManager,
      PluginDescriptor pluginDescriptor
  ) {
    super("jenkinsBridgeTrigger", "Trigger Jenkins", webControllerManager, projectManager);
    setIncludeUrl(pluginDescriptor.getPluginResourcesPath("jenkinsBridgeTrigger.jsp"));
    setPluginName(pluginDescriptor.getPluginName());
    register();
  }

  @Override
  public boolean isAvailable(HttpServletRequest request) {
    SBuildType buildType = getBuildType(request);
    return buildType != null
        && !buildType.getBuildFeaturesOfType(BridgeBuildFeatureConstants.TYPE).isEmpty();
  }

  @Override
  protected void fillModel(Map<String, Object> model, HttpServletRequest request,
                           SBuildType buildType, SUser user) {
    // Prefix the context path so the AJAX URL is correct under a non-root TeamCity context (e.g. /bs).
    // Gson's default HTML escaping protects the surrounding script element as well as the JS
    // string literal (notably for </script>, quotes, backslashes, and Unicode separators).
    model.put("controllerUrlJson", GSON.toJson(request.getContextPath() + JenkinsTriggerController.PATH));
    model.put("buildTypeExternalIdJson", GSON.toJson(buildType.getExternalId()));
    model.put("jenkinsJobJson", GSON.toJson(mappedJenkinsJob(buildType)));
  }

  private String mappedJenkinsJob(SBuildType buildType) {
    for (SBuildFeatureDescriptor descriptor
        : buildType.getBuildFeaturesOfType(BridgeBuildFeatureConstants.TYPE)) {
      String job = descriptor.getParameters().get(BridgeBuildFeatureConstants.PARAM_JENKINS_JOB);
      return job == null ? "" : job;
    }
    return "";
  }
}
