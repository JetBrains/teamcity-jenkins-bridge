package com.jetbrains.teamcity.jenkinsbridge.teamcity;

import com.intellij.openapi.diagnostic.Logger;

import java.util.Date;

/** Converts a failed Jenkins trigger attempt into a failed TeamCity build. */
public class TeamCityTriggeredBuildFailureHandler {
  private static final Logger LOG = Logger.getInstance(TeamCityTriggeredBuildFailureHandler.class.getName());

  private final TeamCityBuildStarter buildStarter;
  private final TeamCityBuildFinisher buildFinisher;

  public TeamCityTriggeredBuildFailureHandler(
      TeamCityBuildStarter buildStarter,
      TeamCityBuildFinisher buildFinisher
  ) {
    this.buildStarter = buildStarter;
    this.buildFinisher = buildFinisher;
  }

  public void fail(long promotionId, String reason) {
    try {
      buildStarter.markBuildAsRunning(promotionId, "Jenkins Bridge");
      buildFinisher.finishBuildAsBridgeFailure(promotionId, new Date(), reason);
    } catch (RuntimeException e) {
      LOG.error("Jenkins Bridge could not fail TeamCity promotion " + promotionId, e);
      throw e;
    }
  }
}
