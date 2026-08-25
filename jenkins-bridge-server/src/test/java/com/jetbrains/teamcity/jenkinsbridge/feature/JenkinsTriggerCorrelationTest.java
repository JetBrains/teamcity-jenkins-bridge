package com.jetbrains.teamcity.jenkinsbridge.feature;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class JenkinsTriggerCorrelationTest {
  @Test
  public void payloadRoundTrips() {
    String value = JenkinsTriggerCorrelation.encode(42L, "secondary-1", "alice", "2026-08-24T15:00:00Z");
    JenkinsTriggerCorrelation.Payload payload = JenkinsTriggerCorrelation.decode(value);
    assertEquals(42L, payload.getPromotionId());
    assertEquals("secondary-1", payload.getNodeId());
    assertEquals("alice", payload.getTriggeredBy());
    assertEquals("Jenkins Bridge: TeamCity promotion 42 (node secondary-1)", payload.getCause());
  }

  @Test
  public void malformedPayloadIsIgnored() {
    assertNull(JenkinsTriggerCorrelation.decode("not-a-correlation"));
  }
}
