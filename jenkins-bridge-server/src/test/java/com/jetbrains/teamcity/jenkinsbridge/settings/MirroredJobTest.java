package com.jetbrains.teamcity.jenkinsbridge.settings;

import org.junit.Test;

import java.lang.reflect.Constructor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class MirroredJobTest {
  @Test
  public void featureJobNamespacesKeyPrefixByBuildTypeAndJob() {
    MirroredJob job = new MirroredJob("team/pipeline", "Proj_Mirror", "Proj / Mirror", 0, false, false);
    assertFalse(job.isLegacy());
    assertEquals("Proj_Mirror::team/pipeline", job.getMirrorKeyPrefix());
  }

  @Test
  public void legacyJobKeepsBareJobKeyPrefix() {
    MirroredJob job = new MirroredJob("team/pipeline", "Proj_Mirror", "Proj / Mirror", 0, true, false);
    assertTrue(job.isLegacy());
    assertEquals("team/pipeline", job.getMirrorKeyPrefix());
  }

  @Test
  public void recentBuildLimitOverrideFallsBackToGlobalDefaultWhenUnset() {
    MirroredJob noOverride = new MirroredJob("job", "Bt", "Bt", 0, false, false);
    assertEquals(7, noOverride.getEffectiveRecentBuildLimit(7));

    MirroredJob withOverride = new MirroredJob("job", "Bt", "Bt", 3, false, false);
    assertEquals(3, withOverride.getEffectiveRecentBuildLimit(7));
  }

  @Test
  public void hasMinimumConfigurationRequiresJobAndBuildType() {
    assertTrue(new MirroredJob("job", "Bt", "Bt", 0, false, false).hasMinimumConfiguration());
    assertFalse(new MirroredJob("", "Bt", "Bt", 0, false, false).hasMinimumConfiguration());
    assertFalse(new MirroredJob("job", "", "", 0, false, false).hasMinimumConfiguration());

    MirroredJob missingJob = new MirroredJob("  ", "Bt", "Bt", 0, false, false);
    assertTrue(missingJob.describeMinimumConfigurationProblem().contains("jenkinsJob"));
  }

  @Test
  public void fromGlobalSettingsProducesLegacyJob() throws Exception {
    JenkinsBridgeSettings settings = settings("myjob", "Bt_Target");
    MirroredJob job = MirroredJob.fromGlobalSettings(settings);

    assertTrue(job.isLegacy());
    assertEquals("myjob", job.getJenkinsJob());
    assertEquals("Bt_Target", job.getTeamCityBuildTypeExternalId());
    assertEquals("myjob", job.getMirrorKeyPrefix());
    assertTrue(job.hasMinimumConfiguration());
  }

  private static JenkinsBridgeSettings settings(String job, String buildTypeId) throws Exception {
    Constructor<JenkinsBridgeSettings> constructor = JenkinsBridgeSettings.class.getDeclaredConstructor(
        boolean.class, String.class, String.class, String.class, String.class,
        String.class, String.class, String.class, String.class,
        int.class, int.class, String.class);
    constructor.setAccessible(true);
    return constructor.newInstance(
        true, "http://jenkins", "user", "token", job,
        "http://teamcity", "tc-user", "tc-pass", buildTypeId,
        10, 1, "");
  }
}
