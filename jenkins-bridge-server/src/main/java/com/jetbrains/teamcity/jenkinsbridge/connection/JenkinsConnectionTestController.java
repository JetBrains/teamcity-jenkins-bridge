package com.jetbrains.teamcity.jenkinsbridge.connection;

import jetbrains.buildServer.controllers.BaseFormXmlController;
import jetbrains.buildServer.controllers.BasePropertiesBean;
import jetbrains.buildServer.controllers.admin.projects.PluginPropertiesUtil;
import jetbrains.buildServer.serverSide.ProjectManager;
import jetbrains.buildServer.serverSide.SProject;
import jetbrains.buildServer.serverSide.auth.Permission;
import jetbrains.buildServer.serverSide.validation.TestConnectionResult;
import jetbrains.buildServer.web.openapi.WebControllerManager;
import jetbrains.buildServer.web.util.SessionUser;
import org.jdom.Element;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.web.servlet.ModelAndView;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Collections;

import static com.jetbrains.teamcity.jenkinsbridge.util.ProjectPermissionHelper.hasProjectPermission;

/**
 * Controller for the "Test connection" button of the Jenkins connection dialog. The dialog posts the
 * currently entered parameters here and renders the serialized errors.
 * Requires the edit permission on the project the connection belongs to.
 */
public class JenkinsConnectionTestController extends BaseFormXmlController {
  @NotNull private final ProjectManager myProjectManager;
  @NotNull private final JenkinsConnectionProvider myConnectionProvider;

  public JenkinsConnectionTestController(@NotNull WebControllerManager webControllerManager,
                                         @NotNull ProjectManager projectManager,
                                         @NotNull JenkinsConnectionProvider connectionProvider) {
    myProjectManager = projectManager;
    myConnectionProvider = connectionProvider;
    webControllerManager.registerController(JenkinsConnectionConstants.TEST_CONNECTION_PATH, this);
  }

  @Nullable
  @Override
  protected ModelAndView doGet(@NotNull HttpServletRequest request, @NotNull HttpServletResponse response) {
    return null;
  }

  @Override
  protected void doPost(@NotNull HttpServletRequest request,
                        @NotNull HttpServletResponse response,
                        @NotNull Element xmlResponse) {
    String projectId = request.getParameter("projectId");
    SProject project = projectId == null ? null : myProjectManager.findProjectByExternalId(projectId);
    TestConnectionResult result = new TestConnectionResult();
    if (project == null) {
      result.addError(JenkinsConnectionProvider.TEST_CONNECTION_FAILED, "Project " + projectId + " was not found");
      result.serialize(xmlResponse);
      return;
    }

    if (!hasProjectPermission(SessionUser.getUser(request), project, Permission.EDIT_PROJECT)) {
      result.addError(JenkinsConnectionProvider.TEST_CONNECTION_FAILED,
          "You are not allowed to edit project " + project.getExternalId());
      result.serialize(xmlResponse);
      return;
    }

    BasePropertiesBean propertiesBean = new BasePropertiesBean(Collections.emptyMap());
    PluginPropertiesUtil.bindPropertiesFromRequest(request, propertiesBean);
    myConnectionProvider.testConnection(project.getExternalId(), propertiesBean.getProperties())
        .serialize(xmlResponse);
  }
}
