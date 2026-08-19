package com.jetbrains.teamcity.jenkinsbridge.model;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Jenkins CSRF crumb from {@code /crumbIssuer/api/json}. When crumb protection is disabled
 * (the endpoint returns 404), use {@link #disabled()}: callers then send no crumb header.
 */
public class JenkinsCrumb {
  private final String field;
  private final String value;

  private JenkinsCrumb(String field, String value) {
    this.field = field;
    this.value = value;
  }

  public static JenkinsCrumb disabled() {
    return new JenkinsCrumb(null, null);
  }

  public static JenkinsCrumb fromJson(@NotNull JsonObject json) {
    String field = getString(json, "crumbRequestField");
    String value = getString(json, "crumb");
    if (field == null || value == null) {
      return disabled();
    }
    return new JenkinsCrumb(field, value);
  }

  /** True when a crumb header should be sent. */
  public boolean isPresent() {
    return field != null && value != null;
  }

  @Nullable
  public String getField() {
    return field;
  }

  @Nullable
  public String getValue() {
    return value;
  }

  private static String getString(JsonObject json, String name) {
    JsonElement element = json.get(name);
    if (element == null || element.isJsonNull()) {
      return null;
    }
    return element.getAsString();
  }
}
