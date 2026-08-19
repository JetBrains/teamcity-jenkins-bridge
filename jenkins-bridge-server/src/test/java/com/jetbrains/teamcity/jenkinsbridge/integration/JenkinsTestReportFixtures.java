package com.jetbrains.teamcity.jenkinsbridge.integration;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.jetbrains.teamcity.jenkinsbridge.model.JenkinsTestReport;

/** Small wire-format fixtures shared by TeamCity reporting integration tests. */
final class JenkinsTestReportFixtures {
  private JenkinsTestReportFixtures() {
  }

  static JenkinsTestReport passedTest(String suiteName, String className, String testName) {
    JsonObject test = new JsonObject();
    test.addProperty("className", className);
    test.addProperty("name", testName);
    test.addProperty("status", "PASSED");

    JsonArray cases = new JsonArray();
    cases.add(test);

    JsonObject suite = new JsonObject();
    suite.addProperty("name", suiteName);
    suite.add("cases", cases);

    JsonArray suites = new JsonArray();
    suites.add(suite);

    JsonObject report = new JsonObject();
    report.add("suites", suites);
    return JenkinsTestReport.fromJson(report);
  }
}
