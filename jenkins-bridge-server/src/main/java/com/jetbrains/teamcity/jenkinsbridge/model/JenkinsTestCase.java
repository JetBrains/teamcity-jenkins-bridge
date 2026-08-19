package com.jetbrains.teamcity.jenkinsbridge.model;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.jetbrains.annotations.NotNull;

import static com.jetbrains.teamcity.jenkinsbridge.util.Utilities.nullToEmpty;

public class JenkinsTestCase {
  private static final String STATUS_SKIPPED = "SKIPPED";
  private static final String STATUS_PASSED = "PASSED";

  private String className;
  private String name;
  // TODO should probably be an enum, and cast to that enum during the construciton. This is currently confusing
  private String status;
  private int durationMillis;
  private String errorDetails;
  private String errorStackTrace;
  private String skippedMessage;
  private String stdout;
  private String stderr;

  public static JenkinsTestCase fromJson(@NotNull JsonObject json) {
    JenkinsTestCase testCase = new JenkinsTestCase();
    testCase.className = getString(json, "className", "");
    testCase.name = getString(json, "name", "");
    testCase.status = getString(json, "status", "");
    testCase.durationMillis = secondsToMillis(getDouble(json, "duration", 0D));
    testCase.errorDetails = getString(json, "errorDetails", "");
    testCase.errorStackTrace = getString(json, "errorStackTrace", "");
    testCase.skippedMessage = getString(json, "skippedMessage", "");
    testCase.stdout = getString(json, "stdout", "");
    testCase.stderr = getString(json, "stderr", "");
    return testCase;
  }

  @NotNull
  public String getClassName() {
    return className;
  }

  @NotNull
  public String getName() {
    return name;
  }

  @NotNull
  public String getStatus() {
    return status;
  }

  public int getDurationMillis() {
    return durationMillis;
  }

  @NotNull
  public String getErrorDetails() {
    return errorDetails;
  }

  @NotNull
  public String getErrorStackTrace() {
    return errorStackTrace;
  }

  @NotNull
  public String getSkippedMessage() {
    return skippedMessage;
  }

  @NotNull
  public String getStdout() {
    return stdout;
  }

  @NotNull
  public String getStderr() {
    return stderr;
  }

  public String getTeamCityTestName() {
    if (isNotBlank(className) && isNotBlank(name)) {
      return className + "." + name;
    }
    if (isNotBlank(name)) {
      return name;
    }
    if (isNotBlank(className)) {
      return className;
    }
    return "Unnamed Jenkins test";
  }

  public boolean isIgnored() {
    return STATUS_SKIPPED.equalsIgnoreCase(nullToEmpty(status));
  }

  public boolean isFailed() {
    String normalizedStatus = nullToEmpty(status);
    if (normalizedStatus.length() == 0) {
      return false;
    }
    return !STATUS_PASSED.equalsIgnoreCase(normalizedStatus) && !isIgnored();
  }

  public boolean hasStdout() {
    return isNotBlank(stdout);
  }

  public boolean hasStderr() {
    return isNotBlank(stderr);
  }

  private static int secondsToMillis(double seconds) {
    if (seconds <= 0D) {
      return 0;
    }
    double millis = seconds * 1000D;
    if (millis >= Integer.MAX_VALUE) {
      return Integer.MAX_VALUE;
    }
    return (int)Math.round(millis);
  }

  private static String getString(JsonObject json, String name, String defaultValue) {
    JsonElement element = json.get(name);
    if (element == null || element.isJsonNull()) {
      return defaultValue;
    }
    return element.getAsString();
  }

  private static double getDouble(JsonObject json, String name, double defaultValue) {
    JsonElement element = json.get(name);
    if (element == null || element.isJsonNull()) {
      return defaultValue;
    }
    return element.getAsDouble();
  }

  private static boolean isNotBlank(String value) {
    return value != null && value.trim().length() > 0;
  }

}
