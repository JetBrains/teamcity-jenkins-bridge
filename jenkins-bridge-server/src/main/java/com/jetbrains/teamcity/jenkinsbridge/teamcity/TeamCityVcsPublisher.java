package com.jetbrains.teamcity.jenkinsbridge.teamcity;

import com.intellij.openapi.diagnostic.Logger;
import com.jetbrains.teamcity.jenkinsbridge.model.JenkinsVcsInfo;
import com.jetbrains.teamcity.jenkinsbridge.model.JenkinsVcsRepository;
import com.jetbrains.teamcity.jenkinsbridge.persistence.BuildMirror;
import com.jetbrains.teamcity.jenkinsbridge.vcs.VcsBuildCustomization;
import com.jetbrains.teamcity.jenkinsbridge.vcs.VcsProvider;
import com.jetbrains.teamcity.jenkinsbridge.vcs.VcsSyncResult;
import jetbrains.buildServer.serverSide.ProjectManager;
import jetbrains.buildServer.serverSide.RepositoryVersion;
import jetbrains.buildServer.serverSide.SBuildType;
import jetbrains.buildServer.serverSide.SProject;
import jetbrains.buildServer.vcs.CheckoutRules;
import jetbrains.buildServer.vcs.DuplicateVcsRootNameException;
import jetbrains.buildServer.vcs.SVcsRoot;
import jetbrains.buildServer.vcs.VcsRootInstanceEntry;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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

    public TeamCityVcsPublisher(ProjectManager projectManager) {
        myProjectManager = projectManager;
    }

    public VcsBuildCustomization prepareVcs(BuildMirror mirror, JenkinsVcsInfo vcsInfo) {
        VcsSyncResult result = new VcsSyncResult();
        if (vcsInfo == null || vcsInfo.repositories().isEmpty()) {
            return new VcsBuildCustomization(result, null, null);
        }

        SBuildType buildType = findBuildType(mirror.getTeamCityBuildTypeId(), myProjectManager);
        if (buildType == null) {
            result.addError("TeamCity build type " + mirror.getTeamCityBuildTypeId() + " was not found");
            return new VcsBuildCustomization(result, null, null);
        }
        SProject project = buildType.getProject();

        List<AttachedRepository> attached = ensureVcsRootsAttached(project, buildType, vcsInfo, result);
        return buildCustomization(buildType, attached, result);
    }

    /**
     * For each repository, find or create the matching VCS root on the project and attach it to the
     * build configuration. Idempotent.
     */
    private List<AttachedRepository> ensureVcsRootsAttached(
            SProject project,
            SBuildType buildType,
            JenkinsVcsInfo vcsInfo,
            VcsSyncResult result
    ) {
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

            try {
                SVcsRoot root = findOrCreateRoot(project, provider, repo, normalized, branch);
                if (buildType.getVcsRootInstanceEntryForParent(root) == null) {
                    buildType.addVcsRoot(root);
                    buildType.setCheckoutRules(root, CheckoutRules.DEFAULT);
                    buildTypeChanged = true;
                }
                attached.add(new AttachedRepository(repo, root, branch));
                result.incrementAttached();
            } catch (Exception e) {
                result.addError(repo.remoteUrl() + ": " + describeException(e));
                LOG.warn("Jenkins Bridge: failed to attach VCS root for " + repo.remoteUrl(), e);
            }
        }

        if (buildTypeChanged) {
            buildType.persist();
        }
        return attached;
    }

    private SVcsRoot findOrCreateRoot(
            SProject project,
            VcsProvider provider,
            JenkinsVcsRepository repo,
            String normalizedUrl,
            TeamCityBranch branch
    ) {
        Optional<SVcsRoot> existing = findExistingRoot(project, provider, normalizedUrl);
        if (existing.isPresent()) {
            return existing.get();
        }

        try {
            SVcsRoot created = project.createVcsRoot(
                    provider.teamCityVcsName(),
                    normalizedUrl,
                    provider.buildRootParameters(repo.remoteUrl(), branch.ref()));
            created.persist();
            return created;
        } catch (DuplicateVcsRootNameException duplicate) {
            Optional<SVcsRoot> found = findExistingRoot(project, provider, normalizedUrl);
            if (found.isPresent()) {
                return found.get();
            }
            throw duplicate;
        }
    }

    /**
     * Finds an existing root with the same identity (normalized URL)
     */
    @NotNull
    private Optional<SVcsRoot> findExistingRoot(SProject project, VcsProvider provider, String normalized) {
        for (SVcsRoot root : project.getVcsRoots()) {
            if (!provider.teamCityVcsName().equals(root.getVcsName())) {
                continue;
            }
            String urlProperty = root.getProperty(provider.urlPropertyKey());
            if (urlProperty == null) {
                continue;
            }
            if (normalized.equals(normalizeRepositoryUrl(urlProperty))) {
                return Optional.of(root);
            }
        }
        return Optional.empty();
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
            RepositoryVersion version = new RepositoryVersion(
                    repo.repository.sha1(),
                    repo.repository.sha1(),
                    branchRef);
            revisions.put(entry.getVcsRoot().getId(), version);

            if (desiredBranch == null && !repo.branch.isDefault()) {
                desiredBranch = repo.branch.displayName();
            }
        }

        return new VcsBuildCustomization(result, revisions, desiredBranch);
    }

    private record AttachedRepository(JenkinsVcsRepository repository, SVcsRoot root,
                                      TeamCityBranch branch) {
    }
}
