package com.jetbrains.teamcity.jenkinsbridge.web;

import com.google.gson.Gson;
import com.jetbrains.teamcity.jenkinsbridge.jenkins.JenkinsClient;
import com.jetbrains.teamcity.jenkinsbridge.jenkins.JenkinsClientFactory;
import com.jetbrains.teamcity.jenkinsbridge.model.JenkinsPipelineGraph;
import com.jetbrains.teamcity.jenkinsbridge.model.JenkinsPipelineGraphNode;
import com.jetbrains.teamcity.jenkinsbridge.model.JenkinsStageLog;
import com.jetbrains.teamcity.jenkinsbridge.model.JenkinsStageStep;
import com.jetbrains.teamcity.jenkinsbridge.persistence.BuildMirror;
import com.jetbrains.teamcity.jenkinsbridge.persistence.BuildMirrorResolver;
import jetbrains.buildServer.controllers.BaseController;
import jetbrains.buildServer.serverSide.BuildsManager;
import jetbrains.buildServer.serverSide.SBuild;
import jetbrains.buildServer.serverSide.SBuildType;
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
import java.util.List;

/**
 * AJAX endpoint backing the "Pipeline Graph" build-results tab. Given a TeamCity build id, resolves the
 * mirrored Jenkins build (via {@link BuildMirrorResolver}, which covers both creation flows) and returns
 * its normalized Blue Ocean pipeline graph as JSON for the tab's renderer. Read-only; requires VIEW_PROJECT
 * on the build's project. Returns {@code {"pipeline": false}} for non-mirror / non-pipeline builds.
 */
public class JenkinsPipelineGraphController extends BaseController {
  static final String PATH = "/jenkinsBridgePipelineGraph.html";
  private static final Gson GSON = new Gson();

  private final BuildsManager buildsManager;
  private final BuildMirrorResolver mirrorResolver;
  private final JenkinsClientFactory jenkinsClientFactory;

  public JenkinsPipelineGraphController(
      WebControllerManager webControllerManager,
      BuildsManager buildsManager,
      BuildMirrorResolver mirrorResolver,
      JenkinsClientFactory jenkinsClientFactory
  ) {
    this.buildsManager = buildsManager;
    this.mirrorResolver = mirrorResolver;
    this.jenkinsClientFactory = jenkinsClientFactory;
    webControllerManager.registerController(PATH, this);
  }

  private JenkinsClient jenkinsClientFor(SBuild build) {
    SBuildType buildType = build.getBuildType();
    if (buildType == null) {
      throw new IllegalStateException("Build " + build.getBuildId() + " has no build configuration");
    }
    return jenkinsClientFactory.forBuildType(buildType);
  }

  @Override
  protected ModelAndView doHandle(HttpServletRequest request, HttpServletResponse response) throws Exception {
    SBuild build = resolveBuild(request.getParameter("buildId"));
    if (build == null) {
      return error(response, 400, "Unknown or missing build");
    }

    SUser user = SessionUser.getUser(request);
    if (user == null || !user.isPermissionGrantedForProject(build.getProjectId(), Permission.VIEW_PROJECT)) {
      return error(response, 403, "You do not have permission to view this build");
    }

    String action = request.getParameter("action");
    if ("steps".equals(action)) {
      return writeJson(response, stageStepsForBuild(build, request.getParameter("nodeId")));
    }
    if ("stagelog".equals(action)) {
      return writeJson(response, stageLogForBuild(build, request.getParameter("nodeId")));
    }
    return writeJson(response, graphViewForBuild(build));
  }

  private SBuild resolveBuild(String buildIdParam) {
    if (buildIdParam == null || buildIdParam.trim().length() == 0) {
      return null;
    }
    try {
      return buildsManager.findBuildInstanceById(Long.parseLong(buildIdParam.trim()));
    } catch (NumberFormatException e) {
      return null;
    }
  }

  /** Package-private so it can be unit-tested without a servlet request. */
  GraphView graphViewForBuild(SBuild build) throws IOException {
    BuildMirror mirror = mirrorResolver.resolve(build);
    JenkinsPipelineGraph graph = mirror == null ? null : mirror.getPipelineGraph();
    if (graph == null || !graph.isPipeline() || graph.getNodes().isEmpty()) {
      return GraphView.notPipeline();
    }
    return GraphView.of(graph);
  }

  /**
   * Whole-stage log for one graph node: WFAPI step logs for that node, concatenated (reuses
   * {@link JenkinsClient#getStageLog}). Uses the clicked Blue Ocean node id directly as the WFAPI stage
   * id (the node-id match); a mismatched/absent node yields empty text rather than an error.
   * Package-private for tests.
   */
  StageLogView stageLogForBuild(SBuild build, String nodeId) throws IOException {
    if (nodeId == null || nodeId.trim().length() == 0) {
      return new StageLogView("", "");
    }
    BuildMirror mirror = mirrorResolver.resolve(build);
    if (mirror == null) {
      return new StageLogView(nodeId, "");
    }
    try {
      JenkinsStageLog log = jenkinsClientFor(build).getStageLog(
          mirror.getJenkinsJob(), mirror.getJenkinsBuildNumber(), nodeId);
      return new StageLogView(nodeId, log.getText());
    } catch (Exception e) {
      StageLogView view = new StageLogView(nodeId, "");
      view.error = e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage());
      return view;
    }
  }

  /**
   * Per-step breakdown of one stage (G3b): each WFAPI step's name, status, duration, and console log.
   * Uses the clicked Blue Ocean node id as the WFAPI stage id (node-id match). Package-private for tests.
   */
  StageStepsView stageStepsForBuild(SBuild build, String nodeId) throws IOException {
    StageStepsView view = new StageStepsView(nodeId == null ? "" : nodeId);
    if (nodeId == null || nodeId.trim().length() == 0) {
      return view;
    }
    BuildMirror mirror = mirrorResolver.resolve(build);
    if (mirror == null) {
      return view;
    }
    try {
      String nodeName = nodeNameFromGraph(mirror, nodeId);
      for (JenkinsStageStep step : jenkinsClientFor(build).getStageStepsForNode(
          mirror.getJenkinsJob(), mirror.getJenkinsBuildNumber(), nodeId, nodeName)) {
        view.steps.add(new StepView(step));
      }
    } catch (Exception e) {
      view.error = e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage());
    }
    return view;
  }

  /** The display name of a graph node, from the persisted graph — used to name-match parallel branches. */
  private String nodeNameFromGraph(BuildMirror mirror, String nodeId) {
    JenkinsPipelineGraph graph = mirror.getPipelineGraph();
    if (graph != null) {
      for (JenkinsPipelineGraphNode node : graph.getNodes()) {
        if (node.getId().equals(nodeId)) {
          return node.getName();
        }
      }
    }
    return "";
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

  /** JSON shape sent to the tab renderer. Stable, decoupled from the internal graph model. */
  @SuppressWarnings("unused")
  static final class GraphView {
    final boolean pipeline;
    final String source;
    final String confidence;
    final String topologyHash;
    final List<NodeView> nodes;

    private GraphView(boolean pipeline, String source, String confidence, String topologyHash, List<NodeView> nodes) {
      this.pipeline = pipeline;
      this.source = source;
      this.confidence = confidence;
      this.topologyHash = topologyHash;
      this.nodes = nodes;
    }

    static GraphView notPipeline() {
      return new GraphView(false, "", "", "", Collections.<NodeView>emptyList());
    }

    static GraphView of(JenkinsPipelineGraph graph) {
      List<NodeView> nodes = new ArrayList<NodeView>();
      for (JenkinsPipelineGraphNode node : graph.getNodes()) {
        nodes.add(new NodeView(node));
      }
      return new GraphView(
          true,
          graph.getSource(),
          String.valueOf(graph.getConfidence()),
          graph.getTopologyHash(),
          nodes);
    }
  }

  @SuppressWarnings("unused")
  static final class NodeView {
    final String id;
    final String name;
    final String status;
    final long startTimeMillis;
    final long durationMillis;
    final List<String> parents;
    final List<String> children;

    NodeView(JenkinsPipelineGraphNode node) {
      this.id = node.getId();
      this.name = node.getName();
      this.status = node.getStatus();
      this.startTimeMillis = node.getStartTimeMillis();
      this.durationMillis = node.getDurationMillis();
      this.parents = new ArrayList<String>(node.getParentIds());
      this.children = new ArrayList<String>(node.getChildIds());
    }
  }

  /** JSON shape for one stage's whole log (G3a: click a graph node -> its concatenated stage log). */
  @SuppressWarnings("unused")
  static final class StageLogView {
    final String nodeId;
    final String text;
    String error;

    StageLogView(String nodeId, String text) {
      this.nodeId = nodeId;
      this.text = text;
    }
  }

  /** JSON shape for a stage's per-step breakdown (G3b): steps with name/status/duration/log. */
  @SuppressWarnings("unused")
  static final class StageStepsView {
    final String nodeId;
    final List<StepView> steps = new ArrayList<StepView>();
    String error;

    StageStepsView(String nodeId) {
      this.nodeId = nodeId;
    }
  }

  @SuppressWarnings("unused")
  static final class StepView {
    final String id;
    final String name;
    final String status;
    final long durationMillis;
    final String log;

    StepView(JenkinsStageStep step) {
      this.id = step.getId();
      this.name = step.getName();
      this.status = step.getStatus();
      this.durationMillis = step.getDurationMillis();
      this.log = step.getLog();
    }
  }
}
