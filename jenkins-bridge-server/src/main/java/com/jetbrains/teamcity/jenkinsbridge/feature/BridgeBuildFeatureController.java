package com.jetbrains.teamcity.jenkinsbridge.feature;

import com.intellij.openapi.diagnostic.Logger;
import com.jetbrains.teamcity.jenkinsbridge.connection.JenkinsConnectionResolver;
import com.jetbrains.teamcity.jenkinsbridge.util.Utilities;
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
import static com.jetbrains.teamcity.jenkinsbridge.util.ProjectPermissionHelper.hasProjectPermission;

/**
 * Renders the edit form of the Jenkins Bridge build feature. A controller is needed instead of a
 * plain JSP so the form can reach {@link JenkinsConnectionResolver} and list the Jenkins connections
 * available to the edited project.
 */
public class BridgeBuildFeatureController extends BaseController {
  /** Path under the plugin resources, also used as the feature's edit parameters URL. */
  public static final String EDIT_PARAMS_RELATIVE_URL = "editJenkinsBridge.html";
  private static final Logger LOG = Logger.getInstance(BridgeBuildFeatureController.class.getName());

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
    try {
      SProject project = findProject(request);
      ProjectAccess access = checkProjectAccess(request, response, project);
      if (access.error != null) {
        return access.error;
      }

      ModelAndView modelAndView = new ModelAndView(
          myPluginDescriptor.getPluginResourcesPath("editJenkinsBridge.jsp"));
      modelAndView.getModel().put("jenkinsConnections", myConnectionResolver);
      modelAndView.getModel().put("readOnly", access.readOnly);
      return modelAndView;
    } catch (RuntimeException e) {
      LOG.error("Jenkins Bridge build-feature request failed: " + requestDescription(request), e);
      throw e;
    }
  }

  @Nullable
  private ProjectAccess checkProjectAccess(@NotNull HttpServletRequest request,
                                           @NotNull HttpServletResponse response,
                                           @Nullable SProject project) {
    if (project == null) {
      return ProjectAccess.error(error(response, HttpServletResponse.SC_BAD_REQUEST,
          "Jenkins Bridge could not determine the build configuration project from the request"));
    }

    SUser user = SessionUser.getUser(request);
    boolean hasEdit = hasProjectPermission(user, project, Permission.EDIT_PROJECT);
    boolean hasView = hasProjectPermission(user, project,
        Permission.VIEW_BUILD_CONFIGURATION_SETTINGS);
    if (!hasEdit && !hasView) {
      return ProjectAccess.error(error(response, HttpServletResponse.SC_FORBIDDEN,
          "You do not have permission to view or edit this project"));
    }
    return ProjectAccess.allowed(!hasEdit);
  }

  private ModelAndView error(@NotNull HttpServletResponse response, int status, @NotNull String message) {
    response.setStatus(status);
    LOG.warn("Jenkins Bridge build-feature returning HTTP " + status + ": " + message);
    return simpleView(message);
  }

  @Nullable
  private SProject findProject(HttpServletRequest request) {
    String projectId = request.getParameter("projectId");
    if (projectId != null && !projectId.trim().isEmpty()) {
      return Utilities.findProject(projectId, myProjectManager);
    }
    String buildTypeId = request.getParameter("buildTypeId");
    if (buildTypeId == null || buildTypeId.trim().isEmpty()) {
      String formId = request.getParameter("id");
      if (formId != null && formId.startsWith("buildType:")) {
        buildTypeId = formId.substring("buildType:".length());
      }
    }
    SBuildType buildType = findBuildType(buildTypeId, myProjectManager);
    return buildType == null ? null : buildType.getProject();
  }

  private static String requestDescription(@NotNull HttpServletRequest request) {
    return "method=" + request.getMethod()
        + ", URI=" + request.getRequestURI()
        + ", contextPath=" + request.getContextPath()
        + ", query=" + request.getQueryString()
        + ", parameters=" + request.getParameterMap().keySet()
        + ", projectId=" + request.getParameter("projectId")
        + ", buildTypeId=" + request.getParameter("buildTypeId")
        + ", id=" + request.getParameter("id")
        + ", featureId=" + request.getParameter("featureId");
  }
  // TODO maybe in the future try to move it to the project permission handler util class, currently it would require some refactoring as
  // so it is reusable in othe controllers
  private static final class ProjectAccess {
    @Nullable private final ModelAndView error;
    private final boolean readOnly;

    private ProjectAccess(@Nullable ModelAndView error, boolean readOnly) {
      this.error = error;
      this.readOnly = readOnly;
    }

    @NotNull
    private static ProjectAccess allowed(boolean readOnly) {
      return new ProjectAccess(null, readOnly);
    }

    @NotNull
    private static ProjectAccess error(@NotNull ModelAndView error) {
      return new ProjectAccess(error, false);
    }
  }

}
