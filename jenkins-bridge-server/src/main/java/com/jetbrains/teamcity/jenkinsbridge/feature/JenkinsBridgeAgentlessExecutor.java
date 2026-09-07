package com.jetbrains.teamcity.jenkinsbridge.feature;

import jetbrains.buildServer.serverSide.BuildPromotion;
import jetbrains.buildServer.serverSide.ProjectManager;
import jetbrains.buildServer.serverSide.RunTypeRegistry;
import jetbrains.buildServer.serverSide.SBuildType;
import jetbrains.buildServer.agentServer.AgentDetails;
import jetbrains.buildServer.serverSide.impl.AgentDescriptionFactory;
import jetbrains.buildServer.serverSide.impl.executors.ExecutorDescriptionFetcher;
import jetbrains.buildServer.serverSide.agentless.AgentlessBuildExecutor;
import jetbrains.buildServer.serverSide.agentless.AgentlessBuildStartResult;
import jetbrains.buildServer.serverSide.buildDistribution.SimpleWaitReason;
import jetbrains.buildServer.clouds.server.executors.BuildExecutorDescriptor;
import jetbrains.buildServer.clouds.server.executors.BuildExecutorsManager;
import jetbrains.buildServer.vcs.VcsManager;
import org.jetbrains.annotations.NotNull;

import java.util.stream.Collectors;

/**
 * Supplies the queue explanation for TeamCity-first Jenkins Bridge promotions.
 *
 * <p>The promotion is intentionally kept in TeamCity's queue until the bridge resolves the
 * external Jenkins queue item and starts the detached TeamCity build. Without an executor-specific
 * wait result, TeamCity falls back to its generic agent-compatibility text, which is misleading for
 * an agentless build.</p>
 *
 * <p>This is a spike for the queue UX. The bridge's existing {@code TeamCityBuildStarter} remains
 * responsible for starting the promotion once Jenkins has produced a build.</p>
 */
public class JenkinsBridgeAgentlessExecutor implements AgentlessBuildExecutor {
  public static final String EXECUTOR_NAME = "Jenkins Bridge";
  public static final String WAITING_FOR_JENKINS_BUILD = "Waiting for Jenkins build to start";

  private final BuildExecutorsManager executorsManager;
  private final ProjectManager projectManager;
  private final ExecutorDescriptionFetcher descriptionFetcher;
  private final AgentDescriptionFactory descriptionFactory;
  private final RunTypeRegistry runTypeRegistry;
  private final VcsManager vcsManager;

  /** Test-friendly constructor; TeamCity uses the dependency-injected constructor. */
  public JenkinsBridgeAgentlessExecutor() {
    this(null, null, null, null, null, null);
  }

  public JenkinsBridgeAgentlessExecutor(
      BuildExecutorsManager executorsManager,
      ProjectManager projectManager,
      ExecutorDescriptionFetcher descriptionFetcher,
      AgentDescriptionFactory descriptionFactory,
      RunTypeRegistry runTypeRegistry,
      VcsManager vcsManager) {
    this.executorsManager = executorsManager;
    this.projectManager = projectManager;
    this.descriptionFetcher = descriptionFetcher;
    this.descriptionFactory = descriptionFactory;
    this.runTypeRegistry = runTypeRegistry;
    this.vcsManager = vcsManager;
  }

  @Override
  public boolean supports(@NotNull BuildPromotion buildPromotion) {
    SBuildType buildType = buildPromotion.getBuildType();
    return buildType != null
        && !buildPromotion.getCustomParameters()
            .containsKey(BridgeBuildFeatureConstants.JENKINS_BUILD_KEY_PARAM)
        && !buildType.getBuildFeaturesOfType(BridgeBuildFeatureConstants.TYPE).isEmpty();
  }

  @Override
  @NotNull
  public String getExecutorType() {
    return JenkinsBridgeExecutorType.EXECUTOR_TYPE;
  }

  @Override
  @NotNull
  public AgentlessBuildStartResult checkCanStart(@NotNull BuildPromotion buildPromotion) {
    return new AgentlessBuildStartResult(
        EXECUTOR_NAME, new SimpleWaitReason(WAITING_FOR_JENKINS_BUILD));
  }

  /**
   * Publishes the executor description TeamCity needs before it considers this profile eligible.
   * The bridge is agentless, so it can accept every registered run type and VCS plugin.
   */
  @Override
  public void scheduleFetchParameters(@NotNull String projectId, @NotNull String executorId,
                                      @NotNull String token) {
    if (executorsManager == null || projectManager == null || descriptionFetcher == null
        || descriptionFactory == null || runTypeRegistry == null || vcsManager == null) {
      return;
    }
    jetbrains.buildServer.serverSide.SProject project = projectManager.findProjectById(projectId);
    if (project == null) {
      return;
    }
    BuildExecutorDescriptor descriptor = executorsManager.findExecutorById(project, executorId);
    if (descriptor == null) {
      return;
    }
    AgentDetails details = new AgentDetails("Jenkins Bridge", "localhost", 0, "", "");
    details.setAvailableRunners(runTypeRegistry.getRegisteredRunTypes().stream()
        .map(type -> type.getType()).collect(Collectors.toList()));
    details.setAvailableVcsPlugins(vcsManager.getAllVcsCore().stream()
        .map(vcs -> vcs.getName()).collect(Collectors.toList()));
    details.setOsName("Jenkins");
    descriptionFetcher.rememberParameters(token, descriptor, descriptionFactory.createDescription(details));
  }
}
