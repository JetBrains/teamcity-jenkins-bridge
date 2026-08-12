package com.jetbrains.teamcity.jenkinsbridge.teamcity;

import jetbrains.buildServer.serverSide.BuildPromotion;
import jetbrains.buildServer.serverSide.BuildsManager;
import jetbrains.buildServer.serverSide.QueuedBuildEx;
import jetbrains.buildServer.serverSide.RunningBuildEx;
import jetbrains.buildServer.serverSide.SBuild;
import jetbrains.buildServer.serverSide.SQueuedBuild;

public class TeamCityBuildStarter {
  private final BuildsManager buildsManager;
  private final TeamCityRunningBuildLocator buildLocator;

  public TeamCityBuildStarter(BuildsManager buildsManager, TeamCityRunningBuildLocator buildLocator) {
    this.buildsManager = buildsManager;
    this.buildLocator = buildLocator;
  }

  public void markBuildAsRunning(long buildId, String requestor) {
    SBuild build = buildsManager.findBuildInstanceById(buildId);
    if (build != null) {
      return;
    }

    BuildPromotion promotion = buildLocator.findPromotion(buildId);

    SBuild associatedBuild = promotion.getAssociatedBuild();
    if (associatedBuild != null) {
      return;
    }

    SQueuedBuild queuedBuild = promotion.getQueuedBuild();
    if (queuedBuild == null) {
      throw new IllegalStateException("TeamCity build promotion " + buildId + " is not queued");
    }
    if (!(queuedBuild instanceof QueuedBuildEx)) {
      throw new IllegalStateException("TeamCity build promotion " + buildId + " cannot be started as an agentless build");
    }

    detachFromAgent(((QueuedBuildEx)queuedBuild).startBuild(requestor));
  }

  private void detachFromAgent(RunningBuildEx runningBuild) {
    if (!runningBuild.isCompositeBuild()) {
      runningBuild.detachedFromAgent();
    }
  }
}
