package com.jetbrains.teamcity.jenkinsbridge.web;

import com.jetbrains.teamcity.jenkinsbridge.model.JenkinsPipelineGraph;
import com.jetbrains.teamcity.jenkinsbridge.persistence.BuildMirror;
import com.jetbrains.teamcity.jenkinsbridge.persistence.BuildMirrorStore;
import jetbrains.buildServer.serverSide.SBuild;
import jetbrains.buildServer.serverSide.SBuildServer;
import jetbrains.buildServer.web.openapi.PagePlaces;
import jetbrains.buildServer.web.openapi.PluginDescriptor;
import jetbrains.buildServer.web.openapi.ViewLogTab;

import javax.servlet.http.HttpServletRequest;
import java.util.Map;

/**
 * "Pipeline Graph" tab on a build's results page. Shown only for mirrored Jenkins Pipeline builds that
 * have a persisted graph. The JSP polls {@link JenkinsPipelineGraphController} and renders the Blue
 * Ocean graph (stages + parallel branches) live, mirroring what the user sees in Jenkins.
 */
public class JenkinsPipelineGraphTab extends ViewLogTab {
  private final PluginDescriptor pluginDescriptor;
  private final BuildMirrorStore mirrorStore;

  public JenkinsPipelineGraphTab(
      PagePlaces pagePlaces,
      SBuildServer server,
      PluginDescriptor pluginDescriptor,
      BuildMirrorStore mirrorStore
  ) {
    super("Pipeline Graph", "jenkinsBridgePipelineGraph", pagePlaces, server);
    this.pluginDescriptor = pluginDescriptor;
    this.mirrorStore = mirrorStore;
    setPluginName(pluginDescriptor.getPluginName());
    setIncludeUrl(pluginDescriptor.getPluginResourcesPath("jenkinsBridgePipelineGraph.jsp"));
    register();
  }

  @Override
  protected boolean isAvailable(HttpServletRequest request, SBuild build) {
    return hasPipelineGraph(build);
  }

  @Override
  protected void fillModel(Map<String, Object> model, HttpServletRequest request, SBuild build) {
    // Prefix the context path so the AJAX URL is correct under a non-root TeamCity context (e.g. /bs).
    model.put("controllerUrl", request.getContextPath() + JenkinsPipelineGraphController.PATH);
    model.put("buildId", build.getBuildId());
  }

  private boolean hasPipelineGraph(SBuild build) {
    try {
      String key = build.getParametersProvider().get(JenkinsPipelineGraphController.JENKINS_BUILD_KEY_PARAM);
      if (key == null || key.trim().length() == 0) {
        return false;
      }
      BuildMirror mirror = mirrorStore.findMirror(key);
      JenkinsPipelineGraph graph = mirror == null ? null : mirror.getPipelineGraph();
      return graph != null && graph.isPipeline() && !graph.getNodes().isEmpty();
    } catch (Exception e) {
      // Never let a tab-availability check break the build results page.
      return false;
    }
  }
}
