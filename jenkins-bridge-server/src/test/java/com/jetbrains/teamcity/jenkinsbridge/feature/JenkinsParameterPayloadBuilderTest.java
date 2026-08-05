package com.jetbrains.teamcity.jenkinsbridge.feature;

import com.google.gson.JsonParser;
import com.jetbrains.teamcity.jenkinsbridge.model.JenkinsJobParameters;
import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

public class JenkinsParameterPayloadBuilderTest {
  @Test
  public void forwardsOnlyDeclaredJenkinsParametersAndFallsBackToDefaults() {
    JenkinsJobParameters definitions = definitions(
        "{\"property\":[{\"parameterDefinitions\":["
            + "{\"name\":\"BRANCH\",\"type\":\"StringParameterDefinition\",\"defaultParameterValue\":{\"value\":\"main\"}},"
            + "{\"name\":\"DEBUG\",\"type\":\"BooleanParameterDefinition\",\"defaultParameterValue\":{\"value\":false}}"
            + "]}]}");
    Map<String, String> source = new LinkedHashMap<String, String>();
    source.put("BRANCH", "feature/x");
    source.put("TEAMCITY_ONLY", "ignored");

    Map<String, String> payload = JenkinsParameterPayloadBuilder.build(definitions, source);

    assertEquals(2, payload.size());
    assertEquals("feature/x", payload.get("BRANCH"));
    assertEquals("false", payload.get("DEBUG"));
    assertFalse(payload.containsKey("TEAMCITY_ONLY"));
  }

  private static JenkinsJobParameters definitions(String json) {
    return JenkinsJobParameters.fromJson(JsonParser.parseString(json).getAsJsonObject());
  }
}
