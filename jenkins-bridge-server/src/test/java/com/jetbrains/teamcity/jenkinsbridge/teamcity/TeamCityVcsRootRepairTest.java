package com.jetbrains.teamcity.jenkinsbridge.teamcity;

import com.jetbrains.teamcity.jenkinsbridge.feature.BridgeBuildFeatureConstants;
import com.jetbrains.teamcity.jenkinsbridge.jenkins.JenkinsClientFactory;
import com.jetbrains.teamcity.jenkinsbridge.model.JenkinsMultibranchScmSnapshot;
import com.jetbrains.teamcity.jenkinsbridge.vcs.VcsRefType;
import jetbrains.buildServer.serverSide.*;
import jetbrains.buildServer.vcs.*;
import org.junit.Before;
import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class TeamCityVcsRootRepairTest {
  private final VcsManager vcsManager = mock(VcsManager.class);
  private final JenkinsClientFactory clients = mock(JenkinsClientFactory.class);
  private final TeamCityNodes nodes = mock(TeamCityNodes.class);
  private final TeamCityNode node = mock(TeamCityNode.class);
  private final SVcsRoot root = mock(SVcsRoot.class);
  private final SBuildType user = mock(SBuildType.class);
  private final SProject project = mock(SProject.class);
  private final TeamCityVcsRootRepair repair = new TeamCityVcsRootRepair(vcsManager, clients, nodes);
  @Before public void setup() {
    when(nodes.getCurrentNode()).thenReturn(node); when(node.isMainNode()).thenReturn(true);
    when(root.getName()).thenReturn("github.com/org/repo");
    when(root.getProperties()).thenReturn(Map.of("branch", "refs/heads/deb", "authMethod", "PASSWORD", "teamcity:branchSpec", "+:refs/heads/*"));
    when(root.schedulePersisting(anyString())).thenReturn(mock(PersistTask.class));
    when(root.getUsagesInConfigurations()).thenReturn(List.of(user));
    when(vcsManager.getAllProjectUsages(root)).thenReturn(List.of(project));
    when(vcsManager.getAllTemplateUsages(root)).thenReturn(List.of());
    when(project.getAvailableFeaturesOfType("versionedSettings")).thenReturn(List.of());
    when(user.getProject()).thenReturn(project);
    SBuildFeatureDescriptor feature = mock(SBuildFeatureDescriptor.class);
    when(user.getBuildFeaturesOfType(BridgeBuildFeatureConstants.TYPE)).thenReturn(List.of(feature));
    when(clients.getMultibranchScmSnapshot(user)).thenReturn(Optional.of(snapshot("main")));
  }
  @Test public void bridgeOnlyRootRepairsDefaultAndPreservesAuthenticationAndSpec() {
    assertTrue(repair.repair(root, "github.com/org/repo", "refs/heads/main", false));
    verify(root).setProperties(Map.of("branch", "refs/heads/main", "authMethod", "PASSWORD", "teamcity:branchSpec", "+:refs/heads/*"));
  }
  @Test public void tagReportingIsRepairedAlongsideDefault() {
    when(root.getName()).thenReturn("github.com/org/repo/tags");
    assertTrue(repair.repair(root, "github.com/org/repo", "refs/heads/main", true));
    verify(root).setProperties(argThat(properties -> "true".equals(properties.get("reportTagRevisions"))));
  }
  @Test public void ordinaryBuildUserPreventsRepair() {
    when(user.getBuildFeaturesOfType(BridgeBuildFeatureConstants.TYPE)).thenReturn(List.of()); assertPreserved();
  }
  @Test public void templateUsagePreventsRepair() {
    when(vcsManager.getAllTemplateUsages(root)).thenReturn(List.of(mock(BuildTypeTemplate.class))); assertPreserved();
  }
  @Test public void versionedSettingsUsagePreventsRepair() {
    when(project.getAvailableFeaturesOfType("versionedSettings")).thenReturn(List.of(mock(SProjectFeatureDescriptor.class))); assertPreserved();
  }
  @Test public void conflictingOrMissingDefaultPreventsRepair() {
    when(clients.getMultibranchScmSnapshot(user)).thenReturn(Optional.of(snapshot("deb"))); assertPreserved();
    when(clients.getMultibranchScmSnapshot(user)).thenReturn(Optional.empty()); assertPreserved();
  }
  @Test public void secondaryNodeAndCustomRootNamePreventRepair() {
    when(node.isMainNode()).thenReturn(false); assertPreserved();
    when(node.isMainNode()).thenReturn(true); when(root.getName()).thenReturn("Manually configured"); assertPreserved();
  }
  private void assertPreserved() {
    assertFalse(repair.repair(root, "github.com/org/repo", "refs/heads/main", false));
    verify(root, never()).setProperties(anyMap());
  }
  private JenkinsMultibranchScmSnapshot snapshot(String name) {
    return new JenkinsMultibranchScmSnapshot(List.of(new JenkinsMultibranchScmSnapshot.Child(
        "parent/" + name, name, VcsRefType.HEADS, "https://github.com/org/repo.git", true, false)));
  }
}
