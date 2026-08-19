package com.jetbrains.teamcity.jenkinsbridge.settings;

import java.nio.file.Path;

import static com.jetbrains.teamcity.jenkinsbridge.util.Utilities.nullToEmpty;

/**
 * Server-wide operational settings of the bridge. Jenkins servers and their credentials are not
 * here, they come from the "Jenkins" project connections, one per mirrored build configuration.
 */
public class JenkinsBridgeSettings {
  private final boolean enabled;
  private final int pollSeconds;
  private final int pendingTriggerTimeoutMinutes;
  private final String teamCityUrl;
  private final String teamCityUser;
  private final String teamCityPassword;

  JenkinsBridgeSettings(
      boolean enabled,
      int pollSeconds,
      int pendingTriggerTimeoutMinutes,
      String teamCityUrl,
      String teamCityUser,
      String teamCityPassword
  ) {
    this.enabled = enabled;
    this.pollSeconds = Math.max(1, pollSeconds);
    this.pendingTriggerTimeoutMinutes = Math.max(1, pendingTriggerTimeoutMinutes);
    this.teamCityUrl = trimTrailingSlash(teamCityUrl);
    this.teamCityUser = nullToEmpty(teamCityUser);
    this.teamCityPassword = nullToEmpty(teamCityPassword);
  }

  public boolean isEnabled() {
    return enabled;
  }

  public int getPollSeconds() {
    return pollSeconds;
  }

  /** Only read by the deprecated {@code TeamCityClient}. */
  @Deprecated
  public String getTeamCityUrl() {
    return teamCityUrl;
  }

  /** Only read by the deprecated {@code TeamCityClient}. */
  @Deprecated
  public String getTeamCityUser() {
    return teamCityUser;
  }

  /** Only read by the deprecated {@code TeamCityClient}. */
  @Deprecated
  public String getTeamCityPassword() {
    return teamCityPassword;
  }

  public int getPendingTriggerTimeoutMinutes() {
    return pendingTriggerTimeoutMinutes;
  }

  public String describeForLog() {
    return "enabled=" + enabled
        + ", pollSeconds=" + pollSeconds
        + ", pendingTriggerTimeoutMinutes=" + pendingTriggerTimeoutMinutes;
  }

  private static String trimTrailingSlash(String value) {
    String result = nullToEmpty(value).trim();
    while (result.endsWith("/")) {
      result = result.substring(0, result.length() - 1);
    }
    return result;
  }

  static boolean isNotBlank(String value) {
    return value != null && !value.trim().isEmpty();
  }

}
