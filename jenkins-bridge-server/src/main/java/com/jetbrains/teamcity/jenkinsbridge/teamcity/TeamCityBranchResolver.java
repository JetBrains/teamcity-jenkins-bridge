package com.jetbrains.teamcity.jenkinsbridge.teamcity;

import com.intellij.openapi.diagnostic.Logger;
import com.jetbrains.teamcity.jenkinsbridge.vcs.VcsProvider;
import com.jetbrains.teamcity.jenkinsbridge.vcs.VcsRefType;
import com.jetbrains.teamcity.jenkinsbridge.vcs.constants.GenericVcsConstants;
import com.jetbrains.teamcity.jenkinsbridge.vcs.constants.GitConstants;
import jetbrains.buildServer.serverSide.SBuildType;
import jetbrains.buildServer.vcs.SVcsRoot;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Resolves whether a Jenkins Git ref should be passed as TeamCity's explicit desired branch.
 * TeamCity validates that desired branch against the revisions selected from its VCS roots.
 */
final class TeamCityBranchResolver {
  private static final Logger LOG = Logger.getInstance(TeamCityBranchResolver.class.getName());

  private TeamCityBranchResolver() {
  }

  /**
   * Returns the desired branch name, or {@code null} when the ref is already the configured
   * default. Leaving the default unset avoids labeling an all-default-revision build as a separate
   * branch build. An attached Git root that neither monitors the ref nor uses it as its default
   * causes queueing to fail instead of silently using an unrelated revision.
   */
  @Nullable
  static String resolve(SBuildType buildType, String branchName, VcsRefType refType)
      throws TeamCityBuildQueueException {
    BranchMapping mapping = classify(buildType, branchName, refType);
    if (mapping == BranchMapping.CONFIGURED_DEFAULT) {
      LOG.info("Jenkins Bridge: using TeamCity's default branch for Jenkins branch " + branchName);
      return null;
    }
    if (mapping == BranchMapping.UNMONITORED) {
      throw unmonitoredBranchException(buildType, branchName, refType);
    }
    if (mapping == BranchMapping.NO_GIT_ROOT) {
      LOG.info("Jenkins Bridge: leaving TeamCity branch unset for Jenkins branch " + branchName
          + " because the build type has no attached Git VCS root");
      return null;
    }
    // A monitored ref or unavailable root metadata preserves the previous named-branch behavior.
    return branchName;
  }

  private static BranchMapping classify(SBuildType buildType, String branchName, VcsRefType refType) {
    String requestedRef = toGitRef(branchName, refType);
    List<SVcsRoot> vcsRoots = buildType.getVcsRoots();
    if (vcsRoots == null) {
      return BranchMapping.ROOTS_UNAVAILABLE;
    }

    boolean hasAttachedGitRoot = false;
    boolean matchesConfiguredDefault = false;
    for (SVcsRoot root : vcsRoots) {
      if (!isGitRoot(root)) {
        continue;
      }
      hasAttachedGitRoot = true;

      boolean isConfiguredDefault = isConfiguredDefaultRef(root, requestedRef);
      if (isConfiguredDefault) {
        matchesConfiguredDefault = true;
        continue;
      }

      boolean isMonitoredBranch = isRefIncludedByBranchSpec(root, requestedRef);
      if (isMonitoredBranch) {
        return BranchMapping.MONITORED;
      }
    }

    if (matchesConfiguredDefault) {
      return BranchMapping.CONFIGURED_DEFAULT;
    }
    if (hasAttachedGitRoot) {
      return BranchMapping.UNMONITORED;
    }
    return BranchMapping.NO_GIT_ROOT;
  }

  private static boolean isGitRoot(SVcsRoot root) {
    return VcsProvider.GIT.teamCityVcsName().equals(root.getVcsName());
  }

  private static boolean isConfiguredDefaultRef(SVcsRoot root, String requestedRef) {
    // TeamCity stores a Git root's configured default ref in its "branch" property.
    String configuredDefaultRef = root.getProperty(GitConstants.BRANCH_PROP);
    return requestedRef.equals(configuredDefaultRef);
  }

  private static boolean isRefIncludedByBranchSpec(SVcsRoot root, String requestedRef) {
    String branchSpec = root.getProperty(GenericVcsConstants.BRANCH_SPEC_PROP);
    return branchSpecIncludes(branchSpec, requestedRef);
  }

  /**
   * TeamCity's Git branch specification lists which remote refs are monitored as named branches.
   * Each nonempty rule starts with {@code +:} to include refs or {@code -:} to exclude them, for
   * example {@code +:refs/heads/*} for all branches or {@code +:refs/tags/*} for all tags. Rules
   * are applied in order, so the last matching rule decides whether the ref is included.
   */
  private static boolean branchSpecIncludes(@Nullable String branchSpec, String requestedRef) {
    if (branchSpec == null || branchSpec.trim().isEmpty()) {
      return false;
    }

    Boolean included = null;
    for (String ruleLine : branchSpec.split("\\R")) {
      String rule = ruleLine.trim();
      if (!isBranchSpecRule(rule)) {
        continue;
      }

      String pattern = rule.substring(2).trim();
      if (branchSpecPatternMatches(pattern, requestedRef)) {
        included = rule.charAt(0) == '+';
      }
    }
    return Boolean.TRUE.equals(included);
  }

  private static boolean isBranchSpecRule(String rule) {
    if (rule.length() < 3 || rule.charAt(1) != ':') {
      return false;
    }
    char action = rule.charAt(0);
    return action == '+' || action == '-';
  }

  /**
   * Matches one TeamCity branch-spec pattern against a full Git ref. The {@code *} wildcard matches
   * any characters; parentheses mark a named capture in TeamCity's syntax and do not change which
   * ref matches. For example, {@code refs/heads/(feature-*)} matches
   * {@code refs/heads/feature-login}. Other characters are treated literally.
   */
  private static boolean branchSpecPatternMatches(String pattern, String requestedRef) {
    StringBuilder regex = new StringBuilder("^");
    for (int i = 0; i < pattern.length(); i++) {
      char character = pattern.charAt(i);
      if (character == '*') {
        regex.append(".*");
      } else if (character != '(' && character != ')') {
        regex.append(Pattern.quote(String.valueOf(character)));
      }
    }
    regex.append('$');
    return requestedRef.matches(regex.toString());
  }

  private static String toGitRef(String branchName, VcsRefType refType) {
    String prefix = refType == VcsRefType.TAGS ? "refs/tags/" : "refs/heads/";
    return prefix + branchName;
  }

  private static TeamCityBuildQueueException unmonitoredBranchException(
      SBuildType buildType, String branchName, VcsRefType refType) {
    String ref = toGitRef(branchName, refType);
    return new TeamCityBuildQueueException("Cannot queue Jenkins branch '" + branchName
        + "' for TeamCity build type " + buildType.getExternalId() + ": no attached Git VCS root monitors "
        + ref + " or uses it as its default branch. Add the ref to a Git VCS root branch specification "
        + "or configure it as the root's default branch.");
  }

  private enum BranchMapping {
    MONITORED,
    CONFIGURED_DEFAULT,
    UNMONITORED,
    NO_GIT_ROOT,
    ROOTS_UNAVAILABLE
  }
}
