package com.jetbrains.teamcity.jenkinsbridge.settings;

import jetbrains.buildServer.serverSide.TeamCityProperties;

/**
 * Reads the bridge's global operational settings from TeamCity's internal properties.
 *
 * <p>Jenkins servers and credentials are not read here. They live on the "Jenkins" project
 * connections and are selected per build configuration by the Jenkins Bridge build feature.
 */
public class JenkinsBridgeSettingsProvider {
  private static final String ENABLED = "jenkins.bridge.enabled";
  private static final String POLL_SECONDS = "jenkins.bridge.pollSeconds";
  private static final String PARAMETER_REFRESH_POLL_CYCLES =
      "jenkins.bridge.parameterRefreshPollCycles";
  private static final String PENDING_TRIGGER_TIMEOUT_MINUTES =
      "jenkins.bridge.pendingTriggerTimeoutMinutes";
  public JenkinsBridgeSettings load() {
    return new JenkinsBridgeSettings(
        TeamCityProperties.getBoolean(ENABLED, true),
        TeamCityProperties.getInteger(POLL_SECONDS, 10),
        TeamCityProperties.getInteger(PARAMETER_REFRESH_POLL_CYCLES, 100),
        TeamCityProperties.getInteger(PENDING_TRIGGER_TIMEOUT_MINUTES, 1440)
    );
  }
}
