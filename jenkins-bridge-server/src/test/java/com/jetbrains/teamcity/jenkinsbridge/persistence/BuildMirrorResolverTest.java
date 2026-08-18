package com.jetbrains.teamcity.jenkinsbridge.persistence;

import com.jetbrains.teamcity.jenkinsbridge.feature.BridgeBuildFeatureConstants;
import jetbrains.buildServer.serverSide.BuildPromotion;
import jetbrains.buildServer.serverSide.SBuild;
import jetbrains.buildServer.parameters.ParametersProvider;
import org.junit.Test;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class BuildMirrorResolverTest {

  @Test
  public void resolvesJenkinsFirstBuildByKeyParam() throws Exception {
    BuildMirrorStore store = mock(BuildMirrorStore.class);
    BuildMirror mirror = mock(BuildMirror.class);
    when(store.findMirror("job#5@ts")).thenReturn(mirror);

    SBuild build = buildWith("job#5@ts", 100L, 100L);

    assertSame(mirror, new BuildMirrorResolver(store).resolve(build));
  }

  @Test
  public void resolvesTcFirstBuildByPromotionId() throws Exception {
    BuildMirrorStore store = mock(BuildMirrorStore.class);
    BuildMirror mirror = mock(BuildMirror.class);
    // No key param on the promotion; bound by promotion id.
    when(store.findMirrorByTcBuildId(77L)).thenReturn(mirror);

    SBuild build = buildWith(null, 77L, 77L);

    assertSame(mirror, new BuildMirrorResolver(store).resolve(build));
  }

  @Test
  public void resolvesViaBuildIdFallbackWhenPromotionIdMisses() throws Exception {
    BuildMirrorStore store = mock(BuildMirrorStore.class);
    BuildMirror mirror = mock(BuildMirror.class);
    when(store.findMirrorByTcBuildId(88L)).thenReturn(null); // promotion id miss
    when(store.findMirrorByTcBuildId(99L)).thenReturn(mirror); // build id hit (restore path)

    SBuild build = buildWith(null, 88L, 99L);

    assertSame(mirror, new BuildMirrorResolver(store).resolve(build));
  }

  @Test
  public void returnsNullWhenNoMirrorExists() throws Exception {
    BuildMirrorStore store = mock(BuildMirrorStore.class);
    when(store.findMirror(org.mockito.ArgumentMatchers.anyString())).thenReturn(null);
    when(store.findMirrorByTcBuildId(org.mockito.ArgumentMatchers.anyLong())).thenReturn(null);

    SBuild build = buildWith(null, 1L, 1L);

    assertNull(new BuildMirrorResolver(store).resolve(build));
  }

  @Test
  public void staleKeyParamFallsThroughToPromotionIdMatch() throws Exception {
    BuildMirrorStore store = mock(BuildMirrorStore.class);
    BuildMirror mirror = mock(BuildMirror.class);
    // Key present but the mirror was pruned; promotion-id binding still holds.
    when(store.findMirror("stale-key")).thenReturn(null);
    when(store.findMirrorByTcBuildId(55L)).thenReturn(mirror);

    SBuild build = buildWith("stale-key", 55L, 55L);

    assertSame(mirror, new BuildMirrorResolver(store).resolve(build));
  }

  @Test
  public void blankKeyParamSkipsPrimaryPath() throws Exception {
    BuildMirrorStore store = mock(BuildMirrorStore.class);
    BuildMirror mirror = mock(BuildMirror.class);
    when(store.findMirrorByTcBuildId(33L)).thenReturn(mirror);

    SBuild build = buildWith("   ", 33L, 33L);

    // Must not call findMirror with a blank key; resolves by promotion id instead.
    assertSame(mirror, new BuildMirrorResolver(store).resolve(build));
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
