package com.jetbrains.teamcity.jenkinsbridge.persistence;

import com.jetbrains.teamcity.jenkinsbridge.feature.BridgeBuildFeatureConstants;
import jetbrains.buildServer.parameters.ParametersProvider;
import jetbrains.buildServer.serverSide.BuildPromotion;
import jetbrains.buildServer.serverSide.SBuild;
import org.junit.Test;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class BuildResultMetadataResolverTest {
  @Test
  public void resolvesJenkinsFirstBuildByKeyParam() throws Exception {
    BuildMirrorStore store = mock(BuildMirrorStore.class);
    BuildResultMetadata metadata = mock(BuildResultMetadata.class);
    when(store.findResultMetadataByJenkinsBuildKey("job#5@ts")).thenReturn(metadata);

    assertSame(metadata, new BuildResultMetadataResolver(store).resolve(buildWith("job#5@ts", 100L, 100L)));
  }

  @Test
  public void resolvesTcFirstBuildByPromotionId() throws Exception {
    BuildMirrorStore store = mock(BuildMirrorStore.class);
    BuildResultMetadata metadata = mock(BuildResultMetadata.class);
    when(store.findResultMetadataByTeamCityBuildId(77L)).thenReturn(metadata);

    assertSame(metadata, new BuildResultMetadataResolver(store).resolve(buildWith(null, 77L, 77L)));
  }

  @Test
  public void resolvesViaBuildIdFallbackWhenPromotionIdMisses() throws Exception {
    BuildMirrorStore store = mock(BuildMirrorStore.class);
    BuildResultMetadata metadata = mock(BuildResultMetadata.class);
    when(store.findResultMetadataByTeamCityBuildId(99L)).thenReturn(metadata);

    assertSame(metadata, new BuildResultMetadataResolver(store).resolve(buildWith(null, 88L, 99L)));
  }

  @Test
  public void returnsNullWhenNoMetadataExists() throws Exception {
    BuildMirrorStore store = mock(BuildMirrorStore.class);
    assertNull(new BuildResultMetadataResolver(store).resolve(buildWith(null, 1L, 1L)));
  }

  @Test
  public void staleKeyParamFallsThroughToPromotionIdMatch() throws Exception {
    BuildMirrorStore store = mock(BuildMirrorStore.class);
    BuildResultMetadata metadata = mock(BuildResultMetadata.class);
    when(store.findResultMetadataByTeamCityBuildId(55L)).thenReturn(metadata);

    assertSame(metadata, new BuildResultMetadataResolver(store).resolve(buildWith("stale-key", 55L, 55L)));
  }

  @Test
  public void blankKeyParamSkipsPrimaryPath() throws Exception {
    BuildMirrorStore store = mock(BuildMirrorStore.class);
    BuildResultMetadata metadata = mock(BuildResultMetadata.class);
    when(store.findResultMetadataByTeamCityBuildId(33L)).thenReturn(metadata);

    assertSame(metadata, new BuildResultMetadataResolver(store).resolve(buildWith("   ", 33L, 33L)));
  }

  private static SBuild buildWith(String keyParam, long promotionId, long buildId) {
    ParametersProvider params = mock(ParametersProvider.class);
    when(params.get(BridgeBuildFeatureConstants.JENKINS_BUILD_KEY_PARAM)).thenReturn(keyParam);
    BuildPromotion promotion = mock(BuildPromotion.class);
    when(promotion.getId()).thenReturn(promotionId);
    SBuild build = mock(SBuild.class);
    when(build.getParametersProvider()).thenReturn(params);
    when(build.getBuildPromotion()).thenReturn(promotion);
    when(build.getBuildId()).thenReturn(buildId);
    return build;
  }
}
