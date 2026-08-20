package com.jetbrains.teamcity.jenkinsbridge.integration;

import com.jetbrains.teamcity.jenkinsbridge.polling.JenkinsBridgePollingService;
import com.jetbrains.teamcity.jenkinsbridge.settings.JenkinsBridgeSettings;
import com.jetbrains.teamcity.jenkinsbridge.settings.JenkinsBridgeSettingsProvider;
import jetbrains.buildServer.serverSide.impl.BaseServerTestCase;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.Test;

import java.lang.reflect.Field;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNull;

/** Verifies global bridge settings after the real TeamCity server fixture has initialized. */
public class TeamCitySettingsIT extends BaseServerTestCase {
  private static final String ENABLED = "jenkins.bridge.enabled";
  private static final String POLL_SECONDS = "jenkins.bridge.pollSeconds";
  private static final String PENDING_TRIGGER_TIMEOUT_MINUTES =
      "jenkins.bridge.pendingTriggerTimeoutMinutes";

  @AfterMethod
  public void clearProperties() {
    System.clearProperty(ENABLED);
    System.clearProperty(POLL_SECONDS);
    System.clearProperty(PENDING_TRIGGER_TIMEOUT_MINUTES);
  }

  @Test
  public void readsTeamCityPropertiesAndPollingServiceHonorsDisabledSetting() throws Exception {
    System.setProperty(ENABLED, "false");
    System.setProperty(POLL_SECONDS, "23");
    System.setProperty(PENDING_TRIGGER_TIMEOUT_MINUTES, "31");

    JenkinsBridgeSettings settings = new JenkinsBridgeSettingsProvider().load();
    assertFalse(settings.isEnabled());
    assertEquals(23, settings.getPollSeconds());
    assertEquals(31, settings.getPendingTriggerTimeoutMinutes());

    JenkinsBridgePollingService pollingService = new JenkinsBridgePollingService(
        new JenkinsBridgeSettingsProvider(), null, null, null, null, null, null, null, null);
    pollingService.start();

    assertFalse(readStarted(pollingService));
    assertNull(readExecutor(pollingService));
  }

  private static boolean readStarted(JenkinsBridgePollingService service) throws Exception {
    Field field = JenkinsBridgePollingService.class.getDeclaredField("started");
    field.setAccessible(true);
    return ((AtomicBoolean) field.get(service)).get();
  }

  private static ScheduledExecutorService readExecutor(JenkinsBridgePollingService service)
      throws Exception {
    Field field = JenkinsBridgePollingService.class.getDeclaredField("executorService");
    field.setAccessible(true);
    return (ScheduledExecutorService) field.get(service);
  }
}
