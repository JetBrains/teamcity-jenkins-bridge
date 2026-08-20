package com.jetbrains.teamcity.jenkinsbridge.teamcity;

import jetbrains.buildServer.BuildProblemData;
import jetbrains.buildServer.serverSide.BuildPromotion;
import jetbrains.buildServer.serverSide.QueuedBuildEx;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class TeamCityQueuedBuildFailureServiceTest {
  @Test
  public void terminatesQueuedPromotionAsNativeFailedToStart() {
    TeamCityRunningBuildLocator locator = mock(TeamCityRunningBuildLocator.class);
    BuildPromotion promotion = mock(BuildPromotion.class);
    QueuedBuildEx queuedBuild = mock(QueuedBuildEx.class);
    when(locator.findPromotion(42L)).thenReturn(promotion);
    when(promotion.getQueuedBuild()).thenReturn(queuedBuild);

    new TeamCityQueuedBuildFailureService(locator)
        .failQueuedPromotion(42L, "Jenkins trigger failed");

    ArgumentCaptor<BuildProblemData> problem = ArgumentCaptor.forClass(BuildProblemData.class);
    verify(queuedBuild).terminate(eq(false), eq("Jenkins trigger failed"), problem.capture());
    assertEquals(jetbrains.buildServer.messages.ErrorData.PREPARATION_FAILURE_TYPE,
        problem.getValue().getType());
    assertEquals("Jenkins trigger failed", problem.getValue().getDescription());
  }
}
