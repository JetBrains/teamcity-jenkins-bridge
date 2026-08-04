package com.jetbrains.teamcity.jenkinsbridge.web;

import com.google.gson.Gson;
import com.jetbrains.teamcity.jenkinsbridge.feature.BridgeBuildFeatureConstants;
import com.jetbrains.teamcity.jenkinsbridge.feature.JenkinsParameterPayloadBuilder;
import com.jetbrains.teamcity.jenkinsbridge.jenkins.JenkinsClient;
import com.jetbrains.teamcity.jenkinsbridge.model.JenkinsJobParameters;
import com.jetbrains.teamcity.jenkinsbridge.model.JenkinsParameterDefinition;
import jetbrains.buildServer.controllers.BaseController;
import jetbrains.buildServer.serverSide.SBuildFeatureDescriptor;
import jetbrains.buildServer.serverSide.SBuildType;
import jetbrains.buildServer.serverSide.ProjectManager;
import jetbrains.buildServer.serverSide.auth.Permission;
import jetbrains.buildServer.users.SUser;
import jetbrains.buildServer.web.openapi.WebControllerManager;
import jetbrains.buildServer.web.util.SessionUser;
import org.springframework.web.servlet.ModelAndView;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * AJAX endpoint backing the "Trigger Jenkins" build-configuration tab. Two actions:
 * <ul>
 *   <li>{@code action=params}: the build parameters the mapped Jenkins job declares (JSON).</li>
 *   <li>{@code action=trigger}: POST submitted values to Jenkins {@code buildWithParameters}; returns
 *       the queue-item URL (JSON).</li>
 * </ul>
 * Runs on the request thread under the logged-in user; both require RUN_BUILD on the build config's
 * project. The Jenkins job comes from the build config's Jenkins Bridge feature.
 */
public class JenkinsTriggerController extends BaseController {
  static final String PATH = "/jenkinsBridgeTrigger.html";
  /** Prefix for submitted parameter values, e.g. {@code value_BRANCH=main}. */
  static final String VALUE_PREFIX = "value_";
  private static final Gson GSON = new Gson();

  private final ProjectManager projectManager;
  private final JenkinsClient jenkinsClient;

  public JenkinsTriggerController(
      WebControllerManager webControllerManager,
      ProjectManager projectManager,
      JenkinsClient jenkinsClient
  ) {
    this.projectManager = projectManager;
    this.jenkinsClient = jenkinsClient;
    webControllerManager.registerController(PATH, this);
  }

  @Override
  protected ModelAndView doHandle(HttpServletRequest request, HttpServletResponse response) throws Exception {
    String buildTypeExternalId = request.getParameter("buildTypeExternalId");
    SBuildType buildType = buildTypeExternalId == null
        ? null : projectManager.findBuildTypeByExternalId(buildTypeExternalId);
    if (buildType == null) {
      return error(response, 400, "Unknown or missing build configuration");
    }

    SUser user = SessionUser.getUser(request);
    if (user == null || !user.isPermissionGrantedForProject(buildType.getProjectId(), Permission.RUN_BUILD)) {
      return error(response, 403, "You do not have permission to run builds in this project");
    }

    String job = mappedJenkinsJob(buildType);
    if (job == null || job.trim().length() == 0) {
      return error(response, 409, "This build configuration has no Jenkins Bridge job configured");
    }

    String action = request.getParameter("action");
    try {
      if ("trigger".equals(action)) {
        return handleTrigger(response, job, request);
      }
      return handleParams(response, buildType, job);
    } catch (Exception e) {
      return error(response, 502, e.getClass().getSimpleName()
          + (e.getMessage() == null ? "" : ": " + e.getMessage()));
    }
  }

  private ModelAndView handleParams(HttpServletResponse response, SBuildType buildType, String job)
      throws Exception {
    JenkinsJobParameters parameters = jenkinsClient.getJobParameters(job);
    List<ParamView> jenkinsViews = new ArrayList<ParamView>();
    Set<String> declaredNames = new HashSet<String>();
    for (JenkinsParameterDefinition def : parameters.getParameters()) {
      jenkinsViews.add(new ParamView(def));
      declaredNames.add(def.getName());
    }

    Map<String, Object> payload = new LinkedHashMap<String, Object>();
    payload.put("jenkinsParameters", jenkinsViews);
    payload.put("configParameters", configParameters(buildType.getConfigParameters(), declaredNames));
    return writeJson(response, payload);
  }

  /**
   * The build config's own configuration parameters that are NOT Jenkins build parameters — shown
   * read-only in the tab for context. {@code getConfigParameters()} already excludes system
   * properties and environment variables; we further drop any name the Jenkins job declares (those
   * are rendered as the editable Jenkins group). Sorted by name for a stable UI.
   */
  static List<ConfigParamView> configParameters(Map<String, String> configParameters,
                                                Set<String> jenkinsDeclaredNames) {
    List<ConfigParamView> views = new ArrayList<ConfigParamView>();
    if (configParameters == null) {
      return views;
    }
    Set<String> declared = jenkinsDeclaredNames == null
        ? Collections.<String>emptySet() : jenkinsDeclaredNames;
    for (Map.Entry<String, String> entry : configParameters.entrySet()) {
      if (declared.contains(entry.getKey())) {
        continue;
      }
      views.add(new ConfigParamView(entry.getKey(), entry.getValue()));
    }
    Collections.sort(views, new Comparator<ConfigParamView>() {
      public int compare(ConfigParamView left, ConfigParamView right) {
        return left.name.compareTo(right.name);
      }
    });
    return views;
  }

  private ModelAndView handleTrigger(HttpServletResponse response, String job, HttpServletRequest request)
      throws Exception {
    // Re-read the definitions so we only forward declared parameters, falling back to each default.
    JenkinsJobParameters parameters = jenkinsClient.getJobParameters(job);
    Map<String, String> submittedValues = new LinkedHashMap<String, String>();
    for (JenkinsParameterDefinition def : parameters.getParameters()) {
      String submitted = request.getParameter(VALUE_PREFIX + def.getName());
      if (submitted != null) {
        submittedValues.put(def.getName(), submitted);
      }
    }
    Map<String, String> values = JenkinsParameterPayloadBuilder.build(parameters, submittedValues);

    String queueItemUrl = jenkinsClient.triggerBuild(job, values);

    Map<String, String> result = new LinkedHashMap<String, String>();
    result.put("job", job);
    result.put("queueItemUrl", queueItemUrl);
    return writeJson(response, result);
  }

  private String mappedJenkinsJob(SBuildType buildType) {
    for (SBuildFeatureDescriptor descriptor
        : buildType.getBuildFeaturesOfType(BridgeBuildFeatureConstants.TYPE)) {
      return descriptor.getParameters().get(BridgeBuildFeatureConstants.PARAM_JENKINS_JOB);
    }
    return null;
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

  /** Shape sent to the browser for each Jenkins parameter. */
  @SuppressWarnings("unused")
  private static final class ParamView {
    final String name;
    final String type;
    final String defaultValue;
    final List<String> choices;

    ParamView(JenkinsParameterDefinition def) {
      this.name = def.getName();
      this.type = def.getType();
      this.defaultValue = def.getDefaultValue();
      this.choices = def.getChoices();
    }
  }

  /** Shape sent to the browser for each TeamCity configuration parameter (read-only, not forwarded). */
  @SuppressWarnings("unused")
  static final class ConfigParamView {
    final String name;
    final String value;

    ConfigParamView(String name, String value) {
      this.name = name;
      this.value = value;
    }
  }
}
