package com.jetbrains.teamcity.jenkinsbridge.teamcity;

import jetbrains.buildServer.serverSide.BuildPromotion;
import jetbrains.buildServer.serverSide.BuildPromotionManager;
import jetbrains.buildServer.serverSide.BuildQueryOptions;
import jetbrains.buildServer.serverSide.BuildsManager;
import jetbrains.buildServer.serverSide.ProjectManager;
import jetbrains.buildServer.serverSide.SBuild;
import jetbrains.buildServer.serverSide.SBuildType;
import jetbrains.buildServer.util.ItemProcessor;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class TeamCityRunningBuildLocatorTest {
  private static final String BUILD_TYPE_EXTERNAL_ID = "Proj_Mirror";
  private static final String BUILD_TYPE_INTERNAL_ID = "bt42";

  private final BuildsManager buildsManager = mock(BuildsManager.class);
  private final ProjectManager projectManager = mock(ProjectManager.class);
  private final List<SBuild> builds = new ArrayList<>();
  private BuildQueryOptions capturedOptions;

  private final TeamCityRunningBuildLocator locator =
      new TeamCityRunningBuildLocator(buildsManager, mock(BuildPromotionManager.class), projectManager);

  @Before
  @SuppressWarnings("unchecked")
  public void setUp() {
    SBuildType buildType = mock(SBuildType.class);
    when(buildType.getBuildTypeId()).thenReturn(BUILD_TYPE_INTERNAL_ID);
    when(projectManager.findBuildTypeByExternalId(BUILD_TYPE_EXTERNAL_ID)).thenReturn(buildType);

    doAnswer(invocation -> {
      capturedOptions = invocation.getArgument(0);
      ItemProcessor<SBuild> processor = invocation.getArgument(1);
      for (SBuild build : builds) {
        if (!processor.processItem(build)) {
          break;
        }
      }
      return null;
    }).when(buildsManager).processBuilds(any(BuildQueryOptions.class), any(ItemProcessor.class));
  }

  @Test
  public void recoverBuildIdFindsTheBuildCarryingTheJenkinsBuildKey() {
    builds.addAll(Arrays.asList(build(10L, "job#9@1"), build(11L, "job#10@2"), build(12L, "job#11@3")));

    assertEquals(Long.valueOf(11L), locator.recoverBuildId(BUILD_TYPE_EXTERNAL_ID, "job#10@2"));
  }

  @Test
  public void recoverBuildIdReturnsNullWhenNoBuildMatches() {
    builds.add(build(10L, "job#9@1"));

    assertNull(locator.recoverBuildId(BUILD_TYPE_EXTERNAL_ID, "job#10@2"));
  }

  @Test
  public void recoverBuildIdSearchesTheGivenConfigurationAcrossAllBranchesAndStates() {
    builds.add(build(10L, "job#9@1"));

    locator.recoverBuildId(BUILD_TYPE_EXTERNAL_ID, "job#9@1");

    assertEquals(BUILD_TYPE_INTERNAL_ID, capturedOptions.getBuildTypeId());
    assertTrue(capturedOptions.isMatchAllBranches());
    assertTrue(capturedOptions.isIncludeRunning());
    assertTrue(capturedOptions.isIncludeFinished());
    assertTrue(capturedOptions.isIncludeCanceled());
  }

  @Test
  public void recoverBuildIdReturnsNullForAnUnknownBuildConfiguration() {
    assertNull(locator.recoverBuildId("Missing_Config", "job#10@2"));
  }

  @Test
  public void recoverBuildIdReturnsNullForABlankJenkinsBuildKey() {
    assertNull(locator.recoverBuildId(BUILD_TYPE_EXTERNAL_ID, "  "));
    assertNull(locator.recoverBuildId(BUILD_TYPE_EXTERNAL_ID, null));
  }

  private static SBuild build(long promotionId, String jenkinsBuildKey) {
    BuildPromotion promotion = mock(BuildPromotion.class);
    when(promotion.getId()).thenReturn(promotionId);
    when(promotion.getParameterValue(TeamCityRunningBuildLocator.JENKINS_BUILD_KEY_PARAM))
        .thenReturn(jenkinsBuildKey);

    SBuild build = mock(SBuild.class);
    when(build.getBuildPromotion()).thenReturn(promotion);
    return build;
  }
}
