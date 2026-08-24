package com.jetbrains.teamcity.jenkinsbridge.feature;

import com.jetbrains.teamcity.jenkinsbridge.connection.JenkinsConnectionResolver;
import jetbrains.buildServer.controllers.BaseController;
import jetbrains.buildServer.serverSide.ProjectManager;
import jetbrains.buildServer.serverSide.SBuildType;
import jetbrains.buildServer.serverSide.SProject;
import jetbrains.buildServer.serverSide.auth.Permission;
import jetbrains.buildServer.users.SUser;
import jetbrains.buildServer.web.openapi.PluginDescriptor;
import jetbrains.buildServer.web.openapi.WebControllerManager;
import jetbrains.buildServer.web.util.SessionUser;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.web.servlet.ModelAndView;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import static com.jetbrains.teamcity.jenkinsbridge.util.Utilities.findBuildType;
import static com.jetbrains.teamcity.jenkinsbridge.util.ProjectPermissionHelper.hasAnyProjectPermission;
import static com.jetbrains.teamcity.jenkinsbridge.util.ProjectPermissionHelper.hasProjectPermission;

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
  @NotNull private final ProjectManager myProjectManager;

  public BridgeBuildFeatureController(@NotNull PluginDescriptor pluginDescriptor,
                                      @NotNull JenkinsConnectionResolver connectionResolver,
                                      @NotNull WebControllerManager webControllerManager,
                                      @NotNull ProjectManager projectManager) {
    myPluginDescriptor = pluginDescriptor;
    myConnectionResolver = connectionResolver;
    myProjectManager = projectManager;
    webControllerManager.registerController(
        pluginDescriptor.getPluginResourcesPath(EDIT_PARAMS_RELATIVE_URL), this);
  }

  @Nullable
  @Override
  protected ModelAndView doHandle(@NotNull HttpServletRequest request, @NotNull HttpServletResponse response) {
    SProject project = findProject(request);
    if (!checkProjectAccess(request, response, project)) {
      return null;
    }

    ModelAndView modelAndView =
        new ModelAndView(myPluginDescriptor.getPluginResourcesPath("editJenkinsBridge.jsp"));
    modelAndView.getModel().put("jenkinsConnections", myConnectionResolver);
    modelAndView.getModel().put("readOnly",
        !hasProjectPermission(SessionUser.getUser(request), project, Permission.EDIT_PROJECT));
    return modelAndView;
  }

  private boolean checkProjectAccess(@NotNull HttpServletRequest request,
                                     @NotNull HttpServletResponse response,
                                     @Nullable SProject project) {
    if (project == null) {
      response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
      return false;
    }

    if (!hasAnyProjectPermission(SessionUser.getUser(request), project,
        Permission.EDIT_PROJECT, Permission.VIEW_BUILD_CONFIGURATION_SETTINGS)) {
      response.setStatus(HttpServletResponse.SC_FORBIDDEN);
      return false;
    }
    return true;
  }

  @Nullable
  private SProject findProject(HttpServletRequest request) {
    String projectId = request.getParameter("projectId");
    if (projectId != null && !projectId.trim().isEmpty()) {
      return myProjectManager.findProjectByExternalId(projectId);
    }
    SBuildType buildType = findBuildType(request.getParameter("buildTypeId"), myProjectManager);
    return buildType == null ? null : buildType.getProject();
  }
}
