package com.jetbrains.teamcity.jenkinsbridge.feature;

import com.google.gson.JsonParser;
import com.jetbrains.teamcity.jenkinsbridge.model.JenkinsJobParameters;
import com.jetbrains.teamcity.jenkinsbridge.model.JenkinsParameterDefinition;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class JenkinsTeamCityRunParameterFactoryTest {
  @Test
  public void createsPromptSpecsForKnownJenkinsParameterTypes() {
    JenkinsJobParameters definitions = definitions(
        "{\"property\":[{\"parameterDefinitions\":["
            + "{\"name\":\"BRANCH\",\"type\":\"StringParameterDefinition\",\"defaultParameterValue\":{\"value\":\"main\"}},"
            + "{\"name\":\"TARGET\",\"type\":\"ChoiceParameterDefinition\",\"defaultParameterValue\":{\"value\":\"prod\"},\"choices\":[\"dev\",\"prod\"]},"
            + "{\"name\":\"DEBUG\",\"type\":\"BooleanParameterDefinition\",\"defaultParameterValue\":{\"value\":true}}"
            + "]}]}");

    assertEquals("text display='prompt'",
        JenkinsTeamCityRunParameterFactory.spec(definitions.getParameters().get(0)));
    assertEquals("select display='prompt' data_1='dev' label_1='dev' data_2='prod' label_2='prod'",
        JenkinsTeamCityRunParameterFactory.spec(definitions.getParameters().get(1)));
    assertEquals("checkbox checkedValue='true' uncheckedValue='false' display='prompt'",
        JenkinsTeamCityRunParameterFactory.spec(definitions.getParameters().get(2)));
    assertEquals("true", JenkinsTeamCityRunParameterFactory.defaultValue(definitions.getParameters().get(2)));
  }

  @Test
  public void skipsBridgeAndTeamCityInternalParameterNames() {
    assertFalse(JenkinsTeamCityRunParameterFactory.canImport(definition("teamcity.build.agentLess")));
    assertFalse(JenkinsTeamCityRunParameterFactory.canImport(definition("jenkins.build.key")));
    assertFalse(JenkinsTeamCityRunParameterFactory.canImport(definition("teamcity.foo")));
    assertTrue(JenkinsTeamCityRunParameterFactory.canImport(definition("BRANCH_NAME")));
  }

  private static JenkinsParameterDefinition definition(String name) {
    return definitions("{\"property\":[{\"parameterDefinitions\":[{\"name\":\"" + name
        + "\",\"type\":\"StringParameterDefinition\",\"defaultParameterValue\":{\"value\":\"\"}}]}]}")
        .getParameters().get(0);
  }

  private static JenkinsJobParameters definitions(String json) {
    return JenkinsJobParameters.fromJson(JsonParser.parseString(json).getAsJsonObject());
  }
}
