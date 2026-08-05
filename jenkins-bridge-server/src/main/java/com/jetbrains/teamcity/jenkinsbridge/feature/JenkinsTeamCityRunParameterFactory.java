package com.jetbrains.teamcity.jenkinsbridge.feature;

import com.jetbrains.teamcity.jenkinsbridge.model.JenkinsParameterDefinition;
import com.jetbrains.teamcity.jenkinsbridge.teamcity.TeamCityBuildParameters;
import jetbrains.buildServer.serverSide.Parameter;
import jetbrains.buildServer.serverSide.parameters.ParameterFactory;

import java.util.List;
import java.util.Locale;

/**
 * Converts Jenkins job parameter definitions into TeamCity build-configuration parameters that are
 * prompted in the normal TeamCity Run dialog.
 */
public final class JenkinsTeamCityRunParameterFactory {
  private static final String PROMPT_TEXT_SPEC = "text display='prompt'";
  private static final String BOOLEAN_SPEC =
      "checkbox checkedValue='true' uncheckedValue='false' display='prompt'";

  private JenkinsTeamCityRunParameterFactory() {
  }

  public static boolean canImport(JenkinsParameterDefinition definition) {
    if (definition == null) {
      return false;
    }
    String name = definition.getName();
    if (name.trim().isEmpty()) {
      return false;
    }

    String lowerName = name.toLowerCase(Locale.ROOT);
    return !BridgeBuildFeatureConstants.JENKINS_BUILD_KEY_PARAM.equals(name)
        && !TeamCityBuildParameters.AGENTLESS_BUILD_PROPERTY.equals(name)
        && !lowerName.startsWith("teamcity.")
        && !lowerName.startsWith("jenkins.build.")
        && !lowerName.startsWith("jenkins.bridge.");
  }

  public static Parameter create(ParameterFactory parameterFactory, JenkinsParameterDefinition definition) {
    String name = definition.getName();
    String value = defaultValue(definition);
    String spec = spec(definition);
    return parameterFactory.createTypedParameter(name, value, spec);
  }

  static String defaultValue(JenkinsParameterDefinition definition) {
    if (isBoolean(definition)) {
      return Boolean.parseBoolean(definition.getDefaultValue()) ? "true" : "false";
    }

    List<String> choices = definition.getChoices();
    if (!choices.isEmpty() && !choices.contains(definition.getDefaultValue())) {
      return choices.get(0);
    }
    return definition.getDefaultValue();
  }

  static String spec(JenkinsParameterDefinition definition) {
    if (isBoolean(definition)) {
      return BOOLEAN_SPEC;
    }

    List<String> choices = definition.getChoices();
    if (!choices.isEmpty()) {
      StringBuilder spec = new StringBuilder("select display='prompt'");
      for (int i = 0; i < choices.size(); i++) {
        String encoded = quote(choices.get(i));
        spec.append(" data_").append(i + 1).append("='").append(encoded).append("'");
        spec.append(" label_").append(i + 1).append("='").append(encoded).append("'");
      }
      return spec.toString();
    }

    return PROMPT_TEXT_SPEC;
  }

  private static boolean isBoolean(JenkinsParameterDefinition definition) {
    return definition.getType().contains("Boolean");
  }

  private static String quote(String value) {
    StringBuilder result = new StringBuilder();
    String safeValue = value == null ? "" : value;
    for (int i = 0; i < safeValue.length(); i++) {
      char ch = safeValue.charAt(i);
      switch (ch) {
        case '|':
          result.append("||");
          break;
        case '\'':
          result.append("|'");
          break;
        case '\n':
          result.append("|n");
          break;
        case '\r':
          result.append("|r");
          break;
        case '[':
          result.append("|[");
          break;
        case ']':
          result.append("|]");
          break;
        default:
          result.append(ch);
      }
    }
    return result.toString();
  }
}
