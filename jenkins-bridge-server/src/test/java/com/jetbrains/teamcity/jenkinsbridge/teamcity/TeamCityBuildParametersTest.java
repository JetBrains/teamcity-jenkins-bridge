package com.jetbrains.teamcity.jenkinsbridge.teamcity;

import org.junit.Test;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class TeamCityBuildParametersTest {
  @Test
  public void mergesJenkinsParametersWithoutPrefix() {
    Map<String, String> bridge = new LinkedHashMap<String, String>();
    bridge.put("jenkins.job", "job");
    bridge.put("jenkins.build.key", "job#1");

    Map<String, String> jenkins = new LinkedHashMap<String, String>();
    jenkins.put("BRANCH", "feature/x");
    jenkins.put("RUN_TESTS", "true");

    Map<String, String> merged = TeamCityBuildParameters.mergeWithJenkinsParameters(
        bridge, jenkins);

    assertEquals("job", merged.get("jenkins.job"));
    assertEquals("job#1", merged.get("jenkins.build.key"));
    assertEquals("feature/x", merged.get("BRANCH"));
    assertEquals("true", merged.get("RUN_TESTS"));
  }

  @Test
  public void failsWhenJenkinsParameterCollidesWithBridgeParameter() {
    Map<String, String> bridge = new LinkedHashMap<String, String>();
    bridge.put("jenkins.build.key", "job#1");

    Map<String, String> jenkins = new LinkedHashMap<String, String>();
    jenkins.put("jenkins.build.key", "evil");

    try {
      TeamCityBuildParameters.mergeWithJenkinsParameters(bridge, jenkins);
      fail("Expected collision to fail");
    } catch (IllegalStateException e) {
      assertTrue(e.getMessage().contains("jenkins.build.key"));
    }
  }

  @Test
  public void jenkinsParameterOverridesExistingTeamCityParameterDeclaration() {
    Map<String, String> jenkins = new LinkedHashMap<String, String>();
    jenkins.put("DEPLOY_ENV", "prod");

    Map<String, String> merged = TeamCityBuildParameters.mergeWithJenkinsParameters(
        Collections.<String, String>emptyMap(), jenkins);

    assertEquals("prod", merged.get("DEPLOY_ENV"));
  }

  @Test
  public void failsWhenJenkinsParameterCollidesWithAgentlessParameter() {
    Map<String, String> jenkins = new LinkedHashMap<String, String>();
    jenkins.put(TeamCityBuildParameters.AGENTLESS_BUILD_PROPERTY, "false");

    try {
      TeamCityBuildParameters.mergeWithJenkinsParameters(
          Collections.<String, String>emptyMap(),
          jenkins);
      fail("Expected collision to fail");
    } catch (IllegalStateException e) {
      assertTrue(e.getMessage().contains(TeamCityBuildParameters.AGENTLESS_BUILD_PROPERTY));
    }
  }

  @Test
  public void omitsBridgeInternalJenkinsParameters() {
    Map<String, String> jenkins = new LinkedHashMap<String, String>();
    jenkins.put("jenkins.bridge.generated.chain", "true");
    jenkins.put("jenkins.bridge.internal.value", "hidden");
    jenkins.put("VISIBLE", "shown");

    Map<String, String> merged = TeamCityBuildParameters.mergeWithJenkinsParameters(
        Collections.<String, String>emptyMap(), jenkins);

    assertFalse(merged.containsKey("jenkins.bridge.generated.chain"));
    assertFalse(merged.containsKey("jenkins.bridge.internal.value"));
    assertEquals("shown", merged.get("VISIBLE"));
  }
}
