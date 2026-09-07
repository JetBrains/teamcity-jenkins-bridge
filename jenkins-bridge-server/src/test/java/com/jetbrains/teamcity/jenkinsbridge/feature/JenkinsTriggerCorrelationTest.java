package com.jetbrains.teamcity.jenkinsbridge.feature;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class JenkinsTriggerCorrelationTest {
  @Test
  public void promotionIdRoundTripsWithoutEncoding() {
    String value = JenkinsTriggerCorrelation.encode(42L);
    assertEquals("42", value);
    assertEquals(Long.valueOf(42L), JenkinsTriggerCorrelation.decode(value));
    assertEquals("Jenkins Bridge: TeamCity promotion 42", JenkinsTriggerCorrelation.cause(42L));
  }

  @Test
  public void malformedPayloadIsIgnored() {
    assertNull(JenkinsTriggerCorrelation.decode("not-a-promotion-id"));
  }
}
