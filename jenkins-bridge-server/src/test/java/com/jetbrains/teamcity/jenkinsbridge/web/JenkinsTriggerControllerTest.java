package com.jetbrains.teamcity.jenkinsbridge.web;

import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class JenkinsTriggerControllerTest {

  @Test
  public void configParametersExcludeJenkinsDeclaredNames() {
    Map<String, String> configParams = new LinkedHashMap<String, String>();
    configParams.put("BRANCH", "main");                 // Jenkins-declared -> excluded
    configParams.put("teamcity.build.agentLess", "true"); // TC config -> kept
    configParams.put("MY_CONFIG", "x");                  // user config -> kept

    Set<String> declared = new HashSet<String>();
    declared.add("BRANCH");

    List<JenkinsTriggerController.ConfigParamView> views =
        JenkinsTriggerController.configParameters(configParams, declared);

    assertEquals(2, views.size());
    // Sorted by name: MY_CONFIG, teamcity.build.agentLess
    assertEquals("MY_CONFIG", views.get(0).name);
    assertEquals("x", views.get(0).value);
    assertEquals("teamcity.build.agentLess", views.get(1).name);
    assertEquals("true", views.get(1).value);
  }

  @Test
  public void configParametersEmptyWhenAllJenkinsDeclared() {
    Map<String, String> configParams = new LinkedHashMap<String, String>();
    configParams.put("BRANCH", "main");
    configParams.put("TAG", "v1");

    Set<String> declared = new HashSet<String>();
    declared.add("BRANCH");
    declared.add("TAG");

    assertTrue(JenkinsTriggerController.configParameters(configParams, declared).isEmpty());
  }

  @Test
  public void configParametersToleratesNulls() {
    assertTrue(JenkinsTriggerController.configParameters(null, null).isEmpty());
  }
}
