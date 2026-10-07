package com.jetbrains.teamcity.jenkinsbridge.settings;

import org.jetbrains.annotations.NotNull;

import static com.jetbrains.teamcity.jenkinsbridge.util.Utilities.nullToEmpty;

/**
 * Runtime snapshot of one Jenkins Bridge build feature mapping a Jenkins job to a TeamCity build
 * configuration. Recreated on each polling discovery from persisted TeamCity configuration and
 * kept only in memory. One mapping can produce many build mirrors.
 *
 * <p>A mirrored job names the Jenkins connection to read from, the Jenkins job, and the target
 * TeamCity build config (the config that hosts the Jenkins Bridge build feature).
 *
 * @param isMultibranch When true, jenkinsJob is a multibranch pipeline path and the poller polls each branch job under it.
 */
public record MirroredJob(String connectionId, String jenkinsJob, String teamCityBuildTypeExternalId,
                          String teamCityBuildTypeName, int recentBuildLimit, boolean isMultibranch)
    implements MirroredJobNode {
  public MirroredJob(
      String connectionId,
      String jenkinsJob,
      String teamCityBuildTypeExternalId,
      String teamCityBuildTypeName,
      int recentBuildLimit,
      boolean isMultibranch
  ) {
    this.connectionId = nullToEmpty(connectionId).trim();
    this.jenkinsJob = nullToEmpty(jenkinsJob).trim();
    this.teamCityBuildTypeExternalId = nullToEmpty(teamCityBuildTypeExternalId).trim();
    this.teamCityBuildTypeName = nullToEmpty(teamCityBuildTypeName).trim();
    this.recentBuildLimit = Math.max(0, recentBuildLimit);
    this.isMultibranch = isMultibranch;
  }

  /**
   * Id of the "Jenkins" connection this job is mirrored from.
   */
  @Override
  public String connectionId() {
    return connectionId;
  }

  @Override
  public String teamCityBuildTypeName() {
    return JenkinsBridgeSettings.isNotBlank(teamCityBuildTypeName)
        ? teamCityBuildTypeName : teamCityBuildTypeExternalId;
  }

  @Override
  @NotNull
  public MirroredJob mapping() {
    return this;
  }

  @Override
  @NotNull
  public String jenkinsJob() {
    return jenkinsJob;
  }

  /** Prefix for a child job triggered through this mapped configuration. */
  public String getMirrorKeyPrefix(String childJob) {
    return teamCityBuildTypeExternalId + "::" + childJob;
  }

  public boolean hasMinimumConfiguration() {
    return JenkinsBridgeSettings.isNotBlank(connectionId)
        && JenkinsBridgeSettings.isNotBlank(jenkinsJob)
        && JenkinsBridgeSettings.isNotBlank(teamCityBuildTypeExternalId);
  }

  public String describeMinimumConfigurationProblem() {
    if (hasMinimumConfiguration()) {
      return "";
    }
    StringBuilder result = new StringBuilder("Jenkins Bridge job is missing configuration:");
    if (!JenkinsBridgeSettings.isNotBlank(connectionId)) {
      result.append(" jenkinsConnection");
    }
    if (!JenkinsBridgeSettings.isNotBlank(jenkinsJob)) {
      result.append(" jenkinsJob");
    }
    if (!JenkinsBridgeSettings.isNotBlank(teamCityBuildTypeExternalId)) {
      result.append(" teamCityBuildType");
    }
    if (JenkinsBridgeSettings.isNotBlank(teamCityBuildTypeExternalId)) {
      result.append(" (build configuration ").append(teamCityBuildTypeExternalId).append(')');
    }
    return result.toString();
  }
}
