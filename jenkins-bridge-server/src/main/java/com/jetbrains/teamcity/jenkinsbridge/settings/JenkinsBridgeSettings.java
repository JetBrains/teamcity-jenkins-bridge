package com.jetbrains.teamcity.jenkinsbridge.settings;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.DateTimeException;
import java.time.ZoneId;

import static com.jetbrains.teamcity.jenkinsbridge.util.Utilities.nullToEmpty;

/**
 * Server-wide operational settings of the bridge. Jenkins servers and their credentials are not
 * here, they come from the "Jenkins" project connections, one per mirrored build configuration.
 */
public class JenkinsBridgeSettings {
  private final boolean enabled;
  private final int pollSeconds;
  private final String timeZone;
  private final String stateFile;
  private final String teamCityUrl;
  private final String teamCityUser;
  private final String teamCityPassword;

  JenkinsBridgeSettings(
      boolean enabled,
      int pollSeconds,
      String timeZone,
      String stateFile,
      String teamCityUrl,
      String teamCityUser,
      String teamCityPassword
  ) {
    this.enabled = enabled;
    this.pollSeconds = Math.max(1, pollSeconds);
    this.timeZone = nullToEmpty(timeZone);
    this.stateFile = nullToEmpty(stateFile);
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
        + ", pollSeconds=" + pollSeconds
        + ", timeZone=" + timeZone
        + ", stateFile=" + stateFile;
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
