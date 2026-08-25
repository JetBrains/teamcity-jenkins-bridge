package com.jetbrains.teamcity.jenkinsbridge.model;

import com.google.gson.JsonObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class JenkinsJsonDefaultsTest {
  @Test
  public void missingArtifactsAndStagesAreEmpty() {
    JsonObject json = new JsonObject();

    assertTrue(JenkinsArtifacts.fromJson(json).isEmpty());
    assertTrue(JenkinsStages.fromJson(json).getStages().isEmpty());
  }

  @Test
  public void missingTestCaseStringsAreEmpty() {
    JenkinsTestCase testCase = JenkinsTestCase.fromJson(new JsonObject());

    assertEquals("", testCase.getClassName());
    assertEquals("", testCase.getName());
    assertEquals("", testCase.getStatus());
  }

  @Test
  public void buildCausesAreReadFromActions() {
    JsonObject json = com.google.gson.JsonParser.parseString(
        "{\"actions\":[{\"causes\":[{\"shortDescription\":\"Jenkins Bridge: promotion 42\"}]}]}")
        .getAsJsonObject();
    assertEquals("Jenkins Bridge: promotion 42", JenkinsBuildInfo.fromJson(json).getCauses().get(0));
  }
}
