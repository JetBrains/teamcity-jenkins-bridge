package com.jetbrains.teamcity.jenkinsbridge.settings;

import java.nio.file.Path;
import java.nio.file.Paths;

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
  private final int pendingTriggerTimeoutMinutes;
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
      String stateFile
  ) {
    this(builder()
        .enabled(enabled)
        .jenkinsUrl(jenkinsUrl)
        .jenkinsUser(jenkinsUser)
        .jenkinsToken(jenkinsToken)
        .jenkinsJob(jenkinsJob)
        .teamCityUrl(teamCityUrl)
        .teamCityUser(teamCityUser)
        .teamCityPassword(teamCityPassword)
        .teamCityBuildTypeId(teamCityBuildTypeId)
        .pollSeconds(pollSeconds)
        .recentBuildLimit(recentBuildLimit)
        .stateFile(stateFile));
  }

  private JenkinsBridgeSettings(Builder builder) {
    this.enabled = builder.enabled;
    this.jenkinsUrl = trimTrailingSlash(builder.jenkinsUrl);
    this.jenkinsUser = nullToEmpty(builder.jenkinsUser);
    this.jenkinsToken = nullToEmpty(builder.jenkinsToken);
    this.jenkinsJob = nullToEmpty(builder.jenkinsJob);
    this.teamCityUrl = trimTrailingSlash(builder.teamCityUrl);
    this.teamCityUser = nullToEmpty(builder.teamCityUser);
    this.teamCityPassword = nullToEmpty(builder.teamCityPassword);
    this.teamCityBuildTypeId = nullToEmpty(builder.teamCityBuildTypeId);
    this.pollSeconds = Math.max(1, builder.pollSeconds);
    this.recentBuildLimit = Math.max(1, builder.recentBuildLimit);
    this.pendingTriggerTimeoutMinutes = Math.max(1, builder.pendingTriggerTimeoutMinutes);
    this.stateFile = nullToEmpty(builder.stateFile);
  }

  @Deprecated
  public static JenkinsBridgeSettings fromEnvironment() {
    return builder()
        .enabled(getBoolean("jenkins.bridge.enabled", "JENKINS_BRIDGE_ENABLED", true))
        .jenkinsUrl(getString("jenkins.bridge.jenkinsUrl", "JENKINS_URL", "http://localhost:8080"))
        .jenkinsUser(getString("jenkins.bridge.jenkinsUser", "JENKINS_USER", "Ahmed"))
        .jenkinsToken(getString("jenkins.bridge.jenkinsToken", "JENKINS_TOKEN", ""))
        .jenkinsJob(getString("jenkins.bridge.jenkinsJob", "JENKINS_JOB", "tc-test"))
        .teamCityUrl(getString("jenkins.bridge.teamCityUrl", "TEAMCITY_URL", "http://localhost:8111/bs/httpAuth"))
        .teamCityUser(getString("jenkins.bridge.teamCityUser", "TEAMCITY_USER", "Ahmed"))
        .teamCityPassword(getString("jenkins.bridge.teamCityPassword", "TEAMCITY_PASSWORD", "test"))
        .teamCityBuildTypeId(getString("jenkins.bridge.teamCityBuildTypeId", "TEAMCITY_BUILD_TYPE_ID", "TestTc_JenkinsTcTest"))
        .pollSeconds(getInt("jenkins.bridge.pollSeconds", "BRIDGE_POLL_SECONDS", 10))
        .recentBuildLimit(getInt("jenkins.bridge.recentBuildLimit", "RECENT_BUILDS_LIMIT", 1))
        .pendingTriggerTimeoutMinutes(getInt("jenkins.bridge.pendingTriggerTimeoutMinutes", "PENDING_TRIGGER_TIMEOUT_MINUTES", 1440))
        .stateFile(getString("jenkins.bridge.stateFile", "BRIDGE_STATE_FILE", ""))
        .build();
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

  public int getPendingTriggerTimeoutMinutes() {
    return pendingTriggerTimeoutMinutes;
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
        + ", pendingTriggerTimeoutMinutes=" + pendingTriggerTimeoutMinutes
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

  static Builder builder() {
    return new Builder();
  }

  static final class Builder {
    private boolean enabled;
    private String jenkinsUrl;
    private String jenkinsUser;
    private String jenkinsToken;
    private String jenkinsJob;
    private String teamCityUrl;
    private String teamCityUser;
    private String teamCityPassword;
    private String teamCityBuildTypeId;
    private int pollSeconds;
    private int recentBuildLimit;
    private int pendingTriggerTimeoutMinutes = 1440;
    private String stateFile;

    Builder enabled(boolean value) { enabled = value; return this; }
    Builder jenkinsUrl(String value) { jenkinsUrl = value; return this; }
    Builder jenkinsUser(String value) { jenkinsUser = value; return this; }
    Builder jenkinsToken(String value) { jenkinsToken = value; return this; }
    Builder jenkinsJob(String value) { jenkinsJob = value; return this; }
    Builder teamCityUrl(String value) { teamCityUrl = value; return this; }
    Builder teamCityUser(String value) { teamCityUser = value; return this; }
    Builder teamCityPassword(String value) { teamCityPassword = value; return this; }
    Builder teamCityBuildTypeId(String value) { teamCityBuildTypeId = value; return this; }
    Builder pollSeconds(int value) { pollSeconds = value; return this; }
    Builder recentBuildLimit(int value) { recentBuildLimit = value; return this; }
    Builder pendingTriggerTimeoutMinutes(int value) { pendingTriggerTimeoutMinutes = value; return this; }
    Builder stateFile(String value) { stateFile = value; return this; }

    JenkinsBridgeSettings build() {
      return new JenkinsBridgeSettings(this);
    }
  }

  static boolean isNotBlank(String value) {
    return value != null && value.trim().length() > 0;
  }

}
