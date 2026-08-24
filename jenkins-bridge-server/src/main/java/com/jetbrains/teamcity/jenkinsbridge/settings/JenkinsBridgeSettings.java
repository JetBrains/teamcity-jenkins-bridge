package com.jetbrains.teamcity.jenkinsbridge.settings;

/**
 * Server-wide operational settings of the bridge. Jenkins servers and their credentials are not
 * here, they come from the "Jenkins" project connections, one per mirrored build configuration.
 */
public class JenkinsBridgeSettings {
  private final boolean enabled;
  private final int pollSeconds;
  private final int parameterRefreshPollCycles;
  private final int pendingTriggerTimeoutMinutes;
  JenkinsBridgeSettings(
      boolean enabled,
      int pollSeconds,
      int parameterRefreshPollCycles,
      int pendingTriggerTimeoutMinutes
  ) {
    this.enabled = enabled;
    this.pollSeconds = Math.max(1, pollSeconds);
    this.parameterRefreshPollCycles = Math.max(1, parameterRefreshPollCycles);
    this.pendingTriggerTimeoutMinutes = Math.max(1, pendingTriggerTimeoutMinutes);
  }

  public boolean isEnabled() {
    return enabled;
  }

  public int getPollSeconds() {
    return pollSeconds;
  }

  public int getParameterRefreshPollCycles() {
    return parameterRefreshPollCycles;
  }

  public int getPendingTriggerTimeoutMinutes() {
    return pendingTriggerTimeoutMinutes;
  }

  public String describeForLog() {
    return "enabled=" + enabled
        + ", pollSeconds=" + pollSeconds
        + ", parameterRefreshPollCycles=" + parameterRefreshPollCycles
        + ", pendingTriggerTimeoutMinutes=" + pendingTriggerTimeoutMinutes;
  }

  static boolean isNotBlank(String value) {
    return value != null && !value.trim().isEmpty();
  }

}
