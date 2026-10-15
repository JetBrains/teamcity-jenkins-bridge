package com.jetbrains.teamcity.jenkinsbridge.integration;

import com.jetbrains.teamcity.jenkinsbridge.feature.BridgeBuildFeatureConstants;
import com.jetbrains.teamcity.jenkinsbridge.jenkins.JenkinsClientFactory;
import com.jetbrains.teamcity.jenkinsbridge.model.JenkinsMultibranchScmSnapshot;
import com.jetbrains.teamcity.jenkinsbridge.teamcity.TeamCityVcsRootRepair;
import com.jetbrains.teamcity.jenkinsbridge.vcs.VcsRefType;
import jetbrains.buildServer.serverSide.*;
import jetbrains.buildServer.vcs.*;
import org.mockito.Mockito;
import org.testng.annotations.Test;

import java.util.*;

/** Real root usage discovery and persistence, including a shared ordinary-build user. */
public class TeamCityVcsRootRepairIT extends TeamCityIntegrationTestBase {
  @Test public void repairsBridgeOnlyRootButPreservesRootSharedWithOrdinaryBuild() throws Exception {
    myFixture.registerVcsSupport("jetbrains.git");
    SVcsRoot root = myFixture.addVcsRoot("jetbrains.git", "", myBuildType);
    root.setName("github.com/org/repo");
    root.setProperties(Map.of("url", "https://github.com/org/repo.git", "branch", "refs/heads/deb",
        "authMethod", "ANONYMOUS", "teamcity:branchSpec", "+:refs/heads/*"));
    myBuildType.addBuildFeature(BridgeBuildFeatureConstants.TYPE, Map.of("jenkinsJob", "parent", "connectionId", "conn"));
    var clients = Mockito.mock(JenkinsClientFactory.class);
    Mockito.when(clients.getMultibranchScmSnapshot(myBuildType)).thenReturn(Optional.of(new JenkinsMultibranchScmSnapshot(List.of(
        new JenkinsMultibranchScmSnapshot.Child("parent/main", "main", VcsRefType.HEADS, "https://github.com/org/repo.git", true, false)))));
    var nodes = Mockito.mock(TeamCityNodes.class); var node = Mockito.mock(TeamCityNode.class);
    Mockito.when(nodes.getCurrentNode()).thenReturn(node); Mockito.when(node.isMainNode()).thenReturn(true);
    var repair = new TeamCityVcsRootRepair(myFixture.getSingletonService(VcsManager.class), clients, nodes);
    assertTrue(repair.repair(root, "github.com/org/repo", "refs/heads/main", false));
    assertEquals("refs/heads/main", root.getProperty("branch"));
    assertEquals("ANONYMOUS", root.getProperty("authMethod"));
    assertEquals("+:refs/heads/*", root.getProperty("teamcity:branchSpec"));

    SBuildType ordinary = root.getProject().createBuildType("Ordinary");
    ordinary.addVcsRoot(root);
    assertFalse(repair.repair(root, "github.com/org/repo", "refs/heads/deb", false));
    assertEquals("refs/heads/main", root.getProperty("branch"));
  }
}
