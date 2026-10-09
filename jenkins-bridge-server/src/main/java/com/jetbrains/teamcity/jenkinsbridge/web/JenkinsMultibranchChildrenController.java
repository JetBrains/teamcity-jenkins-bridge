package com.jetbrains.teamcity.jenkinsbridge.web;

import com.google.gson.Gson;
import com.jetbrains.teamcity.jenkinsbridge.feature.BridgeBuildFeatureConstants;
import com.jetbrains.teamcity.jenkinsbridge.feature.JenkinsTeamCityRunParameterFactory;
import com.jetbrains.teamcity.jenkinsbridge.http.BridgeHttpException;
import com.jetbrains.teamcity.jenkinsbridge.jenkins.JenkinsClient;
import com.jetbrains.teamcity.jenkinsbridge.jenkins.JenkinsClientFactory;
import com.jetbrains.teamcity.jenkinsbridge.jenkins.JenkinsDataException;
import com.jetbrains.teamcity.jenkinsbridge.jenkins.JenkinsJob;
import com.jetbrains.teamcity.jenkinsbridge.model.JenkinsJobParameters;
import com.jetbrains.teamcity.jenkinsbridge.model.JenkinsParameterDefinition;
import com.jetbrains.teamcity.jenkinsbridge.util.ProjectPermissionHelper;
import com.jetbrains.teamcity.jenkinsbridge.util.Utilities;
import jetbrains.buildServer.controllers.BaseController;
import jetbrains.buildServer.serverSide.ProjectManager;
import jetbrains.buildServer.serverSide.SBuildFeatureDescriptor;
import jetbrains.buildServer.serverSide.SBuildType;
import jetbrains.buildServer.serverSide.auth.Permission;
import jetbrains.buildServer.web.openapi.WebControllerManager;
import jetbrains.buildServer.web.util.SessionUser;
import org.springframework.web.servlet.ModelAndView;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Supplies indexed, runnable Jenkins children for the multibranch Run Custom Build selector. */
public final class JenkinsMultibranchChildrenController extends BaseController {
  static final String PATH = "/jenkinsBridgeMultibranchChildren.html";
  private static final Gson GSON = new Gson();

  private final ProjectManager projectManager;
  private final JenkinsClientFactory jenkinsClientFactory;

  public JenkinsMultibranchChildrenController(WebControllerManager controllerManager,
                                              ProjectManager projectManager,
                                              JenkinsClientFactory jenkinsClientFactory) {
    this.projectManager = projectManager;
    this.jenkinsClientFactory = jenkinsClientFactory;
    controllerManager.registerController(PATH, this);
  }

  @Override
  protected ModelAndView doHandle(HttpServletRequest request, HttpServletResponse response) throws Exception {
    SBuildType buildType = projectManager.findBuildTypeByExternalId(request.getParameter("buildTypeId"));
    if (buildType == null) {
      return error(response, 404, "TeamCity build configuration was not found");
    }
    if (!ProjectPermissionHelper.hasProjectPermission(
        SessionUser.getUser(request), buildType.getProject(), Permission.VIEW_PROJECT)) {
      return error(response, 403, "You do not have permission to view this build configuration");
    }
    if (!Utilities.isBuildConfigMultibranch(buildType)) {
      return error(response, 400, "This build configuration does not map a multibranch pipeline");
    }

    SBuildFeatureDescriptor feature = buildType.getBuildFeaturesOfType(BridgeBuildFeatureConstants.TYPE)
        .stream().findFirst().orElse(null);
    String parentPath = feature == null ? null
        : feature.getParameters().get(BridgeBuildFeatureConstants.PARAM_JENKINS_JOB);
    if (parentPath == null || parentPath.trim().isEmpty()) {
      return error(response, 400, "The Jenkins multibranch mapping is incomplete");
    }

    try {
      JenkinsClient client = jenkinsClientFactory.forBuildType(buildType);
      String childJob = request.getParameter("childJob");
      if (childJob != null && !childJob.trim().isEmpty()) {
        return writeChildParameters(response, client, parentPath, childJob.trim());
      }
      return writeJson(response, runnableChildren(client, parentPath));
    } catch (BridgeHttpException | JenkinsDataException e) {
      return error(response, 502, "Could not load Jenkins branches: " + safeMessage(e));
    }
  }

  List<ChildOption> runnableChildren(JenkinsClient client, String parentPath)
      throws BridgeHttpException, JenkinsDataException {
    List<ChildOption> children = new ArrayList<>();
    String childPrefix = parentPath + "/";
    for (JenkinsJob job : client.listJobs(parentPath)) {
      if (!job.isImportable() || !job.isBuildable() || job.getFullName() == null
          || !job.getFullName().startsWith(childPrefix)) {
        continue;
      }
      children.add(new ChildOption(job.getDisplayName(), job.getFullName()));
    }
    return children;
  }

  private ModelAndView writeChildParameters(HttpServletResponse response, JenkinsClient client,
                                            String parentPath, String childJob)
      throws BridgeHttpException, JenkinsDataException, IOException {
    boolean runnableChild = false;
    for (ChildOption child : runnableChildren(client, parentPath)) {
      if (childJob.equals(child.fullName)) {
        runnableChild = true;
        break;
      }
    }
    if (!runnableChild) {
      return error(response, 404, "The selected Jenkins branch is no longer runnable");
    }

    JenkinsJobParameters definitions = client.getJobParameters(childJob);
    List<ParameterOption> parameters = new ArrayList<>();
    for (JenkinsParameterDefinition definition : definitions.getParameters()) {
      if (JenkinsTeamCityRunParameterFactory.canImport(definition)) {
        parameters.add(new ParameterOption(definition));
      }
    }
    return writeJson(response, parameters);
  }

  private ModelAndView writeJson(HttpServletResponse response, Object payload) throws IOException {
    response.setContentType("application/json");
    response.setCharacterEncoding("UTF-8");
    response.getWriter().write(GSON.toJson(payload));
    return null;
  }

  private ModelAndView error(HttpServletResponse response, int status, String message) throws IOException {
    response.setStatus(status);
    return writeJson(response, Collections.singletonMap("error", message));
  }

  private static String safeMessage(Exception exception) {
    String message = exception.getMessage();
    return message == null || message.trim().isEmpty() ? exception.getClass().getSimpleName() : message;
  }

  static final class ChildOption {
    final String name;
    final String fullName;

    ChildOption(String name, String fullName) {
      this.name = name;
      this.fullName = fullName;
    }
  }

  static final class ParameterOption {
    final String name;
    final String type;
    final String defaultValue;
    final List<String> choices;

    ParameterOption(JenkinsParameterDefinition definition) {
      this.name = definition.getName();
      this.type = definition.getType();
      this.defaultValue = definition.getDefaultValue();
      this.choices = definition.getChoices();
    }
  }
}
