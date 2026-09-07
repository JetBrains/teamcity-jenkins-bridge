package com.jetbrains.teamcity.jenkinsbridge.feature;

import jetbrains.buildServer.serverSide.BuildPromotionEx;
import jetbrains.buildServer.serverSide.BuildTypeEx;
import jetbrains.buildServer.serverSide.QueuedBuildEx;
import jetbrains.buildServer.serverSide.SBuildFeatureDescriptor;
import org.junit.Test;

import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class JenkinsQueueWaitReasonPreconditionTest {
  private final JenkinsQueueWaitReasonPrecondition precondition =
      new JenkinsQueueWaitReasonPrecondition();
  private final QueuedBuildEx queuedBuild = mock(QueuedBuildEx.class);
  private final BuildPromotionEx promotion = mock(BuildPromotionEx.class);
  private final BuildTypeEx buildType = mock(BuildTypeEx.class);

  public JenkinsQueueWaitReasonPreconditionTest() {
    when(queuedBuild.getBuildPromotion()).thenReturn(promotion);
    when(promotion.getBuildType()).thenReturn(buildType);
    when(promotion.getCustomParameters()).thenReturn(Collections.emptyMap());
    when(promotion.isAgentLessBuild()).thenReturn(true);
    when(buildType.getBuildFeaturesOfType(BridgeBuildFeatureConstants.TYPE))
        .thenReturn(Collections.singletonList(mock(SBuildFeatureDescriptor.class)));
  }

  @Test
  public void explainsThatTheJenkinsBuildIsStillStarting() {
    assertEquals(JenkinsQueueWaitReasonPrecondition.WAITING_FOR_JENKINS_BUILD,
        precondition.canStart(queuedBuild, Collections.emptyMap(), mock(
            jetbrains.buildServer.serverSide.buildDistribution.BuildDistributorInput.class), false)
            .getDescription());
  }

  @Test
  public void ignoresPromotionsAlreadyBoundToJenkins() {
    when(promotion.getCustomParameters()).thenReturn(Collections.singletonMap(
        BridgeBuildFeatureConstants.JENKINS_BUILD_KEY_PARAM, "job#42@123"));

    assertNull(precondition.canStart(queuedBuild, Collections.emptyMap(), mock(
        jetbrains.buildServer.serverSide.buildDistribution.BuildDistributorInput.class), false));
  }

  @Test
  public void ignoresNonAgentlessOrNonBridgePromotions() {
    when(promotion.isAgentLessBuild()).thenReturn(false);
    assertNull(precondition.canStart(queuedBuild, Collections.emptyMap(), mock(
        jetbrains.buildServer.serverSide.buildDistribution.BuildDistributorInput.class), false));

    when(promotion.isAgentLessBuild()).thenReturn(true);
    when(buildType.getBuildFeaturesOfType(BridgeBuildFeatureConstants.TYPE)).thenReturn(Collections.emptyList());
    assertNull(precondition.canStart(queuedBuild, Collections.emptyMap(), mock(
        jetbrains.buildServer.serverSide.buildDistribution.BuildDistributorInput.class), false));
  }
}
