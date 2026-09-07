package com.jetbrains.teamcity.jenkinsbridge.feature;

import org.jetbrains.annotations.Nullable;

/** Handles the plain TeamCity promotion ID used for cross-node trigger correlation. */
public final class JenkinsTriggerCorrelation {
  private JenkinsTriggerCorrelation() {
  }

  public static String encode(long promotionId) {
    return Long.toString(promotionId);
  }

  @Nullable
  public static Long decode(@Nullable String value) {
    if (value == null || value.trim().isEmpty()) return null;
    try {
      long promotionId = Long.parseLong(value.trim());
      return promotionId >= 0 ? promotionId : null;
    } catch (NumberFormatException ignored) {
      return null;
    }
  }

  public static String cause(long promotionId) {
    return "Jenkins Bridge: TeamCity promotion " + promotionId;
  }
}
