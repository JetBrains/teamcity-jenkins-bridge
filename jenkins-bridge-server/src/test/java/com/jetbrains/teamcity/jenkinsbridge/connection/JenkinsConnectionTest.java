package com.jetbrains.teamcity.jenkinsbridge.connection;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;

public class JenkinsConnectionTest {
  @Test
  public void getUrlDropsTrailingSlashes() {
    assertEquals("http://jenkins:8080", new JenkinsConnection("http://jenkins:8080///", "u", "t").getUrl());
    assertEquals("http://jenkins:8080", new JenkinsConnection("  http://jenkins:8080/ ", "u", "t").getUrl());
    assertEquals("http://jenkins:8080", new JenkinsConnection("http://jenkins:8080", "u", "t").getUrl());
  }

  @Test
  public void constructorTurnsNullsIntoEmptyValues() {
    JenkinsConnection connection = new JenkinsConnection(null, null, null);

    assertEquals("", connection.getUrl());
    assertEquals("", connection.getUser());
    assertEquals("", connection.getToken());
  }

  @Test
  public void fromParametersReadsTheConnectionFeatureParameters() {
    Map<String, String> parameters = new HashMap<>();
    parameters.put(JenkinsConnectionConstants.PARAM_URL, "http://jenkins:8080/");
    parameters.put(JenkinsConnectionConstants.PARAM_USER, " ci-user ");
    parameters.put(JenkinsConnectionConstants.PARAM_TOKEN, "11aabbcc");

    JenkinsConnection connection = JenkinsConnection.fromParameters(parameters);

    assertEquals("http://jenkins:8080", connection.getUrl());
    assertEquals("ci-user", connection.getUser());
    assertEquals("11aabbcc", connection.getToken());
  }
}
