package com.jetbrains.teamcity.jenkinsbridge.persistence;

import com.jetbrains.teamcity.jenkinsbridge.feature.BridgeBuildFeatureConstants;
import jetbrains.buildServer.serverSide.SBuild;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;

/**
 * Canonical way to resolve the {@link BuildMirror} for a TeamCity build. Every UI/web feature that
 * needs "which Jenkins mirror does this TeamCity build represent?" should go through here rather than
 * reading {@code jenkins.build.key} directly, so both creation flows are covered uniformly.
 *
 * <p>Resolution order:
 * <ol>
 *   <li>Primary — the {@code jenkins.build.key} build parameter (O(1) key lookup). Present on
 *       Jenkins-first mirror builds, which the bridge stamps at queue time.</li>
 *   <li>Secondary — scan for {@code teamCityBuildId == build.getBuildPromotion().getId()}. Covers
 *       TC-first builds (bound to the promotion id by the poller) and Jenkins-first builds alike,
 *       since the queuer stores the promotion id too.</li>
 *   <li>Tertiary — scan for {@code teamCityBuildId == build.getBuildId()}. Safety net for the
 *       restore-by-key path, which stores a REST build id rather than a promotion id.</li>
 * </ol>
 *
 * <p>All lookups are pure in-memory scans of {@link BuildMirrorStore}; no TeamCity or Jenkins API
 * calls are made.
 *
 * <p><b>TC-first metadata limitation.</b> TC-first promotions are created by the user before Jenkins
 * runs, so bridge metadata ({@code jenkins.build.key}, {@code jenkins.build.url}, ...) is available
 * only through the mirror binding this resolver returns — <em>not</em> as TeamCity build parameters.
 * A build step reading {@code %jenkins.build.key%} cannot see it for a TC-first run: at queue time the
 * Jenkins build number is not yet assigned, and after queueing the promotion's parameters are frozen
 * for already-executed steps. This is by construction, not a gap to close with late parameter stamping.
 */
public class BuildMirrorResolver {

  private final BuildMirrorStore mirrorStore;

  public BuildMirrorResolver(@NotNull BuildMirrorStore mirrorStore) {
    this.mirrorStore = mirrorStore;
  }

  /**
   * Returns the mirror associated with {@code build}, or {@code null} if none is bound. Only throws
   * {@link IOException} if storage access itself fails — a missing mirror returns {@code null}.
   */
  @Nullable
  public BuildMirror resolve(@NotNull SBuild build) throws IOException {
    // Primary: jenkins.build.key parameter (Jenkins-first, fast path).
    String key = build.getParametersProvider().get(BridgeBuildFeatureConstants.JENKINS_BUILD_KEY_PARAM);
    if (key != null && !key.trim().isEmpty()) {
      BuildMirror mirror = mirrorStore.findMirror(key);
      if (mirror != null) {
        return mirror;
      }
      // Parameter present but no mirror (e.g. stale key after pruning): fall through to id scans.
    }

    // Secondary: teamCityBuildId == promotion id (works for both creation flows).
    long promotionId = build.getBuildPromotion().getId();
    BuildMirror byPromotion = mirrorStore.findMirrorByTcBuildId(promotionId);
    if (byPromotion != null) {
      return byPromotion;
    }

    // Tertiary: teamCityBuildId == build id (restore-by-key path stored a REST build id).
    long buildId = build.getBuildId();
    if (buildId != promotionId) {
      return mirrorStore.findMirrorByTcBuildId(buildId);
    }

    return null;
  }
}
