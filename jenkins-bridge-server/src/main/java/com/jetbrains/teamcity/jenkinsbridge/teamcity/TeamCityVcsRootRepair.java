package com.jetbrains.teamcity.jenkinsbridge.teamcity;

import com.jetbrains.teamcity.jenkinsbridge.vcs.constants.GitConstants;
import com.jetbrains.teamcity.jenkinsbridge.jenkins.JenkinsClientFactory;
import jetbrains.buildServer.serverSide.SBuildType;
import jetbrains.buildServer.serverSide.TeamCityNodes;
import jetbrains.buildServer.vcs.SVcsRoot;
import jetbrains.buildServer.vcs.VcsManager;
import org.jetbrains.annotations.NotNull;

import java.util.*;

/** Conservative public-API usage audit before changing a generated, shared root. */
public class TeamCityVcsRootRepair {
  private final VcsManager vcsManager;
  private final JenkinsClientFactory clients;
  private final TeamCityNodes nodes;
  public TeamCityVcsRootRepair(@NotNull VcsManager vcsManager, @NotNull JenkinsClientFactory clients,
                              @NotNull TeamCityNodes nodes) {
    this.vcsManager = vcsManager; this.clients = clients; this.nodes = nodes;
  }

  public boolean repair(@NotNull SVcsRoot root, @NotNull String normalizedUrl,
                        @NotNull String defaultRef, boolean tags) {
    if (nodes.getCurrentNode() == null || !nodes.getCurrentNode().isMainNode()) return false;
    if (!(normalizedUrl + (tags ? "/tags" : "")).equals(root.getName())) return false;
    var templates = vcsManager.getAllTemplateUsages(root);
    var projects = vcsManager.getAllProjectUsages(root);
    List<SBuildType> users = root.getUsagesInConfigurations();
    if (templates == null || !templates.isEmpty() || projects == null || users == null || users.isEmpty()) return false;
    // Project usage includes versioned settings. Preserve roots whenever that usage is uncertain,
    // including inherited versioned settings, even if they happen to share a Bridge configuration.
    for (var project : projects) {
      if (project.getAvailableFeaturesOfType("versionedSettings") == null
          || !project.getAvailableFeaturesOfType("versionedSettings").isEmpty()) return false;
      if (users.stream().noneMatch(user -> user.getProject().equals(project))) return false;
    }
    for (SBuildType user : users) {
      if (TeamCityBridgeFeatureLookup.findBridgeFeature(user) == null) return false;
      var discovered = clients.getMultibranchScmSnapshot(user).flatMap(s -> s.defaultRef(normalizedUrl));
      if (discovered.isEmpty() || !defaultRef.equals(discovered.get())) return false;
    }
    Map<String, String> properties = new LinkedHashMap<>(root.getProperties());
    boolean changed = !defaultRef.equals(properties.put(GitConstants.BRANCH_PROP, defaultRef));
    if (tags) changed |= !"true".equals(properties.put(GitConstants.REPORT_TAG_REVISIONS_PROP, "true"));
    if (changed) {
      root.setProperties(properties);
      root.schedulePersisting("Jenkins Bridge: repair discovered repository default and tag reporting").awaitUninterruptibly();
    }
    return true;
  }
}
