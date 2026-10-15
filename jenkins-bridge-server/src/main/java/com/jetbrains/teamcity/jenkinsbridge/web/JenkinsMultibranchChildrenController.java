package com.jetbrains.teamcity.jenkinsbridge.web;

import com.google.gson.Gson;
import com.intellij.openapi.diagnostic.Logger;
import com.jetbrains.teamcity.jenkinsbridge.feature.BridgeBuildFeatureConstants;
import com.jetbrains.teamcity.jenkinsbridge.http.BridgeHttpException;
import com.jetbrains.teamcity.jenkinsbridge.jenkins.JenkinsClient;
import com.jetbrains.teamcity.jenkinsbridge.jenkins.JenkinsClientFactory;
import com.jetbrains.teamcity.jenkinsbridge.jenkins.JenkinsDataException;
import com.jetbrains.teamcity.jenkinsbridge.jenkins.JenkinsJob;
import com.jetbrains.teamcity.jenkinsbridge.teamcity.TeamCityBridgeFeatureLookup;
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

import static com.jetbrains.teamcity.jenkinsbridge.util.Utilities.decodeBranchFragment;

/** Supplies indexed, runnable Jenkins children for the multibranch Run Custom Build selector. */
public final class JenkinsMultibranchChildrenController extends BaseController {
  static final String PATH = "/jenkinsBridgeMultibranchChildren.html";
  private static final Logger LOG =
      Logger.getInstance(JenkinsMultibranchChildrenController.class.getName());
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

    SBuildFeatureDescriptor feature = TeamCityBridgeFeatureLookup.findBridgeFeature(buildType);
    String parentPath = feature == null
        ? null
        : feature.getParameters().get(BridgeBuildFeatureConstants.PARAM_JENKINS_JOB);
    if (parentPath == null || parentPath.trim().isEmpty()) {
      return error(response, 400, "The Jenkins multibranch mapping is incomplete");
    }

    JenkinsClient client;
    try {
      client = jenkinsClientFactory.forBuildType(buildType);
    } catch (IllegalStateException e) {
      LOG.warn(
          "Jenkins Bridge could not resolve the Jenkins connection for TeamCity build configuration "
              + buildType.getExternalId(),
          e);
      return error(response, 502,
          "The Jenkins connection for this build configuration is unavailable. "
              + "Check its Jenkins Bridge settings.");
    }

    try {
      return writeJson(response, runnableChildren(client, parentPath));
    } catch (BridgeHttpException | JenkinsDataException e) {
      LOG.warn(
          "Jenkins Bridge could not load Jenkins branches for TeamCity build configuration "
              + buildType.getExternalId(),
          e);
      return error(response, 502,
          "Could not load Jenkins branches. See the TeamCity server log for details.");
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
      // Jenkins may expose the encoded item name as displayName for branch jobs. Decode only
      // the label; the option value must remain the canonical full job path used for triggering.
      children.add(new ChildOption(decodeBranchFragment(job.getDisplayName()), job.getFullName()));
    }
    return children;
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

  static final class ChildOption {
    final String name;
    final String fullName;

    ChildOption(String name, String fullName) {
      this.name = name;
      this.fullName = fullName;
    }
  }

}
