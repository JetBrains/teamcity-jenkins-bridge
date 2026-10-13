package com.jetbrains.teamcity.jenkinsbridge.teamcity;

import jetbrains.buildServer.serverSide.BuildPromotion;
import jetbrains.buildServer.serverSide.BuildPromotionEx;
import org.jetbrains.annotations.NotNull;

import java.util.LinkedHashMap;
import java.util.Map;

/** TeamCity's native per-promotion policy; metadata warnings must not replace the Jenkins result. */
public final class TeamCityVcsFailurePolicy {
  public static final String INVALID_BRANCH_REPORTING = "teamcity.build.invalidBranchReporting";
  public static final String WARNING_ONLY = "logWarning";
  private TeamCityVcsFailurePolicy() {}
  public static void configure(@NotNull Map<String, String> parameters) {
    parameters.put(INVALID_BRANCH_REPORTING, WARNING_ONLY);
  }
  public static void configure(@NotNull BuildPromotion promotion) {
    if (promotion instanceof BuildPromotionEx ex) {
      Map<String, String> parameters = new LinkedHashMap<>(promotion.getCustomParameters());
      configure(parameters);
      ex.setCustomParameters(parameters);
    }
  }
}
