package com.jetbrains.teamcity.jenkinsbridge.settings;

import static com.jetbrains.teamcity.jenkinsbridge.util.Utilities.nullToEmpty;

/**
 * One Jenkins job mirrored into a TeamCity build configuration. One {@code MirroredJob} corresponds
 * to many {@code BuildMirror}s (one per Jenkins build).
 *
 * <p>A mirrored job names the Jenkins connection to read from, the Jenkins job, and the target
 * TeamCity build config (the config that hosts the Jenkins Bridge build feature).
 *
 * @param isMultibranch When true, jenkinsJob is a multibranch pipeline path and the poller polls each branch job under it.
 */
public record MirroredJob(String connectionId, String jenkinsJob, String teamCityBuildTypeExternalId,
                          String teamCityBuildTypeName, int recentBuildLimit, boolean isMultibranch) {
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

  /**
   * Prefix that namespaces this job's persisted mirrors and its polling watermark.
   *
   * <p>The prefix is {@code <externalId>::<job>} so two configs mirroring the same Jenkins job do
   * not collide.
   */
  public String getMirrorKeyPrefix() {
    return teamCityBuildTypeExternalId + "::" + jenkinsJob;
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

  public String describeForLog() {
    return "job " + jenkinsJob
        + " -> buildType=" + teamCityBuildTypeExternalId
        + " via connection " + connectionId
        + " (recentBuildLimit=" + recentBuildLimit + ")";
  }
}
