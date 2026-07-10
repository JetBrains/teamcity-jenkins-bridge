package com.jetbrains.teamcity.jenkinsbridge.settings;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.DateTimeException;
import java.time.ZoneId;

import static com.jetbrains.teamcity.jenkinsbridge.util.Utilities.nullToEmpty;

public class JenkinsBridgeSettings {
  private final boolean enabled;
  private final String jenkinsUrl;
  private final String jenkinsUser;
  private final String jenkinsToken;
  private final String jenkinsJob;
  private final String teamCityUrl;
  private final String teamCityUser;
  private final String teamCityPassword;
  private final String teamCityBuildTypeId;
  private final int pollSeconds;
  private final int recentBuildLimit;
  private final String timeZone;
  private final String stateFile;

  JenkinsBridgeSettings(
      boolean enabled,
      String jenkinsUrl,
      String jenkinsUser,
      String jenkinsToken,
      String jenkinsJob,
      String teamCityUrl,
      String teamCityUser,
      String teamCityPassword,
      String teamCityBuildTypeId,
      int pollSeconds,
      int recentBuildLimit,
      String timeZone,
      String stateFile
  ) {
    this.enabled = enabled;
    this.jenkinsUrl = trimTrailingSlash(jenkinsUrl);
    this.jenkinsUser = nullToEmpty(jenkinsUser);
    this.jenkinsToken = nullToEmpty(jenkinsToken);
    this.jenkinsJob = nullToEmpty(jenkinsJob);
    this.teamCityUrl = trimTrailingSlash(teamCityUrl);
    this.teamCityUser = nullToEmpty(teamCityUser);
    this.teamCityPassword = nullToEmpty(teamCityPassword);
    this.teamCityBuildTypeId = nullToEmpty(teamCityBuildTypeId);
    this.pollSeconds = Math.max(1, pollSeconds);
    this.recentBuildLimit = Math.max(1, recentBuildLimit);
    this.timeZone = nullToEmpty(timeZone);
    this.stateFile = nullToEmpty(stateFile);
  }

  @Deprecated
  public static JenkinsBridgeSettings fromEnvironment() {
    return new JenkinsBridgeSettings(
        getBoolean("jenkins.bridge.enabled", "JENKINS_BRIDGE_ENABLED", true),
        getString("jenkins.bridge.jenkinsUrl", "JENKINS_URL", "http://localhost:8080"),
        getString("jenkins.bridge.jenkinsUser", "JENKINS_USER", "Ahmed"),
        getString("jenkins.bridge.jenkinsToken", "JENKINS_TOKEN", ""),
        getString("jenkins.bridge.jenkinsJob", "JENKINS_JOB", "tc-test"),
        getString("jenkins.bridge.teamCityUrl", "TEAMCITY_URL", "http://localhost:8111/bs/httpAuth"),
        getString("jenkins.bridge.teamCityUser", "TEAMCITY_USER", "Ahmed"),
        getString("jenkins.bridge.teamCityPassword", "TEAMCITY_PASSWORD", "test"),
        getString("jenkins.bridge.teamCityBuildTypeId", "TEAMCITY_BUILD_TYPE_ID", "TestTc_JenkinsTcTest"),
        getInt("jenkins.bridge.pollSeconds", "BRIDGE_POLL_SECONDS", 10),
        getInt("jenkins.bridge.recentBuildLimit", "RECENT_BUILDS_LIMIT", 1),
        getString("jenkins.bridge.timeZone", "TIMEZONE", "Europe/Berlin"),
        getString("jenkins.bridge.stateFile", "BRIDGE_STATE_FILE", "")
    );
  }

  public boolean isEnabled() {
    return enabled;
  }

  public String getJenkinsUrl() {
    return jenkinsUrl;
  }

  public String getJenkinsUser() {
    return jenkinsUser;
  }

  public String getJenkinsToken() {
    return jenkinsToken;
  }

  public String getJenkinsJob() {
    return jenkinsJob;
  }

  public String getTeamCityUrl() {
    return teamCityUrl;
  }

  public String getTeamCityUser() {
    return teamCityUser;
  }

  public String getTeamCityPassword() {
    return teamCityPassword;
  }

  public String getTeamCityBuildTypeId() {
    return teamCityBuildTypeId;
  }

  public int getPollSeconds() {
    return pollSeconds;
  }

  public int getRecentBuildLimit() {
    return recentBuildLimit;
  }

  public ZoneId getZoneId() {
    try {
      return ZoneId.of(timeZone);
    } catch (DateTimeException e) {
      return ZoneId.systemDefault();
    }
  }

  public boolean hasCustomStateFile() {
    return isNotBlank(stateFile);
  }

  public Path getCustomStateFile() {
    return Paths.get(stateFile);
  }

  public String describeForLog() {
    return "enabled=" + enabled
        + ", jenkinsUrl=" + jenkinsUrl
        + ", jenkinsUser=" + jenkinsUser
        + ", jenkinsToken=" + redact(jenkinsToken)
        + ", jenkinsJob=" + jenkinsJob
        + ", teamCityUrl=" + teamCityUrl
        + ", teamCityUser=" + teamCityUser
        + ", teamCityPassword=" + redact(teamCityPassword)
        + ", teamCityBuildTypeId=" + teamCityBuildTypeId
        + ", pollSeconds=" + pollSeconds
        + ", recentBuildLimit=" + recentBuildLimit
        + ", timeZone=" + timeZone
        + ", stateFile=" + stateFile;
  }

  public boolean hasMinimumConfiguration() {
    return isNotBlank(jenkinsUrl)
        && isNotBlank(jenkinsJob)
        && isNotBlank(teamCityUrl)
        && isNotBlank(teamCityBuildTypeId);
  }

  /**
   * The global Jenkins connection (URL) is required regardless of how jobs are mapped, since the
   * Jenkins server/credentials are shared across all mappings.
   */
  public boolean hasJenkinsConnection() {
    return isNotBlank(jenkinsUrl);
  }

  public String describeMinimumConfigurationProblem() {
    if (hasMinimumConfiguration()) {
      return "";
    }
    StringBuilder result = new StringBuilder("Jenkins Bridge is missing configuration:");
    appendIfBlank(result, "jenkinsUrl", jenkinsUrl);
    appendIfBlank(result, "jenkinsJob", jenkinsJob);
    appendIfBlank(result, "teamCityUrl", teamCityUrl);
    appendIfBlank(result, "teamCityBuildTypeId", teamCityBuildTypeId);
    return result.toString();
  }

  private static void appendIfBlank(StringBuilder result, String name, String value) {
    if (!isNotBlank(value)) {
      result.append(' ').append(name);
    }
  }


  private static String getString(String propertyName, String environmentName, String defaultValue) {
    String value = System.getProperty(propertyName);
    if (isNotBlank(value)) {
      return value;
    }

    value = System.getenv(environmentName);
    if (isNotBlank(value)) {
      return value;
    }

    return defaultValue;
  }

  private static int getInt(String propertyName, String environmentName, int defaultValue) {
    String value = getString(propertyName, environmentName, String.valueOf(defaultValue));
    return parseInt(value, defaultValue);
  }

  private static int parseInt(String value, int defaultValue) {
    try {
      return Integer.parseInt(value);
    } catch (NumberFormatException e) {
      return defaultValue;
    }
  }

  private static boolean getBoolean(String propertyName, String environmentName, boolean defaultValue) {
    String value = getString(propertyName, environmentName, String.valueOf(defaultValue));
    return parseBoolean(value);
  }

  private static boolean parseBoolean(String value) {
    return "true".equalsIgnoreCase(value) || "1".equals(value) || "yes".equalsIgnoreCase(value);
  }

  private static String trimTrailingSlash(String value) {
    String result = nullToEmpty(value).trim();
    while (result.endsWith("/")) {
      result = result.substring(0, result.length() - 1);
    }
    return result;
  }

  private static String redact(String value) {
    return isNotBlank(value) ? "<set>" : "<empty>";
  }

  static boolean isNotBlank(String value) {
    return value != null && value.trim().length() > 0;
  }

}
