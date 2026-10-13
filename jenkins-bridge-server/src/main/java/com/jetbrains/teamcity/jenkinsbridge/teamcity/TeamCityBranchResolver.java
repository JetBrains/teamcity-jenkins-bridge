package com.jetbrains.teamcity.jenkinsbridge.teamcity;

import com.intellij.openapi.diagnostic.Logger;
import com.jetbrains.teamcity.jenkinsbridge.vcs.VcsProvider;
import com.jetbrains.teamcity.jenkinsbridge.vcs.constants.GenericVcsConstants;
import com.jetbrains.teamcity.jenkinsbridge.vcs.constants.GitConstants;
import jetbrains.buildServer.serverSide.SBuildType;
import jetbrains.buildServer.vcs.SVcsRoot;
import jetbrains.buildServer.vcs.spec.BranchSpecs;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;

import static com.jetbrains.teamcity.jenkinsbridge.util.Utilities.normalizeRepositoryUrl;

/** One decision for both trigger directions, based on the actual typed ref and repository. */
public class TeamCityBranchResolver {
  private static final Logger LOG = Logger.getInstance(TeamCityBranchResolver.class.getName());
  private final BranchSpecs specs;
  public TeamCityBranchResolver(@NotNull BranchSpecs specs) { this.specs = specs; }

  public enum Outcome { NAMED, CONFIGURED_DEFAULT, NO_MAPPING }
  public record Decision(@NotNull Outcome outcome, @Nullable String branchName) {
    @NotNull public static Decision unmapped() { return new Decision(Outcome.NO_MAPPING, null); }
  }

  @NotNull public Decision resolve(@NotNull SBuildType type, @NotNull String ref, @NotNull String repository) {
    String normalized = normalizeRepositoryUrl(repository);
    if (normalized == null || !(ref.startsWith("refs/heads/") || ref.startsWith("refs/tags/"))) return Decision.unmapped();
    try {
      List<SVcsRoot> roots = type.getVcsRoots();
      if (roots == null) return Decision.unmapped();
      Set<String> names = new LinkedHashSet<>();
      boolean configuredDefault = false;
      for (SVcsRoot root : roots) {
        Decision decision = resolveRoot(root, ref, repository);
        if (decision.outcome() == Outcome.CONFIGURED_DEFAULT) configuredDefault = true;
        if (decision.outcome() == Outcome.NAMED) names.add(decision.branchName());
      }
      if (configuredDefault) return new Decision(Outcome.CONFIGURED_DEFAULT, null);
      if (names.size() == 1) return new Decision(Outcome.NAMED, names.iterator().next());
    } catch (RuntimeException e) {
      LOG.warn("Jenkins Bridge: optional branch mapping failed for " + type.getExternalId(), e);
    }
    return Decision.unmapped();
  }
  /** Revision pins are checked against their own root; an unrelated root cannot discard them. */
  @NotNull public Decision resolveRoot(@NotNull SVcsRoot root, @NotNull String ref, @NotNull String repository) {
    String normalized = normalizeRepositoryUrl(repository);
    if (normalized == null || !(ref.startsWith("refs/heads/") || ref.startsWith("refs/tags/"))) return Decision.unmapped();
    try {
      if (!VcsProvider.GIT.teamCityVcsName().equals(root.getVcsName())
          || !normalized.equals(normalizeRepositoryUrl(root.getProperty(GitConstants.URL_PROP)))) return Decision.unmapped();
      String configuredDefault = root.getProperty(GitConstants.BRANCH_PROP);
      if (configuredDefault == null || configuredDefault.isBlank()) return Decision.unmapped();
      String defaultRef = TeamCityBranch.fromJenkinsGit(configuredDefault).ref();
      if (ref.equals(defaultRef)) return new Decision(Outcome.CONFIGURED_DEFAULT, null);
      if (ref.startsWith("refs/tags/") && !"true".equals(root.getProperty(GitConstants.REPORT_TAG_REVISIONS_PROP))) return Decision.unmapped();
      String name = specs.parse(root.getProperty(GenericVcsConstants.BRANCH_SPEC_PROP)).getLogicalBranchName(ref);
      return name == null ? Decision.unmapped() : new Decision(Outcome.NAMED, name);
    } catch (RuntimeException e) {
      LOG.warn("Jenkins Bridge: optional VCS root branch inspection failed", e);
      return Decision.unmapped();
    }
  }

}
