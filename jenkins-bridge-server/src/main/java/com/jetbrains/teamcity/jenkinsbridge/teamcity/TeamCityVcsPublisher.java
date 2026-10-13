package com.jetbrains.teamcity.jenkinsbridge.teamcity;

import com.jetbrains.teamcity.jenkinsbridge.vcs.constants.GitConstants;
import com.intellij.openapi.diagnostic.Logger;
import com.jetbrains.teamcity.jenkinsbridge.jenkins.JenkinsClientFactory;
import com.jetbrains.teamcity.jenkinsbridge.model.*;
import com.jetbrains.teamcity.jenkinsbridge.persistence.BuildMirror;
import com.jetbrains.teamcity.jenkinsbridge.vcs.*;
import jetbrains.buildServer.serverSide.*;
import jetbrains.buildServer.serverSide.impl.CancelableTaskHolder;
import jetbrains.buildServer.vcs.*;
import jetbrains.buildServer.vcs.impl.BuildChainChangesCollector;
import org.jetbrains.annotations.NotNull;

import java.util.*;

import static com.jetbrains.teamcity.jenkinsbridge.util.Utilities.*;

/** Optional VCS metadata; repository defaults and each run's exact checkout are independent. */
public class TeamCityVcsPublisher {
  private static final Logger LOG = Logger.getInstance(TeamCityVcsPublisher.class.getName());
  private final ProjectManager projects;
  private final TeamCityRunningBuildLocator builds;
  private final BuildChainChangesCollector collector;
  private final JenkinsClientFactory clients;
  private final TeamCityVcsRootRepair rootRepair;
  private final TeamCityBranchResolver branchResolver;

  public TeamCityVcsPublisher(ProjectManager projects, TeamCityRunningBuildLocator builds,
      BuildChainChangesCollector collector, JenkinsClientFactory clients,
      TeamCityVcsRootRepair rootRepair, TeamCityBranchResolver branchResolver) {
    this.projects = projects; this.builds = builds; this.collector = collector;
    this.clients = clients; this.rootRepair = rootRepair; this.branchResolver = branchResolver;
  }

  @NotNull public VcsBuildCustomization prepareVcs(@NotNull BuildMirror mirror, JenkinsVcsInfo info)
      throws TeamCityVcsOperationalException {
    VcsSyncResult result = new VcsSyncResult();
    Map<Long, RepositoryVersion> revisions = new LinkedHashMap<>();
    if (info == null || info.repositories().stream()
        .noneMatch(repository -> VcsProvider.fromJenkinsClass(repository.vcsClass()) != null)) {
      return new VcsBuildCustomization(result, revisions);
    }
    try {
      SBuildType type = findBuildType(mirror.getTeamCityBuildTypeId(), projects);
      if (type == null) {
        result.addError("TeamCity build type " + mirror.getTeamCityBuildTypeId() + " was not found");
        return new VcsBuildCustomization(result, revisions);
      }
      Optional<JenkinsMultibranchScmSnapshot> snapshot = Optional.empty();
      Optional<JenkinsScmHeadInfo> primary = Optional.empty();
      if (isBuildConfigMultibranch(type)) {
        try {
          snapshot = clients.getMultibranchScmSnapshot(type);
          var child = snapshot.flatMap(s -> s.child(mirror.getJenkinsJob()));
          primary = child.filter(c -> !c.changeRequest()).map(c -> c.scmInfo());
          if (primary.isEmpty()) primary = clients.forBuildType(type).getBranchScmInfo(mirror.getJenkinsJob());
        } catch (Exception e) { error(result, "SCM metadata unavailable", e); }
      }
      boolean changed = false;
      for (JenkinsVcsRepository repo : info.repositories()) {
        VcsProvider provider = VcsProvider.fromJenkinsClass(repo.vcsClass());
        if (provider == null) continue;
        try {
          String url = normalizeRepositoryUrl(repo.remoteUrl());
          if (url == null) { result.addError("Repository URL could not be parsed"); continue; }
          TeamCityBranch branch = TeamCityBranch.fromJenkinsGit(repo.rawBranchName());
          // Only the matching primary SCM is retyped. Additional checkouts retain their own refs.
          if (primary.isPresent() && primary.get().refType() == VcsRefType.TAGS
              && url.equals(normalizeRepositoryUrl(primary.get().remoteUrl()))) {
            branch = new TeamCityBranch("refs/tags/" + primary.get().headName(), primary.get().headName());
          }
          if (branch.isDefault()) { result.addError("Actual checkout ref unavailable for " + url); continue; }
          VcsRefType refType = branch.ref().startsWith("refs/tags/") ? VcsRefType.TAGS : VcsRefType.HEADS;
          Optional<String> defaultRef = snapshot.flatMap(s -> s.defaultRef(repo.remoteUrl()));
          Optional<SVcsRoot> existing = existingRoot(type, provider, url, refType, branch.ref(), result);
          SVcsRoot root;
          if (existing.isPresent()) {
            root = existing.get();
            if (defaultRef.isPresent() && rootRepair != null) rootRepair.repair(root, url, defaultRef.get(), refType == VcsRefType.TAGS);
          } else {
            if (defaultRef.isEmpty()) {
              result.addError("No unique Jenkins primary branch for " + url + "; VCS root creation deferred");
              continue;
            }
            root = createRoot(type.getProject(), provider, repo, url, defaultRef.get(), refType);
          }
          if (type.getVcsRootInstanceEntryForParent(root) == null) {
            type.addVcsRoot(root);
            type.setCheckoutRules(root, CheckoutRules.DEFAULT);
            changed = true;
          }
          result.incrementAttached();
          if (repo.sha1().isEmpty()) { result.addError("Exact Jenkins commit unavailable for " + url); continue; }
          var decision = branchResolver.resolveRoot(root, branch.ref(), repo.remoteUrl());
          if (decision.outcome() == TeamCityBranchResolver.Outcome.NO_MAPPING) {
            result.addError("No reliable TeamCity VCS mapping for " + branch.ref());
            continue;
          }
          VcsRootInstanceEntry entry = type.getVcsRootInstanceEntryForParent(root);
          if (entry == null) { result.addError("No VCS root instance for " + url); continue; }
          // Even the configured default gets the real full ref and SHA, with an implicit UI label.
          revisions.put(entry.getVcsRoot().getId(), new RepositoryVersion(repo.sha1(), repo.sha1(), branch.ref()));
        } catch (Exception e) { error(result, "Repository VCS preparation failed", e); }
      }
      if (changed) {
        try { type.schedulePersisting("Jenkins Bridge: persist Jenkins VCS root attachment changes").awaitUninterruptibly(); }
        catch (RuntimeException e) { error(result, "VCS attachment persistence failed", e); }
      }
    } catch (RuntimeException e) { error(result, "VCS preparation failed", e); }
    return new VcsBuildCustomization(result, revisions);
  }

  @NotNull public VcsSyncResult applyVcsToBuild(@NotNull BuildMirror mirror, JenkinsVcsInfo info)
      throws TeamCityVcsOperationalException {
    VcsBuildCustomization prepared = prepareVcs(mirror, info);
    VcsSyncResult result = prepared.result();
    // Failed discovery must never erase a previously valid pin.
    if (!prepared.hasRevisions()) return result;
    try {
      BuildPromotion promotion = builds.findPromotion(mirror.getTeamCityBuildId());
      if (!(promotion instanceof BuildPromotionEx ex)) { result.addError("Build promotion unavailable for VCS sync"); return result; }
      TeamCityVcsFailurePolicy.configure(promotion);
      Map<Long, RepositoryVersion> revisions = new LinkedHashMap<>(ex.getProvidedUpperLimitRevisions());
      revisions.putAll(prepared.upperLimitRevisions());
      // Validate/store replacement pins before resetting; preserve pins for repositories that failed.
      ex.setProvidedUpperLimitRevisions(revisions);
      ex.resetBuildRevisions();
      collector.scheduleCheckingForChangesAndWait(ex, new CancelableTaskHolder());
    } catch (RuntimeException e) { error(result, "VCS revision synchronization failed", e); }
    return result;
  }

  private Optional<SVcsRoot> existingRoot(SBuildType type, VcsProvider provider, String url,
                                        VcsRefType refType, String actualRef, VcsSyncResult result) {
    Set<SVcsRoot> candidates = new LinkedHashSet<>(type.getVcsRoots());
    candidates.addAll(type.getProject().getAvailableVcsRoots());
    boolean tags = refType == VcsRefType.TAGS;
    Optional<SVcsRoot> fallback = Optional.empty();
    for (SVcsRoot root : candidates) {
      try {
        if (!provider.teamCityVcsName().equals(root.getVcsName())
            || !url.equals(normalizeRepositoryUrl(root.getProperty(provider.urlPropertyKey())))) continue;
        String spec = nullToEmpty(root.getProperty("teamcity:branchSpec"));
        boolean tagRoot = nullToEmpty(root.getName()).endsWith("/tags")
            || (spec.contains("refs/tags/") && !spec.contains("refs/heads/"));
        if (tagRoot != tags || nullToEmpty(root.getProperty(GitConstants.BRANCH_PROP)).isEmpty()) continue;
        if (fallback.isEmpty()) fallback = Optional.of(root);
        if (branchResolver.resolveRoot(root, actualRef, url).outcome() != TeamCityBranchResolver.Outcome.NO_MAPPING) {
          return Optional.of(root);
        }
      } catch (RuntimeException e) {
        error(result, "VCS root lookup inspection failed", e);
      }
    }
    // Preserve repair/warning behavior when no candidate maps; do not create a root to bypass exclusions.
    return fallback;
  }

  private SVcsRoot createRoot(SProject project, VcsProvider provider, JenkinsVcsRepository repo,
                             String url, String defaultRef, VcsRefType type) {
    SVcsRoot root = project.createVcsRoot(provider.teamCityVcsName(), url + (type == VcsRefType.TAGS ? "/tags" : ""),
          provider.buildRootParameters(repo.remoteUrl(), defaultRef, type));
    root.schedulePersisting("Jenkins Bridge: persist newly created Jenkins VCS root").awaitUninterruptibly();
    return root;
  }

  private static void error(VcsSyncResult result, String context, Exception error) {
    result.addError(context + ": " + describeException(error));
    LOG.warn("Jenkins Bridge: " + context, error);
  }
}
