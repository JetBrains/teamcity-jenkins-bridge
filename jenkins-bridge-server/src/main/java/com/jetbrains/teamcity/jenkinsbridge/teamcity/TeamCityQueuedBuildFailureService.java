package com.jetbrains.teamcity.jenkinsbridge.teamcity;

import com.intellij.openapi.diagnostic.Logger;
import jetbrains.buildServer.BuildProblemData;
import jetbrains.buildServer.messages.ErrorData;
import jetbrains.buildServer.serverSide.BuildPromotion;
import jetbrains.buildServer.serverSide.QueuedBuildEx;
import jetbrains.buildServer.serverSide.SQueuedBuild;

/** Marks a queued TeamCity promotion as failed to start without starting it. */
public class TeamCityQueuedBuildFailureService {
  private static final Logger LOG = Logger.getInstance(TeamCityQueuedBuildFailureService.class.getName());
  private static final String TRIGGER_FAILURE_IDENTITY = "jenkinsBridgeTriggerFailure";

  private final TeamCityRunningBuildLocator buildLocator;

  public TeamCityQueuedBuildFailureService(TeamCityRunningBuildLocator buildLocator) {
    this.buildLocator = buildLocator;
  }

  public void failQueuedPromotion(long promotionId, String reason) {
    try {
      BuildPromotion promotion = buildLocator.findPromotion(promotionId);
      SQueuedBuild queuedBuild = promotion.getQueuedBuild();
      if (!(queuedBuild instanceof QueuedBuildEx)) {
        throw new IllegalStateException(
            "TeamCity promotion " + promotionId + " is no longer queued and cannot fail to start");
      }

      ((QueuedBuildEx) queuedBuild).terminate(
          false,
          reason,
          BuildProblemData.createBuildProblem(
              TRIGGER_FAILURE_IDENTITY,
              ErrorData.PREPARATION_FAILURE_TYPE,
              reason));
    } catch (RuntimeException e) {
      LOG.error("Jenkins Bridge could not mark queued TeamCity promotion " + promotionId
          + " as failed to start", e);
      throw e;
    }
  }
}
