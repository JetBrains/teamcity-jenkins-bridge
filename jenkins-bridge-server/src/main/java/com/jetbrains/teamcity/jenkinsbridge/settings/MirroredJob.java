package com.jetbrains.teamcity.jenkinsbridge.settings;

import static com.jetbrains.teamcity.jenkinsbridge.util.Utilities.nullToEmpty;

/**
 * One Jenkins job mirrored into a TeamCity build configuration. One {@code MirroredJob} corresponds
 * to many {@code BuildMirror}s (one per Jenkins build).
 *
 * <p>The Jenkins connection (URL/user/token) is <b>global</b> (see {@link JenkinsBridgeSettings}); a
 * mirrored job only names the Jenkins job and the target TeamCity build config (the config that hosts
 * the Jenkins Bridge build feature). It is either feature-derived or the single legacy fallback
 * synthesized from global settings when no feature exists.
 */
public final class MirroredJob {
  private final String jenkinsJob;
  private final String teamCityBuildTypeExternalId;
  private final String teamCityBuildTypeName;
  // 0 means "no per-job override; use the global recentBuildLimit".
  private final int recentBuildLimitOverride;
  private final boolean legacy;
  // When true, jenkinsJob is a multibranch pipeline path and the poller polls each branch job under it.
  private final boolean isMultibranch;

  public MirroredJob(
      String jenkinsJob,
      String teamCityBuildTypeExternalId,
      String teamCityBuildTypeName,
      int recentBuildLimitOverride,
      boolean legacy,
      boolean isMultibranch
  ) {
    this.jenkinsJob = nullToEmpty(jenkinsJob).trim();
    this.teamCityBuildTypeExternalId = nullToEmpty(teamCityBuildTypeExternalId).trim();
    this.teamCityBuildTypeName = nullToEmpty(teamCityBuildTypeName).trim();
    this.recentBuildLimitOverride = Math.max(0, recentBuildLimitOverride);
    this.legacy = legacy;
    this.isMultibranch = isMultibranch;
  }

  /** The single legacy mirrored job derived from global settings (used when no build feature exists). */
  public static MirroredJob fromGlobalSettings(JenkinsBridgeSettings settings) {
    return new MirroredJob(
        settings.getJenkinsJob(),
        settings.getTeamCityBuildTypeId(),
        settings.getTeamCityBuildTypeId(),
        0,
        true,
        false
    );
  }

  public String getJenkinsJob() {
    return jenkinsJob;
  }

  public String getTeamCityBuildTypeExternalId() {
    return teamCityBuildTypeExternalId;
  }

  public String getTeamCityBuildTypeName() {
    return JenkinsBridgeSettings.isNotBlank(teamCityBuildTypeName)
        ? teamCityBuildTypeName : teamCityBuildTypeExternalId;
  }

  public boolean isLegacy() {
    return legacy;
  }

  public boolean isMultibranch() {
    return isMultibranch;
  }

  /**
   * Prefix that namespaces this job's persisted mirrors and its polling watermark.
   *
   * <p>Feature-derived jobs use {@code <externalId>::<job>} so two configs mirroring the same Jenkins
   * job don't collide. The legacy job keeps the bare job name to stay byte-compatible with
   * pre-existing on-disk state and the {@code jenkins.build.key} of already-created builds.
   */
  public String getMirrorKeyPrefix() {
    return legacy ? jenkinsJob : teamCityBuildTypeExternalId + "::" + jenkinsJob;
  }

  public int getEffectiveRecentBuildLimit(int globalDefault) {
    return recentBuildLimitOverride > 0 ? recentBuildLimitOverride : globalDefault;
  }

  public boolean hasMinimumConfiguration() {
    return JenkinsBridgeSettings.isNotBlank(jenkinsJob)
        && JenkinsBridgeSettings.isNotBlank(teamCityBuildTypeExternalId);
  }

  public String describeMinimumConfigurationProblem() {
    if (hasMinimumConfiguration()) {
      return "";
    }
    StringBuilder result = new StringBuilder("Jenkins Bridge job is missing configuration:");
    if (!JenkinsBridgeSettings.isNotBlank(jenkinsJob)) {
      result.append(" jenkinsJob");
    }
    if (!JenkinsBridgeSettings.isNotBlank(teamCityBuildTypeExternalId)) {
      result.append(" teamCityBuildType");
    }
    return result.toString();
  }

  public String describeForLog() {
    return (legacy ? "legacy" : "feature") + " job " + jenkinsJob
        + " -> buildType=" + teamCityBuildTypeExternalId
        + (recentBuildLimitOverride > 0 ? " (recentBuildLimit=" + recentBuildLimitOverride + ")" : "");
  }
}
