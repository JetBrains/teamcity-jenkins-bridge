package com.jetbrains.teamcity.jenkinsbridge.teamcity;

import jetbrains.buildServer.serverSide.BuildPromotion;
import jetbrains.buildServer.serverSide.BuildPromotionManager;
import jetbrains.buildServer.serverSide.BuildQueryOptions;
import jetbrains.buildServer.serverSide.BuildsManager;
import jetbrains.buildServer.serverSide.ProjectManager;
import jetbrains.buildServer.serverSide.RunningBuildEx;
import jetbrains.buildServer.serverSide.SBuild;
import jetbrains.buildServer.serverSide.SBuildType;
import jetbrains.buildServer.serverSide.SRunningBuild;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static com.jetbrains.teamcity.jenkinsbridge.util.Utilities.findBuildType;

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
  /** Build parameter carrying the bridge identity of the mirrored Jenkins build. */
  public static final String JENKINS_BUILD_KEY_PARAM = "jenkins.build.key";

  /**
   * How many builds of a configuration to look at when recovering a lost mirror.
   */
  private static final int RECOVERY_SCAN_LIMIT = 1000;

  private final BuildsManager buildsManager;
  private final BuildPromotionManager buildPromotionManager;
  private final ProjectManager projectManager;

  public TeamCityRunningBuildLocator(BuildsManager buildsManager, BuildPromotionManager buildPromotionManager,
                                     ProjectManager projectManager) {
    this.buildsManager = buildsManager;
    this.buildPromotionManager = buildPromotionManager;
    this.projectManager = projectManager;
  }

  /**
   * Finds a mirror build that already exists in TeamCity but is no longer recorded in the bridge's
   * own state, by matching the {@code jenkins.build.key} parameter the bridge sets when it queues a
   * mirror. Scans at most {@link #RECOVERY_SCAN_LIMIT} builds of the configuration.
   *
   * @param buildTypeId     external or internal id of the mirroring build configuration
   * @param jenkinsBuildKey bridge identity of the Jenkins build
   * @return the promotion id of the matching build, or null when there is none
   */
  @Nullable
  public Long recoverBuildId(@Nullable String buildTypeId, @Nullable String jenkinsBuildKey) {
    if (jenkinsBuildKey == null || jenkinsBuildKey.trim().isEmpty()) {
      return null;
    }
    SBuildType buildType = findBuildType(buildTypeId, projectManager);
    if (buildType == null) {
      return null;
    }

    BuildQueryOptions options = new BuildQueryOptions()
        .setBuildTypeId(buildType.getBuildTypeId())
        .setMatchAllBranches(true)
        .setIncludeRunning(true)
        .setIncludeFinished(true)
        .setIncludeCanceled(true)
        .setOrderByChanges(false);

    AtomicLong found = new AtomicLong(0L);
    AtomicInteger scanned = new AtomicInteger(0);
    buildsManager.processBuilds(options, build -> {
      if (jenkinsBuildKey.equals(build.getBuildPromotion().getParameterValue(JENKINS_BUILD_KEY_PARAM))) {
        found.set(build.getBuildPromotion().getId());
        return false;
      }
      return scanned.incrementAndGet() < RECOVERY_SCAN_LIMIT;
    });

    return found.get() == 0L ? null : found.get();
  }

  /**
   * @return the running build for {@code id}, or {@code null} if the build exists but is already
   * finished / not in a runnable state.
   * @throws TeamCityRunningBuildNotFoundException if no build or promotion can be found for
   * {@code id} at all.
   */
  @Nullable
  public RunningBuildEx findRunningBuild(long id) throws TeamCityRunningBuildNotFoundException {
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

    BuildPromotion promotion;
    try {
      promotion = findPromotion(id);
    } catch (IllegalStateException e) {
      throw new TeamCityRunningBuildNotFoundException(
          "TeamCity build " + id + " was not found", e);
    }

    SBuild associatedBuild = promotion.getAssociatedBuild();
    if (associatedBuild == null) {
      throw new TeamCityRunningBuildNotFoundException(
          "TeamCity build " + id + " is not running");
    }
    if (associatedBuild.isFinished()) {
      return null;
    }
    if (associatedBuild instanceof RunningBuildEx) {
      return (RunningBuildEx) associatedBuild;
    }

    throw new TeamCityRunningBuildNotFoundException(
        "TeamCity build " + id + " is not a running build");
  }

  /**
   * Resolves a running build that must accept bridge messages.
   *
   * <p>Use {@link #findRunningBuild(long)} for idempotent operations such as build finishing, where
   * an already-finished build is a normal no-op. Use this method for data delivery, where accepting
   * no messages must never be treated as success.</p>
   */
  public RunningBuildEx requireRunningBuild(long id) throws TeamCityRunningBuildNotFoundException {
    RunningBuildEx runningBuild = findRunningBuild(id);
    if (runningBuild == null) {
      throw new TeamCityRunningBuildNotFoundException(
          "TeamCity running build not found for id " + id);
    }
    return runningBuild;
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
