package com.jetbrains.teamcity.jenkinsbridge.model;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.jetbrains.annotations.NotNull;

public class JenkinsTestSuite {
  private static final String DEFAULT_SUITE_NAME = "Jenkins tests";

  private String name;
  private List<JenkinsTestCase> cases;

  public static JenkinsTestSuite fromJson(JsonObject json) {
    JenkinsTestSuite suite = new JenkinsTestSuite();
    suite.name = getString(json, "name", DEFAULT_SUITE_NAME);

    JsonArray casesJson = json.getAsJsonArray("cases");
    List<JenkinsTestCase> cases = new ArrayList<JenkinsTestCase>();
    if (casesJson != null) {
      for (JsonElement caseJson : casesJson) {
        if (caseJson != null && caseJson.isJsonObject()) {
          cases.add(JenkinsTestCase.fromJson(caseJson.getAsJsonObject()));
        }
      }
    }
    suite.cases = Collections.unmodifiableList(cases);
    return suite;
  }

  @NotNull
  public String getName() {
    if (name == null || name.trim().length() == 0) {
      return DEFAULT_SUITE_NAME;
    }
    return name;
  }

  @NotNull
  public List<JenkinsTestCase> getCases() {
    return cases == null ? Collections.<JenkinsTestCase>emptyList() : cases;
  }

  private static String getString(JsonObject json, String name, String defaultValue) {
    JsonElement element = json.get(name);
    if (element == null || element.isJsonNull()) {
      return defaultValue;
    }
    return element.getAsString();
  }
}
