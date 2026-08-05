package com.jetbrains.teamcity.jenkinsbridge.feature;

import com.jetbrains.teamcity.jenkinsbridge.settings.MirroredJob;
import jetbrains.buildServer.serverSide.ProjectManager;
import jetbrains.buildServer.serverSide.SBuildFeatureDescriptor;
import jetbrains.buildServer.serverSide.SBuildType;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class MirroredJobProviderTest {
  private final ProjectManager projectManager = mock(ProjectManager.class);
  private final MirroredJobProvider provider = new MirroredJobProvider(projectManager);

  @Test
  public void discoverMirroredJobsCarriesTheConnectionAndBackfillOfEachFeature() {
    Map<String, String> params = new LinkedHashMap<>();
    params.put(BridgeBuildFeatureConstants.PARAM_CONNECTION_ID, "conn1");
    params.put(BridgeBuildFeatureConstants.PARAM_JENKINS_JOB, "team/pipeline");
    params.put(BridgeBuildFeatureConstants.PARAM_RECENT_LIMIT, "5");
    withBuildTypes(buildType("Proj_Mirror", "Proj / Mirror", params));

    List<MirroredJob> jobs = provider.discoverMirroredJobs();

    assertEquals(1, jobs.size());
    MirroredJob job = jobs.getFirst();
    assertEquals("conn1", job.connectionId());
    assertEquals("team/pipeline", job.jenkinsJob());
    assertEquals("Proj_Mirror", job.teamCityBuildTypeExternalId());
    assertEquals(5, job.recentBuildLimit());
    assertFalse(job.isMultibranch());
    assertTrue(job.hasMinimumConfiguration());
  }

  @Test
  public void discoverMirroredJobsFlagsAFeatureWithoutAConnectionAsIncomplete() {
    withBuildTypes(buildType("Proj_Mirror", "Proj / Mirror",
        Collections.singletonMap(BridgeBuildFeatureConstants.PARAM_JENKINS_JOB, "team/pipeline")));

    List<MirroredJob> jobs = provider.discoverMirroredJobs();

    assertEquals(1, jobs.size());
    assertFalse(jobs.getFirst().hasMinimumConfiguration());
    assertTrue(jobs.getFirst().describeMinimumConfigurationProblem().contains("jenkinsConnection"));
  }

  @Test
  public void discoverMirroredJobsKeepsAZeroBackfillInsteadOfFallingBackToTheDefault() {
    assertEquals(0, discoverWithRecentLimit("0").recentBuildLimit());
  }

  @Test
  public void discoverMirroredJobsFallsBackToTheDefaultForABlankOrUnparseableBackfill() {
    assertEquals(BridgeBuildFeatureConstants.DEFAULT_RECENT_LIMIT,
        discoverWithRecentLimit("").recentBuildLimit());
    assertEquals(BridgeBuildFeatureConstants.DEFAULT_RECENT_LIMIT,
        discoverWithRecentLimit("abc").recentBuildLimit());
    assertEquals(BridgeBuildFeatureConstants.DEFAULT_RECENT_LIMIT,
        discoverWithRecentLimit(null).recentBuildLimit());
  }

  private MirroredJob discoverWithRecentLimit(String recentLimit) {
    Map<String, String> params = new LinkedHashMap<>();
    params.put(BridgeBuildFeatureConstants.PARAM_CONNECTION_ID, "conn1");
    params.put(BridgeBuildFeatureConstants.PARAM_JENKINS_JOB, "team/pipeline");
    if (recentLimit != null) {
      params.put(BridgeBuildFeatureConstants.PARAM_RECENT_LIMIT, recentLimit);
    }
    withBuildTypes(buildType("Proj_Mirror", "Proj / Mirror", params));
    return provider.discoverMirroredJobs().getFirst();
  }

  @Test
  public void discoverMirroredJobsReadsTheMultibranchFlag() {
    Map<String, String> params = new LinkedHashMap<>();
    params.put(BridgeBuildFeatureConstants.PARAM_CONNECTION_ID, "conn1");
    params.put(BridgeBuildFeatureConstants.PARAM_JENKINS_JOB, "team/pipeline");
    params.put(BridgeBuildFeatureConstants.PARAM_IN_MULTIBRANCH, "true");
    withBuildTypes(buildType("Proj_Mirror", "Proj / Mirror", params));

    assertTrue(provider.discoverMirroredJobs().getFirst().isMultibranch());
  }

  @Test
  public void discoverMirroredJobsIgnoresConfigurationsWithoutTheFeature() {
    SBuildType withoutFeature = mock(SBuildType.class);
    when(withoutFeature.getBuildFeaturesOfType(BridgeBuildFeatureConstants.TYPE))
        .thenReturn(Collections.emptyList());
    withBuildTypes(withoutFeature);

    assertTrue(provider.discoverMirroredJobs().isEmpty());
  }

  private void withBuildTypes(SBuildType... buildTypes) {
    when(projectManager.getActiveBuildTypes()).thenReturn(Arrays.asList(buildTypes));
  }

  private static SBuildType buildType(String externalId, String fullName, Map<String, String> featureParams) {
    SBuildFeatureDescriptor feature = mock(SBuildFeatureDescriptor.class);
    when(feature.getParameters()).thenReturn(featureParams);

    SBuildType buildType = mock(SBuildType.class);
    when(buildType.getExternalId()).thenReturn(externalId);
    when(buildType.getFullName()).thenReturn(fullName);
    when(buildType.getBuildFeaturesOfType(BridgeBuildFeatureConstants.TYPE))
        .thenReturn(Collections.singletonList(feature));
    return buildType;
  }
}
