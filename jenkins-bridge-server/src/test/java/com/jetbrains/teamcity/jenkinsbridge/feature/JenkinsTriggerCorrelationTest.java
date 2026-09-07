package com.jetbrains.teamcity.jenkinsbridge.feature;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class JenkinsTriggerCorrelationTest {
  @Test
  public void formatsCauseWithPromotionId() {
    assertEquals("Jenkins Bridge: TeamCity promotion 42", JenkinsTriggerCorrelation.cause(42L));
  }
}
