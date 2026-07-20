package com.jetbrains.teamcity.jenkinsbridge.feature;

import com.jetbrains.teamcity.jenkinsbridge.model.JenkinsJobParameters;
import com.jetbrains.teamcity.jenkinsbridge.model.JenkinsParameterDefinition;

import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Builds the parameter payload accepted by Jenkins buildWithParameters from Jenkins' own declared
 * parameter definitions and a caller-specific source of values.
 */
public final class JenkinsParameterPayloadBuilder {
  private JenkinsParameterPayloadBuilder() {
  }

  public static Map<String, String> build(
      JenkinsJobParameters definitions,
      Map<String, String> values
  ) {
    if (definitions == null || definitions.getParameters().isEmpty()) {
      return Collections.emptyMap();
    }

    Map<String, String> safeValues = values == null ? Collections.<String, String>emptyMap() : values;
    Map<String, String> result = new LinkedHashMap<String, String>();
    Set<String> seen = new HashSet<String>();
    for (JenkinsParameterDefinition definition : definitions.getParameters()) {
      String name = definition.getName();
      if (name.trim().isEmpty() || !seen.add(name)) {
        continue;
      }
      String value = safeValues.get(name);
      result.put(name, value != null ? value : definition.getDefaultValue());
    }
    return result;
  }
}
