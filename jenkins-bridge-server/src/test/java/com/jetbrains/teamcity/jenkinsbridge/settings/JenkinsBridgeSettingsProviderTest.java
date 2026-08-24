package com.jetbrains.teamcity.jenkinsbridge.settings;

import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class JenkinsBridgeSettingsProviderTest {
  private static final String ENABLED = "jenkins.bridge.enabled";
  private static final String POLL_SECONDS = "jenkins.bridge.pollSeconds";
  private static final String PARAMETER_REFRESH_POLL_CYCLES =
      "jenkins.bridge.parameterRefreshPollCycles";
  private static final String PENDING_TRIGGER_TIMEOUT_MINUTES =
      "jenkins.bridge.pendingTriggerTimeoutMinutes";

  @After
  public void clearProperties() {
    System.clearProperty(ENABLED);
    System.clearProperty(POLL_SECONDS);
    System.clearProperty(PARAMETER_REFRESH_POLL_CYCLES);
    System.clearProperty(PENDING_TRIGGER_TIMEOUT_MINUTES);
  }

  @Test
  public void appliesDefaultsWhenPropertiesAreAbsent() {
    JenkinsBridgeSettings settings = new JenkinsBridgeSettingsProvider().load();

    assertTrue(settings.isEnabled());
    assertEquals(10, settings.getPollSeconds());
    assertEquals(100, settings.getParameterRefreshPollCycles());
    assertEquals(1440, settings.getPendingTriggerTimeoutMinutes());
  }

  @Test
  public void readsValuesThroughTeamCityProperties() {
    System.setProperty(ENABLED, "false");
    System.setProperty(POLL_SECONDS, "7");
    System.setProperty(PARAMETER_REFRESH_POLL_CYCLES, "23");
    System.setProperty(PENDING_TRIGGER_TIMEOUT_MINUTES, "30");

    JenkinsBridgeSettings settings = new JenkinsBridgeSettingsProvider().load();

    assertEquals(false, settings.isEnabled());
    assertEquals(7, settings.getPollSeconds());
    assertEquals(23, settings.getParameterRefreshPollCycles());
    assertEquals(30, settings.getPendingTriggerTimeoutMinutes());
  }
}
