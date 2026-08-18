package com.jetbrains.teamcity.jenkinsbridge.feature;

import com.jetbrains.teamcity.jenkinsbridge.connection.JenkinsConnectionResolver;
import jetbrains.buildServer.controllers.BaseController;
import jetbrains.buildServer.web.openapi.PluginDescriptor;
import jetbrains.buildServer.web.openapi.WebControllerManager;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.web.servlet.ModelAndView;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * Renders the edit form of the Jenkins Bridge build feature. A controller is needed instead of a
 * plain JSP so the form can reach {@link JenkinsConnectionResolver} and list the Jenkins connections
 * available to the edited project.
 */
public class BridgeBuildFeatureController extends BaseController {
  /** Path under the plugin resources, also used as the feature's edit parameters URL. */
  public static final String EDIT_PARAMS_RELATIVE_URL = "editJenkinsBridge.html";

  @NotNull private final PluginDescriptor myPluginDescriptor;
  @NotNull private final JenkinsConnectionResolver myConnectionResolver;

  public BridgeBuildFeatureController(@NotNull PluginDescriptor pluginDescriptor,
                                      @NotNull JenkinsConnectionResolver connectionResolver,
                                      @NotNull WebControllerManager webControllerManager) {
    myPluginDescriptor = pluginDescriptor;
    myConnectionResolver = connectionResolver;
    webControllerManager.registerController(
        pluginDescriptor.getPluginResourcesPath(EDIT_PARAMS_RELATIVE_URL), this);
  }

  @Nullable
  @Override
  protected ModelAndView doHandle(@NotNull HttpServletRequest request, @NotNull HttpServletResponse response) {
    ModelAndView modelAndView =
        new ModelAndView(myPluginDescriptor.getPluginResourcesPath("editJenkinsBridge.jsp"));
    modelAndView.getModel().put("jenkinsConnections", myConnectionResolver);
    return modelAndView;
  }
}
