package com.jetbrains.teamcity.jenkinsbridge.feature;

import jetbrains.buildServer.AgentRestrictor;
import jetbrains.buildServer.serverSide.BuildPromotion;
import jetbrains.buildServer.serverSide.SBuildFeatureDescriptor;
import jetbrains.buildServer.serverSide.SBuildType;
import org.junit.Test;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertSame;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Covers the guard paths that don't require the internal {@code BuildPromotionEx} type (which Mockito
 * cannot mock here — its signatures reference server-core types absent from the test classpath). The
 * actual agentless mutation on a {@code BuildPromotionEx} is verified live in a running TeamCity.
 */
public class JenkinsQueueAgentlessPreprocessorTest {

  private final JenkinsQueueAgentlessPreprocessor preprocessor = new JenkinsQueueAgentlessPreprocessor();

  @Test
  public void returnsSameMapAndToleratesEmpty() {
    Map<BuildPromotion, AgentRestrictor> empty = new LinkedHashMap<BuildPromotion, AgentRestrictor>();
    assertSame(empty, preprocessor.preprocess(empty, "user"));
  }

  @Test
  public void nonBridgePromotionIsLeftUntouched() {
    BuildPromotion promotion = mock(BuildPromotion.class);
    SBuildType buildType = mock(SBuildType.class);
    doReturn(buildType).when(promotion).getBuildType();
    when(buildType.getBuildFeaturesOfType(BridgeBuildFeatureConstants.TYPE))
        .thenReturn(Collections.<SBuildFeatureDescriptor>emptyList());

    Map<BuildPromotion, AgentRestrictor> map = new LinkedHashMap<BuildPromotion, AgentRestrictor>();
    map.put(promotion, null);

    // No bridge feature -> returns unchanged, never reaches the BuildPromotionEx cast, never throws.
    assertSame(map, preprocessor.preprocess(map, "user"));
  }

  @Test
  public void promotionWithoutBuildTypeIsIgnored() {
    BuildPromotion promotion = mock(BuildPromotion.class);
    doReturn(null).when(promotion).getBuildType();

    Map<BuildPromotion, AgentRestrictor> map = new LinkedHashMap<BuildPromotion, AgentRestrictor>();
    map.put(promotion, null);

    assertSame(map, preprocessor.preprocess(map, "user"));
  }
}
