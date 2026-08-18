package com.jetbrains.teamcity.jenkinsbridge.teamcity;

import com.jetbrains.teamcity.jenkinsbridge.model.JenkinsPipelineGraph;
import com.jetbrains.teamcity.jenkinsbridge.persistence.BuildMirror;
import com.jetbrains.teamcity.jenkinsbridge.persistence.PipelineChainMirror;
import com.jetbrains.teamcity.jenkinsbridge.persistence.PipelineChainNodeMirror;
import jetbrains.buildServer.serverSide.BuildCustomizer;
import jetbrains.buildServer.serverSide.BuildCustomizerEx;
import jetbrains.buildServer.serverSide.BuildCustomizerFactory;
import jetbrains.buildServer.serverSide.BuildPromotion;
import jetbrains.buildServer.serverSide.BuildPromotionEx;
import jetbrains.buildServer.serverSide.DuplicateBuildTypeNameException;
import jetbrains.buildServer.serverSide.ProjectManager;
import jetbrains.buildServer.serverSide.SBuildType;
import jetbrains.buildServer.serverSide.SProject;
import jetbrains.buildServer.serverSide.SQueuedBuild;
import jetbrains.buildServer.serverSide.dependency.DependenciesSupplier;
import jetbrains.buildServer.serverSide.dependency.Dependency;
import jetbrains.buildServer.serverSide.dependency.DependencyFactory;
import jetbrains.buildServer.serverSide.dependency.DependencyOptions;
import jetbrains.buildServer.serverSide.impl.ProjectEx;
import jetbrains.buildServer.serverSide.parameters.ParameterFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.jetbrains.teamcity.jenkinsbridge.util.Utilities.findBuildType;
import static com.jetbrains.teamcity.jenkinsbridge.util.Utilities.nullToEmpty;

public class TeamCityPipelineChainService {
  private static final String TRIGGERED_BY = "Jenkins Bridge Pipeline Chain";
  private static final String AGENTLESS_PARAM = "teamcity.build.agentLess";
  private static final String GENERATED_PARAM = "jenkins.bridge.generated.chain";
  private static final String GENERATED_PROJECT_SUFFIX = "_JenkinsBridgeGenerated_virtual";
  /** Per-build-config toggle: create the native TeamCity chain for this job instead of a single build. */
  private static final String CHAIN_ENABLED_PARAM = "jenkins.bridge.pipelineChain.enabled";

  private final ProjectManager projectManager;
  private final ParameterFactory parameterFactory;
  private final DependencyFactory dependencyFactory;
  private final BuildCustomizerFactory buildCustomizerFactory;
  private final PipelineChainPlanner planner;

  public TeamCityPipelineChainService(
      ProjectManager projectManager,
      ParameterFactory parameterFactory,
      DependencyFactory dependencyFactory,
      BuildCustomizerFactory buildCustomizerFactory
  ) {
    this(projectManager, parameterFactory, dependencyFactory, buildCustomizerFactory, new PipelineChainPlanner());
  }

  TeamCityPipelineChainService(
      ProjectManager projectManager,
      ParameterFactory parameterFactory,
      DependencyFactory dependencyFactory,
      BuildCustomizerFactory buildCustomizerFactory,
      PipelineChainPlanner planner
  ) {
    this.projectManager = projectManager;
    this.parameterFactory = parameterFactory;
    this.dependencyFactory = dependencyFactory;
    this.buildCustomizerFactory = buildCustomizerFactory;
    this.planner = planner;
  }

  public PipelineChainMirror ensureChain(BuildMirror mirror, JenkinsPipelineGraph graph)
      throws TeamCityPipelineChainException {
    // Per-build-config toggle. When off (default), return null -> the caller mirrors a single live build
    // (+ the Pipeline Graph tab). When on, we build the native TeamCity chain live and update node
    // statuses each poll. Lets one job demo the chain while another demos the single-build + tab.
    SBuildType sourceBuildType = findBuildType(mirror.getTeamCityBuildTypeId(), projectManager);
    if (sourceBuildType == null || !isChainEnabled(sourceBuildType)) {
      return null;
    }
    if (!planner.canCreateNativeChain(graph)) {
      return null;
    }
    PipelineChainMirror existing = mirror.getPipelineChain();
    if (existing != null && existing.matchesQueuedTopology(graph.getTopologyHash())) {
      return existing;
    }

    PipelineChainPlan plan = planner.plan(mirror, sourceBuildType.getExternalId(), graph);
    if (plan.getTerminalNodeIds().isEmpty()) {
      throw new TeamCityPipelineChainException("Pipeline graph has no terminal node to queue");
    }

    SProject generatedProject = ensureGeneratedProject(sourceBuildType.getProject());
    Map<String, SBuildType> buildTypesByNodeId = ensureBuildTypes(mirror, graph, plan, generatedProject);
    return queueSourceBuild(mirror, plan, sourceBuildType, buildTypesByNodeId);
  }

  /**
   * Adds a completed Jenkins pipeline's graph to an already-finished TeamCity-first promotion.
   *
   * <p>This is deliberately retrospective: the top promotion has already mirrored Jenkins and
   * will not be used to gate execution. TeamCity's dependency mutation API is an internal API and
   * may warn when used after start, but it is the only way to preserve the original TeamCity-first
   * promotion as the top of the historical chain.</p>
   */
  public PipelineChainMirror retrofitFinishedChain(
      BuildMirror mirror,
      JenkinsPipelineGraph graph,
      BuildPromotion topPromotion
  ) throws TeamCityPipelineChainException {
    SBuildType sourceBuildType = findBuildType(mirror.getTeamCityBuildTypeId(), projectManager);
    if (sourceBuildType == null || !isChainEnabled(sourceBuildType)
        || !planner.canCreateNativeChain(graph)) {
      return null;
    }
    if (!(topPromotion instanceof BuildPromotionEx)) {
      throw new TeamCityPipelineChainException("TeamCity top promotion does not support retrospective dependencies");
    }

    PipelineChainMirror existing = mirror.getPipelineChain();
    if (existing != null && existing.matchesQueuedTopology(graph.getTopologyHash())) {
      return existing;
    }

    PipelineChainPlan plan = planner.plan(mirror, sourceBuildType.getExternalId(), graph);
    if (plan.getTerminalNodeIds().isEmpty()) {
      throw new TeamCityPipelineChainException("Pipeline graph has no terminal node to attach");
    }

    SProject generatedProject = ensureGeneratedProject(sourceBuildType.getProject());
    Map<String, SBuildType> buildTypesByNodeId = ensureBuildTypes(mirror, graph, plan, generatedProject);
    Map<String, BuildPromotionEx> promotionsByNodeId = new LinkedHashMap<String, BuildPromotionEx>();

    for (PipelineChainPlan.Node node : plan.getNodes()) {
      SBuildType nodeBuildType = buildTypesByNodeId.get(node.getNodeId());
      BuildCustomizer customizer = buildCustomizerFactory.createBuildCustomizer(nodeBuildType, null);
      customizer.setParameters(TeamCityBuildParameters.mergeWithJenkinsParameters(
          nodeBuildParameters(mirror, node),
          mirror.getJenkinsBuildParameters(),
          nodeBuildType.getParametersProvider().getAll().keySet()));
      BuildPromotion promotion = customizer.createPromotion();
      if (!(promotion instanceof BuildPromotionEx)) {
        throw new TeamCityPipelineChainException("Generated Pipeline promotion does not support dependencies");
      }
      promotionsByNodeId.put(node.getNodeId(), (BuildPromotionEx) promotion);
    }

    for (PipelineChainPlan.Node node : plan.getNodes()) {
      BuildPromotionEx promotion = promotionsByNodeId.get(node.getNodeId());
      for (String parentNodeId : node.getParentNodeIds()) {
        BuildPromotionEx parent = promotionsByNodeId.get(parentNodeId);
        if (parent == null) {
          throw new TeamCityPipelineChainException("Pipeline node " + node.getNodeId()
              + " references missing parent node " + parentNodeId);
        }
        promotion.addDependency(parent, snapshotDependency(parent.getBuildTypeExternalId()));
      }
      promotion.persistDependencies();
    }

    BuildPromotionEx top = (BuildPromotionEx) topPromotion;
    List<Long> terminalPromotionIds = new ArrayList<Long>();
    for (String terminalNodeId : plan.getTerminalNodeIds()) {
      BuildPromotionEx terminal = promotionsByNodeId.get(terminalNodeId);
      if (terminal == null) {
        throw new TeamCityPipelineChainException("Pipeline terminal node " + terminalNodeId + " is missing");
      }
      top.addDependency(terminal, snapshotDependency(terminal.getBuildTypeExternalId()));
      terminalPromotionIds.add(terminal.getId());
    }
    top.persistDependencies();

    Map<String, PipelineChainNodeMirror> nodeMirrors =
        new LinkedHashMap<String, PipelineChainNodeMirror>();
    for (PipelineChainPlan.Node node : plan.getNodes()) {
      BuildPromotionEx promotion = promotionsByNodeId.get(node.getNodeId());
      nodeMirrors.put(node.getNodeId(), new PipelineChainNodeMirror(
          node.getNodeId(), node.getFlowId(), node.getBuildTypeExternalId(), promotion.getId()));
      promotion.addToQueue(TRIGGERED_BY);
    }

    return new PipelineChainMirror(
        plan.getTopologyHash(),
        String.valueOf(plan.getConfidence()),
        sourceBuildType.getExternalId(),
        topPromotion.getId(),
        nodeMirrors,
        plan.getTerminalNodeIds(),
        terminalPromotionIds,
        true);
  }

  private SProject ensureGeneratedProject(SProject sourceProject)
      throws TeamCityPipelineChainException {
    String externalId = truncate(sourceProject.getExternalId() + GENERATED_PROJECT_SUFFIX, 225);
    SProject existing = projectManager.findProjectByExternalId(externalId);
    if (existing != null) {
      if (!existing.isVirtual()) {
        throw new TeamCityPipelineChainException("Jenkins Bridge generated project " + externalId
            + " already exists but is not a virtual TeamCity project");
      }
      return existing;
    }
    if (!(sourceProject instanceof ProjectEx)) {
      throw new TeamCityPipelineChainException("TeamCity project " + sourceProject.getExternalId()
          + " does not support virtual generated projects");
    }
    ProjectEx parent = (ProjectEx) sourceProject;
    SProject generated = parent.createVirtualProject(externalId, sourceProject.getName() + " (Jenkins Bridge generated)");
    generated.setDescription("Internal virtual project for Jenkins Bridge Pipeline chain demo builds.");
    generated.persist();
    return generated;
  }

  private Map<String, SBuildType> ensureBuildTypes(
      BuildMirror mirror,
      JenkinsPipelineGraph graph,
      PipelineChainPlan plan,
      SProject project
  ) throws TeamCityPipelineChainException {
    Map<String, SBuildType> buildTypesByNodeId = new LinkedHashMap<String, SBuildType>();
    for (PipelineChainPlan.Node node : plan.getNodes()) {
      SBuildType buildType = projectManager.findBuildTypeByExternalId(node.getBuildTypeExternalId());
      if (buildType == null) {
        buildType = createBuildType(project, mirror, node);
      } else if (!buildType.belongsTo(project)) {
        throw new TeamCityPipelineChainException("Generated Pipeline build type " + node.getBuildTypeExternalId()
            + " belongs to a different TeamCity project");
      }

      setConfigParameter(buildType, AGENTLESS_PARAM, "true");
      setConfigParameter(buildType, GENERATED_PARAM, "true");
      setConfigParameter(buildType, "jenkins.job", mirror.getJenkinsJob());
      setConfigParameter(buildType, "jenkins.build.number", String.valueOf(mirror.getJenkinsBuildNumber()));
      setConfigParameter(buildType, "jenkins.build.key", mirror.getJenkinsBuildKey());
      setConfigParameter(buildType, "jenkins.build.timestamp", String.valueOf(mirror.getJenkinsBuildTimestamp()));
      setConfigParameter(buildType, "jenkins.build.url", nullToEmpty(mirror.getJenkinsBuildUrl()));
      setConfigParameter(buildType, "jenkins.flow.id", node.getFlowId());
      setConfigParameter(buildType, "jenkins.flow.node.id", node.getNodeId());
      setConfigParameter(buildType, "jenkins.flow.name", node.getName());
      setConfigParameter(buildType, "jenkins.pipeline.source", graph.getSource());
      setConfigParameter(buildType, "jenkins.pipeline.topologyHash", graph.getTopologyHash());
      setConfigParameter(buildType, "jenkins.pipeline.sourceBuildType", plan.getSourceBuildTypeExternalId());
      buildType.persist();

      buildTypesByNodeId.put(node.getNodeId(), buildType);
    }
    return buildTypesByNodeId;
  }

  private SBuildType createBuildType(SProject project, BuildMirror mirror, PipelineChainPlan.Node node)
      throws TeamCityPipelineChainException {
    String baseName = "Jenkins " + mirror.getJenkinsJob() + " #" + mirror.getJenkinsBuildNumber()
        + " - " + node.getName();
    for (int attempt = 0; attempt < 100; attempt++) {
      String suffix = attempt == 0 ? "" : " " + attempt;
      String name = truncate(baseName + suffix, 80);
      try {
        SBuildType buildType = project.createBuildType(node.getBuildTypeExternalId(), name);
        buildType.setDescription("Generated by Jenkins Bridge for " + mirror.getJenkinsBuildKey()
            + " flow node " + node.getNodeId() + ".");
        return buildType;
      } catch (DuplicateBuildTypeNameException e) {
        // Retry with a numeric suffix; the external id remains deterministic for this Jenkins node.
      }
    }
    throw new TeamCityPipelineChainException("Could not find an available TeamCity build type name for "
        + node.getBuildTypeExternalId());
  }

  private DependenciesSupplier dependenciesSupplier(
      PipelineChainPlan plan,
      Map<String, SBuildType> buildTypesByNodeId,
      SBuildType sourceBuildType
  ) throws TeamCityPipelineChainException {
    final Map<String, List<Dependency>> dependenciesByInternalBuildTypeId =
        new LinkedHashMap<String, List<Dependency>>();

    List<Dependency> topDependencies = new ArrayList<Dependency>();
    for (String terminalNodeId : plan.getTerminalNodeIds()) {
      SBuildType terminalBuildType = buildTypesByNodeId.get(terminalNodeId);
      if (terminalBuildType == null) {
        throw new TeamCityPipelineChainException("Pipeline terminal node " + terminalNodeId + " is missing a build type");
      }
      topDependencies.add(snapshotDependency(terminalBuildType.getExternalId()));
    }
    dependenciesByInternalBuildTypeId.put(sourceBuildType.getInternalId(), topDependencies);

    for (PipelineChainPlan.Node node : plan.getNodes()) {
      SBuildType nodeBuildType = buildTypesByNodeId.get(node.getNodeId());
      if (nodeBuildType == null) {
        throw new TeamCityPipelineChainException("Pipeline node " + node.getNodeId() + " is missing a build type");
      }
      List<Dependency> dependencies = new ArrayList<Dependency>();
      for (String parentNodeId : node.getParentNodeIds()) {
        SBuildType parentBuildType = buildTypesByNodeId.get(parentNodeId);
        if (parentBuildType == null) {
          throw new TeamCityPipelineChainException("Pipeline node " + node.getNodeId()
              + " references missing parent node " + parentNodeId);
        }
        dependencies.add(snapshotDependency(parentBuildType.getExternalId()));
      }
      dependenciesByInternalBuildTypeId.put(nodeBuildType.getInternalId(), dependencies);
    }

    return new DependenciesSupplier() {
      @Override
      public List<Dependency> getDependencies(String dependentBuildTypeId) {
        List<Dependency> dependencies = dependenciesByInternalBuildTypeId.get(dependentBuildTypeId);
        return dependencies == null ? Collections.<Dependency>emptyList() : dependencies;
      }
    };
  }

  private PipelineChainMirror queueSourceBuild(
      BuildMirror mirror,
      PipelineChainPlan plan,
      SBuildType sourceBuildType,
      Map<String, SBuildType> buildTypesByNodeId
  ) throws TeamCityPipelineChainException {
    Map<String, PipelineChainNodeMirror> nodeMirrors =
        new LinkedHashMap<String, PipelineChainNodeMirror>();
    for (PipelineChainPlan.Node node : plan.getNodes()) {
      nodeMirrors.put(node.getNodeId(), new PipelineChainNodeMirror(
          node.getNodeId(), node.getFlowId(), node.getBuildTypeExternalId(), null));
    }

    List<Long> terminalPromotionIds = new ArrayList<Long>();
    BuildCustomizer customizer = buildCustomizerFactory.createBuildCustomizer(sourceBuildType, null);
    if (!(customizer instanceof BuildCustomizerEx)) {
      throw new TeamCityPipelineChainException("TeamCity BuildCustomizer does not support dynamic Jenkins Pipeline dependencies");
    }
    customizer.setParameters(TeamCityBuildParameters.mergeWithJenkinsParameters(
        topBuildParameters(mirror),
        mirror.getJenkinsBuildParameters(),
        sourceBuildType.getParametersProvider().getAll().keySet()));
    customizer.setRebuildDependencies(true);
    ((BuildCustomizerEx) customizer).setDependenciesSupplier(
        dependenciesSupplier(plan, buildTypesByNodeId, sourceBuildType));
    BuildPromotion promotion = customizer.createPromotion();
    SQueuedBuild queuedBuild = promotion.addToQueue(TRIGGERED_BY);
    if (queuedBuild == null) {
      throw new TeamCityPipelineChainException("Failed to queue Pipeline source build type "
          + plan.getSourceBuildTypeExternalId());
    }

    BuildPromotion topPromotion = queuedBuild.getBuildPromotion();
    for (BuildPromotion dependencyPromotion : topPromotion.getAllDependencies()) {
      PipelineChainPlan.Node node = recordPromotion(plan, nodeMirrors, dependencyPromotion);
      if (node != null && node.isTerminal()) {
        terminalPromotionIds.add(dependencyPromotion.getId());
      }
    }

    return new PipelineChainMirror(
        plan.getTopologyHash(),
        String.valueOf(plan.getConfidence()),
        sourceBuildType.getExternalId(),
        topPromotion.getId(),
        nodeMirrors,
        plan.getTerminalNodeIds(),
        terminalPromotionIds,
        true);
  }

  private Dependency snapshotDependency(String buildTypeExternalId) {
    Dependency dependency = dependencyFactory.createDependency(buildTypeExternalId);
    dependency.setOption(DependencyOptions.SYNCHRONIZE_REVISIONS, Boolean.FALSE);
    dependency.setOption(DependencyOptions.TAKE_STARTED_BUILD_WITH_SAME_REVISIONS, Boolean.FALSE);
    dependency.setOption(DependencyOptions.TAKE_SUCCESSFUL_BUILDS_ONLY, Boolean.FALSE);
    dependency.setOption(
        DependencyOptions.RUN_BUILD_IF_DEPENDENCY_FAILED,
        DependencyOptions.BuildContinuationMode.RUN);
    dependency.setOption(
        DependencyOptions.RUN_BUILD_IF_DEPENDENCY_FAILED_TO_START,
        DependencyOptions.BuildContinuationMode.RUN);
    return dependency;
  }

  private Map<String, String> nodeBuildParameters(BuildMirror mirror, PipelineChainPlan.Node node) {
    Map<String, String> parameters = new LinkedHashMap<String, String>();
    parameters.put("jenkins.job", mirror.getJenkinsJob());
    parameters.put("jenkins.build.number", String.valueOf(mirror.getJenkinsBuildNumber()));
    parameters.put("jenkins.build.key", mirror.getJenkinsBuildKey());
    parameters.put("jenkins.flow.id", node.getFlowId());
    parameters.put("jenkins.flow.node.id", node.getNodeId());
    parameters.put("jenkins.flow.name", node.getName());
    return parameters;
  }

  private Map<String, String> topBuildParameters(BuildMirror mirror) {
    Map<String, String> parameters = new LinkedHashMap<String, String>();
    parameters.put("jenkins.job", mirror.getJenkinsJob());
    parameters.put("jenkins.build.number", String.valueOf(mirror.getJenkinsBuildNumber()));
    parameters.put("jenkins.build.timestamp", String.valueOf(mirror.getJenkinsBuildTimestamp()));
    parameters.put("jenkins.build.key", mirror.getJenkinsBuildKey());
    parameters.put("jenkins.build.url", nullToEmpty(mirror.getJenkinsBuildUrl()));
    return parameters;
  }

  private PipelineChainPlan.Node recordPromotion(
      PipelineChainPlan plan,
      Map<String, PipelineChainNodeMirror> nodeMirrors,
      BuildPromotion promotion
  ) {
    PipelineChainPlan.Node node = plan.findNodeByBuildTypeExternalId(promotion.getBuildTypeExternalId());
    if (node == null) {
      return null;
    }
    nodeMirrors.put(node.getNodeId(), new PipelineChainNodeMirror(
        node.getNodeId(), node.getFlowId(), node.getBuildTypeExternalId(), promotion.getId()));
    return node;
  }

  private void setConfigParameter(SBuildType buildType, String name, String value) {
    if (buildType.getConfigParameters().containsKey(name)) {
      buildType.removeConfigParameter(name);
    }
    buildType.addConfigParameter(parameterFactory.createSimpleParameter(name, nullToEmpty(value)));
  }

  /** True if the given build config opts into the native chain via the per-build-config toggle. */
  public boolean isChainEnabled(String buildTypeId) {
    SBuildType buildType = findBuildType(buildTypeId, projectManager);
    return buildType != null && isChainEnabled(buildType);
  }

  private boolean isChainEnabled(SBuildType buildType) {
    return "true".equalsIgnoreCase(buildType.getParametersProvider().get(CHAIN_ENABLED_PARAM));
  }

  private static String truncate(String value, int maxLength) {
    if (value == null) {
      return "";
    }
    return value.length() <= maxLength ? value : value.substring(0, maxLength);
  }
}
