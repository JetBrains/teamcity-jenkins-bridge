package com.jetbrains.teamcity.jenkinsbridge.teamcity;

import com.intellij.openapi.diagnostic.Logger;
import com.jetbrains.teamcity.jenkinsbridge.jenkins.JenkinsClientFactory;
import com.jetbrains.teamcity.jenkinsbridge.model.JenkinsVcsInfo;
import com.jetbrains.teamcity.jenkinsbridge.model.JenkinsVcsRepository;
import com.jetbrains.teamcity.jenkinsbridge.persistence.BuildMirror;
import com.jetbrains.teamcity.jenkinsbridge.vcs.VcsProvider;
import com.jetbrains.teamcity.jenkinsbridge.vcs.VcsBuildCustomization;
import com.jetbrains.teamcity.jenkinsbridge.vcs.VcsRefType;
import com.jetbrains.teamcity.jenkinsbridge.vcs.VcsSyncResult;
import jetbrains.buildServer.serverSide.BuildPromotion;
import jetbrains.buildServer.serverSide.BuildPromotionEx;
import jetbrains.buildServer.serverSide.InvalidVcsRootScopeException;
import jetbrains.buildServer.serverSide.PersistFailedException;
import jetbrains.buildServer.serverSide.ProjectNotFoundException;
import jetbrains.buildServer.serverSide.ProjectManager;
import jetbrains.buildServer.serverSide.ReadOnlyEntityException;
import jetbrains.buildServer.serverSide.RepositoryVersion;
import jetbrains.buildServer.serverSide.SBuildType;
import jetbrains.buildServer.serverSide.SProject;
import jetbrains.buildServer.serverSide.impl.CancelableTaskHolder;
import jetbrains.buildServer.vcs.CheckoutRules;
import jetbrains.buildServer.vcs.DuplicateVcsRootNameException;
import jetbrains.buildServer.vcs.SVcsRoot;
import jetbrains.buildServer.vcs.VcsRootInstanceEntry;
import jetbrains.buildServer.vcs.VcsRootNotFoundException;
import jetbrains.buildServer.vcs.UnknownVcsException;
import jetbrains.buildServer.vcs.impl.BuildChainChangesCollector;
import org.jetbrains.annotations.NotNull;

import java.util.*;

import static com.jetbrains.teamcity.jenkinsbridge.util.Utilities.*;

/**
 * Mirrors a Jenkins build's VCS information into TeamCity.
 * <p>
 * A repository for which a root already exists reuses that root, and
 * a root already attached to the build configuration is not re-attached.
 */
public class TeamCityVcsPublisher {
    private static final Logger LOG = Logger.getInstance(TeamCityVcsPublisher.class.getName());

    private final ProjectManager myProjectManager;
    private final TeamCityRunningBuildLocator myBuildLocator;
    private final BuildChainChangesCollector myChangesCollector;
    private final JenkinsClientFactory myJenkinsClientFactory;

    public TeamCityVcsPublisher(ProjectManager projectManager, TeamCityRunningBuildLocator buildLocator,
                                 BuildChainChangesCollector changesCollector,
                                 JenkinsClientFactory jenkinsClientFactory) {
        myProjectManager = projectManager;
        myBuildLocator = buildLocator;
        myChangesCollector = changesCollector;
        myJenkinsClientFactory = jenkinsClientFactory;
    }

    public VcsSyncResult applyVcsToBuild(BuildMirror mirror, JenkinsVcsInfo vcsInfo)
            throws TeamCityVcsOperationalException {
        return applyVcsToBuildInternal(mirror, vcsInfo);
    }

    /**
     * Creates or attaches the VCS roots and calculates the exact Jenkins revision before a
     * TeamCity promotion is queued. TeamCity needs this information during revision calculation;
     * applying it only after the build starts is too late for a first-time tag mirror.
     */
    public VcsBuildCustomization prepareVcs(BuildMirror mirror, JenkinsVcsInfo vcsInfo)
            throws TeamCityVcsOperationalException {
        VcsSyncResult result = new VcsSyncResult();
        if (vcsInfo == null || vcsInfo.repositories().isEmpty()) {
            return new VcsBuildCustomization(result, null, null);
        }

        SBuildType buildType = findBuildType(mirror.getTeamCityBuildTypeId(), myProjectManager);
        if (buildType == null) {
            result.addError("TeamCity build type " + mirror.getTeamCityBuildTypeId() + " was not found");
            return new VcsBuildCustomization(result, null, null);
        }
        if (!hasSupportedRepository(vcsInfo)) {
            return new VcsBuildCustomization(result, null, null);
        }

        VcsRefType refType = myJenkinsClientFactory.forBuildType(buildType)
                .getBranchRefType(mirror.getJenkinsJob());
        List<AttachedRepository> attached = ensureVcsRootsAttached(
                buildType.getProject(), buildType, vcsInfo, result, refType);
        return buildCustomization(buildType, attached, result);
    }

    private VcsSyncResult applyVcsToBuildInternal(BuildMirror mirror, JenkinsVcsInfo vcsInfo)
            throws TeamCityVcsOperationalException {
        VcsSyncResult result = new VcsSyncResult();
        if (vcsInfo == null || vcsInfo.repositories().isEmpty()) {
            return result;
        }

        SBuildType buildType = findBuildType(mirror.getTeamCityBuildTypeId(), myProjectManager);
        if (buildType == null) {
            result.addError("TeamCity build type " + mirror.getTeamCityBuildTypeId() + " was not found");
            return result;
        }
        SProject project = buildType.getProject();

        if (!hasSupportedRepository(vcsInfo)) {
            return result;
        }
        VcsRefType refType = myJenkinsClientFactory.forBuildType(buildType).getBranchRefType(mirror.getJenkinsJob());
        List<AttachedRepository> attached = ensureVcsRootsAttached(project, buildType, vcsInfo, result, refType);

        Map<Long, RepositoryVersion> revisions = new LinkedHashMap<>();
        for (AttachedRepository repo : attached) {
            VcsRootInstanceEntry entry = buildType.getVcsRootInstanceEntryForParent(repo.root);
            if (entry == null) {
                result.addError("No VCS root instance resolved for " + repo.repository.remoteUrl());
                continue;
            }
            String branchRef = repo.branch.isDefault() ? null : repo.branch.ref();
            RepositoryVersion version = new RepositoryVersion(
                    repo.repository.sha1(),
                    repo.repository.sha1(),
                    branchRef);
            revisions.put(entry.getVcsRoot().getId(), version);
        }

        BuildPromotion promotion = myBuildLocator.findPromotion(mirror.getTeamCityBuildId());
        if (!(promotion instanceof BuildPromotionEx promotionEx)) {
            result.addError("TeamCity build " + mirror.getTeamCityBuildId()
                    + " is not an instance of BuildPromotionEx");
            return result;
        }

        promotionEx.resetBuildRevisions();
        promotionEx.setProvidedUpperLimitRevisions(revisions);
        myChangesCollector.scheduleCheckingForChangesAndWait(promotionEx, new CancelableTaskHolder());

        return result;
    }

    private static boolean hasSupportedRepository(JenkinsVcsInfo vcsInfo) {
        for (JenkinsVcsRepository repository : vcsInfo.repositories()) {
            if (VcsProvider.fromJenkinsClass(repository.vcsClass()) != null) {
                return true;
            }
        }
        return false;
    }

    private VcsBuildCustomization buildCustomization(
            SBuildType buildType,
            List<AttachedRepository> attached,
            VcsSyncResult result
    ) {
        Map<Long, RepositoryVersion> revisions = new LinkedHashMap<>();
        String desiredBranch = null;
        for (AttachedRepository repo : attached) {
            VcsRootInstanceEntry entry = buildType.getVcsRootInstanceEntryForParent(repo.root);
            if (entry == null) {
                result.addError("No VCS root instance resolved for " + repo.repository.remoteUrl());
                continue;
            }
            String branchRef = repo.branch.isDefault() ? null : repo.branch.ref();
            if (!repo.repository.sha1().isEmpty()) {
                revisions.put(entry.getVcsRoot().getId(), new RepositoryVersion(
                        repo.repository.sha1(), repo.repository.sha1(), branchRef));
            }
            if (desiredBranch == null && !repo.branch.isDefault()) {
                desiredBranch = repo.branch.displayName();
            }
        }
        return new VcsBuildCustomization(result, revisions, desiredBranch);
    }

    /**
     * For each repository, find or create the matching VCS root on the project and attach it to the
     * build configuration. Idempotent.
     */
    private List<AttachedRepository> ensureVcsRootsAttached(
            SProject project,
            SBuildType buildType,
            JenkinsVcsInfo vcsInfo,
            VcsSyncResult result,
            VcsRefType refType
    ) throws TeamCityVcsOperationalException {
        List<AttachedRepository> attached = new ArrayList<>();
        boolean buildTypeChanged = false;

        for (JenkinsVcsRepository repo : vcsInfo.repositories()) {
            VcsProvider provider = VcsProvider.fromJenkinsClass(repo.vcsClass());
            if (provider == null) {
                // Unimplemented VCS type
                continue;
            }
            String normalized = normalizeRepositoryUrl(repo.remoteUrl());
            if (normalized == null) {
                result.addError("Could not parse repository URL: " + repo.remoteUrl());
                continue;
            }
            TeamCityBranch branch = TeamCityBranch.fromJenkinsGit(repo.rawBranchName());
            if (refType == VcsRefType.TAGS) {
                branch = branch.asTag();
            }

            try {
                SVcsRoot root = findOrCreateRoot(project, provider, repo, normalized, branch, refType);
                if (buildType.getVcsRootInstanceEntryForParent(root) == null) {
                    attachVcsRoot(buildType, root);
                    buildTypeChanged = true;
                }
                attached.add(new AttachedRepository(repo, root, branch));
                result.incrementAttached();
            } catch (TeamCityVcsOperationalException e) {
                // VCS is explicitly best-effort; unexpected runtime failures, including NPEs,
                // must reach the per-build isolation boundary unchanged.
                result.addError(repo.remoteUrl() + ": " + describeException(e.getCause()));
                LOG.warn("Jenkins Bridge: failed to attach VCS root for " + repo.remoteUrl(), e);
            }
        }

        if (buildTypeChanged) {
            persistBuildType(buildType);
        }
        return attached;
    }

    private void persistBuildType(SBuildType buildType) throws TeamCityVcsOperationalException {
        try {
            buildType.schedulePersisting("Jenkins Bridge: persist Jenkins VCS root attachment changes")
                    .awaitUninterruptibly();
        } catch (PersistFailedException e) {
            throw new TeamCityVcsOperationalException(e);
        }
    }

    private void attachVcsRoot(SBuildType buildType, SVcsRoot root)
            throws TeamCityVcsOperationalException {
        try {
            buildType.addVcsRoot(root);
            buildType.setCheckoutRules(root, CheckoutRules.DEFAULT);
        } catch (ReadOnlyEntityException | InvalidVcsRootScopeException
                 | VcsRootNotFoundException e) {
            throw new TeamCityVcsOperationalException(e);
        }
    }

    private SVcsRoot findOrCreateRoot(
            SProject project,
            VcsProvider provider,
            JenkinsVcsRepository repo,
            String normalizedUrl,
            TeamCityBranch branch,
            VcsRefType refType
    ) throws TeamCityVcsOperationalException {
        return findOrCreateRootInternal(project, provider, repo, normalizedUrl, branch, refType);
    }

    private SVcsRoot findOrCreateRootInternal(
            SProject project,
            VcsProvider provider,
            JenkinsVcsRepository repo,
            String normalizedUrl,
            TeamCityBranch branch,
            VcsRefType refType
    ) throws TeamCityVcsOperationalException {
        try {
            Optional<SVcsRoot> existing = findExistingRoot(project, provider, normalizedUrl, refType);
            if (existing.isPresent()) {
                return existing.get();
            }

            String rootName = refType == VcsRefType.TAGS ? normalizedUrl + "/tags" : normalizedUrl;
            try {
                SVcsRoot created = project.createVcsRoot(
                        provider.teamCityVcsName(),
                        rootName,
                        provider.buildRootParameters(repo.remoteUrl(), branch.ref(), refType));
                created.schedulePersisting("Jenkins Bridge: persist newly created Jenkins VCS root")
                        .awaitUninterruptibly();
                return created;
            } catch (DuplicateVcsRootNameException duplicate) {
                Optional<SVcsRoot> found = findExistingRoot(project, provider, normalizedUrl, refType);
                if (found.isPresent()) {
                    return found.get();
                }
                throw duplicate;
            }
        } catch (ReadOnlyEntityException | ProjectNotFoundException
                 | UnknownVcsException | DuplicateVcsRootNameException e) {
            throw new TeamCityVcsOperationalException(e);
        }
    }

    /**
     * Finds an existing root with the same identity (normalized URL and ref type). Tags have separate VCS roots.
     */
    @NotNull
    private Optional<SVcsRoot> findExistingRoot(SProject project, VcsProvider provider, String normalized,
                                                 VcsRefType refType) {
        boolean wantsTagsRoot = refType == VcsRefType.TAGS;
        for (SVcsRoot root : project.getVcsRoots()) {
            if (!provider.teamCityVcsName().equals(root.getVcsName())) {
                continue;
            }
            String urlProperty = root.getProperty(provider.urlPropertyKey());
            if (urlProperty == null) {
                continue;
            }
            if (!normalized.equals(normalizeRepositoryUrl(urlProperty))) {
                continue;
            }
            if (nullToEmpty(root.getName()).endsWith("/tags") == wantsTagsRoot) {
                return Optional.of(root);
            }
        }
        return Optional.empty();
    }

    private record AttachedRepository(JenkinsVcsRepository repository, SVcsRoot root,
                                      TeamCityBranch branch) {
    }
}
