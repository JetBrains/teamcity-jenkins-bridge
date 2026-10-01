package com.jetbrains.teamcity.jenkinsbridge.web;

import com.google.gson.Gson;
import com.jetbrains.teamcity.jenkinsbridge.http.BridgeHttpException;
import com.jetbrains.teamcity.jenkinsbridge.jenkins.JenkinsClient;
import com.jetbrains.teamcity.jenkinsbridge.jenkins.JenkinsClientFactory;
import com.jetbrains.teamcity.jenkinsbridge.jenkins.JenkinsDataException;
import com.jetbrains.teamcity.jenkinsbridge.model.JenkinsPipelineGraph;
import com.jetbrains.teamcity.jenkinsbridge.model.JenkinsPipelineGraphNode;
import com.jetbrains.teamcity.jenkinsbridge.model.JenkinsStageLog;
import com.jetbrains.teamcity.jenkinsbridge.model.JenkinsStageStep;
import com.jetbrains.teamcity.jenkinsbridge.persistence.BuildResultMetadata;
import com.jetbrains.teamcity.jenkinsbridge.persistence.BuildResultMetadataResolver;
import jetbrains.buildServer.controllers.BaseController;
import jetbrains.buildServer.serverSide.BuildsManager;
import jetbrains.buildServer.serverSide.SBuild;
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

import static com.jetbrains.teamcity.jenkinsbridge.util.ProjectPermissionHelper.hasProjectPermission;

/**
 * AJAX endpoint backing the "Pipeline Graph" build-results tab. Given a TeamCity build id, resolves the
 * persisted Jenkins result metadata and returns its normalized Jenkins pipeline graph as JSON for the
 * tab's renderer. Result metadata is separate from active {@code BuildMirror} synchronization state.
 * Read-only; requires VIEW_PROJECT
 * on the build's project. Returns {@code {"pipeline": false}} for non-mirror / non-pipeline builds.
 */
public class JenkinsPipelineGraphController extends BaseController {
  static final String PATH = "/jenkinsBridgePipelineGraph.html";
  private static final Gson GSON = new Gson();

  private final BuildsManager buildsManager;
  private final BuildResultMetadataResolver resultMetadataResolver;
  private final JenkinsClientFactory jenkinsClientFactory;

  public JenkinsPipelineGraphController(
      WebControllerManager webControllerManager,
      BuildsManager buildsManager,
      BuildResultMetadataResolver resultMetadataResolver,
      JenkinsClientFactory jenkinsClientFactory
  ) {
    this.buildsManager = buildsManager;
    this.resultMetadataResolver = resultMetadataResolver;
    this.jenkinsClientFactory = jenkinsClientFactory;
    webControllerManager.registerController(PATH, this);
  }

  private JenkinsClient jenkinsClientFor(SBuild build) {
    jetbrains.buildServer.serverSide.SBuildType buildType = build.getBuildType();
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

    if (!hasProjectPermission(SessionUser.getUser(request), build.getProjectId(), Permission.VIEW_PROJECT)) {
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
    BuildResultMetadata metadata = resultMetadataResolver.resolve(build);
    JenkinsPipelineGraph graph = metadata == null ? null : metadata.getPipelineGraph();
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
    BuildResultMetadata metadata = resultMetadataResolver.resolve(build);
    if (metadata == null) {
      return new StageLogView(nodeId, "");
    }
    try {
      JenkinsPipelineGraph graph = metadata.getPipelineGraph();
      JenkinsStageLog log = graph != null && JenkinsPipelineGraph.SOURCE_PIPELINE_GRAPH_VIEW.equals(graph.getSource())
          ? jenkinsClientFor(build).getPipelineGraphViewLog(
              metadata.getJenkinsJob(), metadata.getJenkinsBuildNumber(), nodeId)
          : jenkinsClientFor(build).getStageLog(
              metadata.getJenkinsJob(), metadata.getJenkinsBuildNumber(), nodeId);
      return new StageLogView(nodeId, log.getText());
    } catch (BridgeHttpException | JenkinsDataException e) {
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
    BuildResultMetadata metadata = resultMetadataResolver.resolve(build);
    if (metadata == null) {
      return view;
    }
    try {
      JenkinsPipelineGraph graph = metadata.getPipelineGraph();
      List<JenkinsStageStep> steps = graph != null
          && JenkinsPipelineGraph.SOURCE_PIPELINE_GRAPH_VIEW.equals(graph.getSource())
          ? jenkinsClientFor(build).getPipelineGraphViewSteps(
              metadata.getJenkinsJob(), metadata.getJenkinsBuildNumber(), nodeId)
          : jenkinsClientFor(build).getStageStepsForNode(
              metadata.getJenkinsJob(), metadata.getJenkinsBuildNumber(), nodeId,
              nodeNameFromGraph(metadata, nodeId));
      for (JenkinsStageStep step : steps) {
        view.steps.add(new StepView(step));
      }
    } catch (BridgeHttpException | JenkinsDataException e) {
      view.error = e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage());
    }
    return view;
  }

  /** The display name of a graph node, from the persisted graph — used to name-match parallel branches. */
  private String nodeNameFromGraph(BuildResultMetadata metadata, String nodeId) {
    JenkinsPipelineGraph graph = metadata.getPipelineGraph();
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
    final boolean complete;
    final List<NodeView> nodes;

    private GraphView(boolean pipeline, String source, String confidence, String topologyHash,
                      boolean complete, List<NodeView> nodes) {
      this.pipeline = pipeline;
      this.source = source;
      this.confidence = confidence;
      this.topologyHash = topologyHash;
      this.complete = complete;
      this.nodes = nodes;
    }

    static GraphView notPipeline() {
      return new GraphView(false, "", "", "", true, Collections.<NodeView>emptyList());
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
          graph.isComplete(),
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
    final String hierarchyParentId;
    final boolean synthetic;

    NodeView(JenkinsPipelineGraphNode node) {
      this.id = node.getId();
      this.name = node.getName();
      this.status = node.getStatus();
      this.startTimeMillis = node.getStartTimeMillis();
      this.durationMillis = node.getDurationMillis();
      this.parents = new ArrayList<String>(node.getParentIds());
      this.children = new ArrayList<String>(node.getChildIds());
      this.hierarchyParentId = node.getHierarchyParentId();
      this.synthetic = node.isSynthetic();
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

  /** JSON shape for a stage's per-step breakdown (G3b), including Jenkins' stored step arguments. */
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
    final String parameterDescription;
    final String status;
    final long durationMillis;
    final String log;

    StepView(JenkinsStageStep step) {
      this.id = step.getId();
      this.name = step.getName();
      this.parameterDescription = step.getParameterDescription();
      this.status = step.getStatus();
      this.durationMillis = step.getDurationMillis();
      this.log = step.getLog();
    }
  }
}
