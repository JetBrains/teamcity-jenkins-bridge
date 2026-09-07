package com.jetbrains.teamcity.jenkinsbridge.feature;

/** Formats the Jenkins cause used to correlate a trigger with its TeamCity promotion. */
public final class JenkinsTriggerCorrelation {
  private JenkinsTriggerCorrelation() {
  }

  public static String cause(long promotionId) {
    return "Jenkins Bridge: TeamCity promotion " + promotionId;
  }
}
