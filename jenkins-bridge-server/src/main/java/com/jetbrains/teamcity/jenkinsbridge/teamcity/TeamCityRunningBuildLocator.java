package com.jetbrains.teamcity.jenkinsbridge.teamcity;

import jetbrains.buildServer.serverSide.BuildPromotion;
import jetbrains.buildServer.serverSide.BuildPromotionManager;
import jetbrains.buildServer.serverSide.BuildsManager;
import jetbrains.buildServer.serverSide.RunningBuildEx;
import jetbrains.buildServer.serverSide.SBuild;
import jetbrains.buildServer.serverSide.SRunningBuild;

/**
 * Resolves a TeamCity running build / promotion from the id the bridge stores as
 * {@code teamCityBuildId}.
 * <p>
 * That id can be either a build <b>promotion</b> id (the queue path stores
 * {@code SQueuedBuild.getBuildPromotion().getId()}) or a build id (the REST restore-by-key path
 * stores the REST {@code build(id)}). Promotion id and build id are not contractually the same
 * value, so lookups try a build lookup first and then a promotion lookup. This centralizes logic
 * that was previously duplicated across the build adapters (R6 in RELIABILITY_AND_PERFORMANCE.md).
 */
public class TeamCityRunningBuildLocator {
  private final BuildsManager buildsManager;
  private final BuildPromotionManager buildPromotionManager;

  public TeamCityRunningBuildLocator(BuildsManager buildsManager, BuildPromotionManager buildPromotionManager) {
    this.buildsManager = buildsManager;
    this.buildPromotionManager = buildPromotionManager;
  }

  /**
   * @return the running build for {@code id}, or {@code null} if the build exists but is already
   * finished / not in a runnable state.
   * @throws IllegalStateException if no build or promotion can be found for {@code id} at all.
   */
  public RunningBuildEx findRunningBuild(long id) {
    SRunningBuild runningBuild = buildsManager.findRunningBuildById(id);
    if (runningBuild instanceof RunningBuildEx) {
      return (RunningBuildEx) runningBuild;
    }

    SBuild build = buildsManager.findBuildInstanceById(id);
    if (build != null) {
      if (build.isFinished()) {
        return null;
      }
      if (build instanceof RunningBuildEx) {
        return (RunningBuildEx) build;
      }
    }

    SBuild associatedBuild = findPromotion(id).getAssociatedBuild();
    if (associatedBuild == null) {
      throw new IllegalStateException("TeamCity build " + id + " is not running");
    }
    if (associatedBuild.isFinished()) {
      return null;
    }
    if (associatedBuild instanceof RunningBuildEx) {
      return (RunningBuildEx) associatedBuild;
    }

    throw new IllegalStateException("TeamCity build " + id + " is not a running build");
  }

  /**
   * @return the build promotion for {@code id} (treated as a promotion id).
   * @throws IllegalStateException if no promotion can be found.
   */
  public BuildPromotion findPromotion(long id) {
    BuildPromotion promotion = buildPromotionManager.findPromotionOrReplacement(id);
    if (promotion == null) {
      throw new IllegalStateException("TeamCity build " + id + " was not found");
    }
    return promotion;
  }
}
