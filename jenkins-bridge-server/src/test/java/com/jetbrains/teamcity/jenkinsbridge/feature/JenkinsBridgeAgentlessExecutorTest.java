package com.jetbrains.teamcity.jenkinsbridge.feature;

import jetbrains.buildServer.serverSide.BuildPromotion;
import jetbrains.buildServer.serverSide.SBuildFeatureDescriptor;
import jetbrains.buildServer.serverSide.SBuildType;
import org.junit.Test;

import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class JenkinsBridgeAgentlessExecutorTest {
  private final JenkinsBridgeAgentlessExecutor executor = new JenkinsBridgeAgentlessExecutor();

  @Test
  public void supportsOnlyBuildsWithTheBridgeFeature() {
    BuildPromotion promotion = mock(BuildPromotion.class);
    SBuildType buildType = mock(SBuildType.class);
    when(promotion.getBuildType()).thenReturn(buildType);
    when(buildType.getBuildFeaturesOfType(BridgeBuildFeatureConstants.TYPE))
        .thenReturn(Collections.singletonList(mock(SBuildFeatureDescriptor.class)));

    assertTrue(executor.supports(promotion));

    when(buildType.getBuildFeaturesOfType(BridgeBuildFeatureConstants.TYPE))
        .thenReturn(Collections.<SBuildFeatureDescriptor>emptyList());
    assertFalse(executor.supports(promotion));
  }

  @Test
  public void doesNotClaimJenkinsFirstMirrorPromotions() {
    BuildPromotion promotion = mock(BuildPromotion.class);
    SBuildType buildType = mock(SBuildType.class);
    when(promotion.getBuildType()).thenReturn(buildType);
    when(promotion.getCustomParameters()).thenReturn(Collections.singletonMap(
        BridgeBuildFeatureConstants.JENKINS_BUILD_KEY_PARAM, "job#42@123"));
    when(buildType.getBuildFeaturesOfType(BridgeBuildFeatureConstants.TYPE))
        .thenReturn(Collections.singletonList(mock(SBuildFeatureDescriptor.class)));

    assertFalse(executor.supports(promotion));
  }

  @Test
  public void reportsThatTheJenkinsBuildIsStillStarting() {
    BuildPromotion promotion = mock(BuildPromotion.class);

    assertEquals(JenkinsBridgeAgentlessExecutor.EXECUTOR_NAME,
        executor.checkCanStart(promotion).getExecutorName());
    assertEquals(JenkinsBridgeAgentlessExecutor.WAITING_FOR_JENKINS_BUILD,
        executor.checkCanStart(promotion).getWaitReason().getDescription());
  }
}
