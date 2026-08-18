package com.jetbrains.teamcity.jenkinsbridge.teamcity;

import jetbrains.buildServer.serverSide.BuildPromotion;
import jetbrains.buildServer.serverSide.BuildsManager;
import jetbrains.buildServer.serverSide.QueuedBuildEx;
import jetbrains.buildServer.serverSide.RunningBuildEx;
import jetbrains.buildServer.serverSide.SBuild;
import org.junit.Before;
import org.junit.Test;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class TeamCityBuildStarterTest {
  private static final long BUILD_ID = 7L;
  private static final String REQUESTOR = "Monitoring Jenkins job my-pipeline build #1";

  private final BuildsManager buildsManager = mock(BuildsManager.class);
  private final TeamCityRunningBuildLocator buildLocator = mock(TeamCityRunningBuildLocator.class);
  private final BuildPromotion promotion = mock(BuildPromotion.class);
  private final QueuedBuildEx queuedBuild = mock(QueuedBuildEx.class);
  private final RunningBuildEx runningBuild = mock(RunningBuildEx.class);

  private final TeamCityBuildStarter starter = new TeamCityBuildStarter(buildsManager, buildLocator);

  @Before
  public void setUp() {
    when(buildLocator.findPromotion(BUILD_ID)).thenReturn(promotion);
    when(promotion.getQueuedBuild()).thenReturn(queuedBuild);
    when(queuedBuild.startBuild(anyString())).thenReturn(runningBuild);
  }

  @Test
  public void markBuildAsRunningDetachesAgentlessBuildFromAgent() {
    starter.markBuildAsRunning(BUILD_ID, REQUESTOR);

    verify(queuedBuild).startBuild(REQUESTOR);
    verify(runningBuild).detachedFromAgent();
  }

  @Test
  public void markBuildAsRunningDoesNotDetachCompositeBuild() {
    when(runningBuild.isCompositeBuild()).thenReturn(true);

    starter.markBuildAsRunning(BUILD_ID, REQUESTOR);

    verify(runningBuild, never()).detachedFromAgent();
  }

  @Test
  public void markBuildAsRunningDoesNothingWhenBuildIsAlreadyStarted() {
    when(buildsManager.findBuildInstanceById(BUILD_ID)).thenReturn(mock(SBuild.class));

    starter.markBuildAsRunning(BUILD_ID, REQUESTOR);

    verify(queuedBuild, never()).startBuild(anyString());
  }
}
