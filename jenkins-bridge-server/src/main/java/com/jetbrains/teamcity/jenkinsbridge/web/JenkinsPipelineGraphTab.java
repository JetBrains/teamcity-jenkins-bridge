package com.jetbrains.teamcity.jenkinsbridge.web;

import com.jetbrains.teamcity.jenkinsbridge.model.JenkinsPipelineGraph;
import com.jetbrains.teamcity.jenkinsbridge.persistence.BuildResultMetadata;
import com.jetbrains.teamcity.jenkinsbridge.persistence.BuildResultMetadataResolver;
import jetbrains.buildServer.serverSide.SBuild;
import jetbrains.buildServer.serverSide.SBuildServer;
import jetbrains.buildServer.web.openapi.PagePlaces;
import jetbrains.buildServer.web.openapi.PluginDescriptor;
import jetbrains.buildServer.web.openapi.ViewLogTab;

import javax.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Map;

/**
 * "Pipeline Graph" tab on a build's results page. Shown only for mirrored Jenkins Pipeline builds that
 * have a persisted graph. The JSP polls {@link JenkinsPipelineGraphController} and renders the
 * Jenkins stage hierarchy or explicit graph live.
 */
public class JenkinsPipelineGraphTab extends ViewLogTab {
  private final PluginDescriptor pluginDescriptor;
  private final BuildResultMetadataResolver resultMetadataResolver;

  public JenkinsPipelineGraphTab(
      PagePlaces pagePlaces,
      SBuildServer server,
      PluginDescriptor pluginDescriptor,
      BuildResultMetadataResolver resultMetadataResolver
  ) {
    super("Pipeline Graph", "jenkinsBridgePipelineGraph", pagePlaces, server);
    this.pluginDescriptor = pluginDescriptor;
    this.resultMetadataResolver = resultMetadataResolver;
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
      BuildResultMetadata metadata = resultMetadataResolver.resolve(build);
      JenkinsPipelineGraph graph = metadata == null ? null : metadata.getPipelineGraph();
      return graph != null && graph.isPipeline()
          && (!graph.getNodes().isEmpty()
              || (JenkinsPipelineGraph.SOURCE_PIPELINE_GRAPH_VIEW.equals(graph.getSource())
                  && !graph.isComplete()));
    } catch (IOException e) {
      // Never let a tab-availability check break the build results page.
      return false;
    }
  }
}
