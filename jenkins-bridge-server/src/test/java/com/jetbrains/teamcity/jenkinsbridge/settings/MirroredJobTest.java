package com.jetbrains.teamcity.jenkinsbridge.settings;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class MirroredJobTest {
  @Test
  public void getMirrorKeyPrefixNamespacesByBuildTypeAndJob() {
    MirroredJob job = new MirroredJob("conn1", "team/pipeline", "Proj_Mirror", "Proj / Mirror", 0, false);
    assertEquals("Proj_Mirror::team/pipeline", job.getMirrorKeyPrefix());
  }

  @Test
  public void getRecentBuildLimitKeepsZeroAndClampsNegativeValues() {
    assertEquals(0, new MirroredJob("conn1", "job", "Bt", "Bt", 0, false).recentBuildLimit());
    assertEquals(3, new MirroredJob("conn1", "job", "Bt", "Bt", 3, false).recentBuildLimit());
    assertEquals(0, new MirroredJob("conn1", "job", "Bt", "Bt", -5, false).recentBuildLimit());
  }

  @Test
  public void hasMinimumConfigurationRequiresConnectionJobAndBuildType() {
    assertTrue(new MirroredJob("conn1", "job", "Bt", "Bt", 0, false).hasMinimumConfiguration());
    assertFalse(new MirroredJob("conn1", "", "Bt", "Bt", 0, false).hasMinimumConfiguration());
    assertFalse(new MirroredJob("conn1", "job", "", "", 0, false).hasMinimumConfiguration());
    assertFalse(new MirroredJob("", "job", "Bt", "Bt", 0, false).hasMinimumConfiguration());
  }

  @Test
  public void describeMinimumConfigurationProblemNamesTheMissingParts() {
    MirroredJob missingJob = new MirroredJob("conn1", "  ", "Bt", "Bt", 0, false);
    assertTrue(missingJob.describeMinimumConfigurationProblem().contains("jenkinsJob"));

    MirroredJob missingConnection = new MirroredJob(" ", "job", "Bt", "Bt", 0, false);
    assertTrue(missingConnection.describeMinimumConfigurationProblem().contains("jenkinsConnection"));
    assertTrue(missingConnection.describeMinimumConfigurationProblem().contains("Bt"));
  }
}
