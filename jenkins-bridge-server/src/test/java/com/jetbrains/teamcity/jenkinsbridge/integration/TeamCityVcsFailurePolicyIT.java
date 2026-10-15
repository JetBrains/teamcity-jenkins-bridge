package com.jetbrains.teamcity.jenkinsbridge.integration;

import com.jetbrains.teamcity.jenkinsbridge.feature.*;
import com.jetbrains.teamcity.jenkinsbridge.jenkins.JenkinsClientFactory;
import com.jetbrains.teamcity.jenkinsbridge.teamcity.*;
import jetbrains.buildServer.serverSide.*;
import jetbrains.buildServer.vcs.*;
import jetbrains.buildServer.vcs.impl.RepositoryStateManager;
import jetbrains.buildServer.vcs.spec.BranchSpecs;
import org.mockito.Mockito;
import org.testng.annotations.Test;

import java.util.*;

/** Proves the per-promotion policy reaches TeamCity's actual revision/branch validation. */
public class TeamCityVcsFailurePolicyIT extends TeamCityIntegrationTestBase {
  @Test public void invalidBranchIsWarningOnlyForMirrorAndStillFailsOrdinaryPromotion() throws Exception {
    myFixture.registerVcsSupport("jetbrains.git").setDAGBased(true);
    SVcsRoot root = myFixture.addVcsRoot("jetbrains.git", "", myBuildType);
    root.setProperties(Map.of("url", "https://github.com/org/repo.git", "branch", "refs/heads/main", "teamcity:branchSpec", "+:refs/heads/*"));
    VcsRootInstanceEntry entry = myBuildType.getVcsRootInstanceEntryForParent(root);
    myFixture.getSingletonService(RepositoryStateManager.class).setRepositoryState(entry.getVcsRoot(),
        RepositoryStateFactory.createRepositoryState(Map.of("refs/heads/main", "commit1"), "refs/heads/main"));

    BuildPromotionEx ordinary = myBuildType.createBuildPromotion();
    ordinary.setDesiredBranchName("deb");
    ordinary.setBuildRevisions(List.of(new BuildRevisionEx(entry, new RepositoryVersion("commit1", "commit1", "refs/heads/main"))), -1L, -1L);
    assertTrue(ordinary.hasBuildProblemsOfType("invalid_branch_name"));

    myBuildType.addBuildFeature(BridgeBuildFeatureConstants.TYPE, Map.of("jenkinsJob", "parent", "connectionId", "conn"));
    BuildPromotionEx mirror = myBuildType.createBuildPromotion();
    mirror.setCustomParameters(Map.of("jenkins.build.key", "parent/deb#1"));
    mirror.setDesiredBranchName("deb");
    // Exercise the Jenkins-first early-return path: warning policy must be applied before it.
    var preprocessor = new JenkinsQueueAgentlessPreprocessor(Mockito.mock(JenkinsClientFactory.class),
        new TeamCityBranchResolver(myFixture.getSingletonService(BranchSpecs.class)));
    Map<BuildPromotion, jetbrains.buildServer.AgentRestrictor> promotions = new LinkedHashMap<>();
    promotions.put(mirror, null); preprocessor.preprocess(promotions, "Jenkins Bridge");
    assertEquals(TeamCityVcsFailurePolicy.WARNING_ONLY, mirror.getParameterValue(TeamCityVcsFailurePolicy.INVALID_BRANCH_REPORTING));
    mirror.setBuildRevisions(List.of(new BuildRevisionEx(entry, new RepositoryVersion("commit1", "commit1", "refs/heads/main"))), -1L, -1L);
    assertFalse(mirror.hasBuildProblemsOfType("invalid_branch_name"));
  }

  @Test public void actualDefaultHeadBesideTagRootStaysImplicitInTeamCityFirstQueue() throws Exception {
    myFixture.registerVcsSupport("jetbrains.git").setDAGBased(true);
    String url = "https://github.com/org/repo.git";
    SVcsRoot head = myFixture.addVcsRoot("jetbrains.git", "", myBuildType);
    head.setProperties(Map.of("url", url, "branch", "refs/heads/deb", "teamcity:branchSpec", "+:refs/heads/*"));
    SVcsRoot tag = myFixture.addVcsRoot("jetbrains.git", "", myBuildType);
    tag.setProperties(Map.of("url", url, "branch", "refs/heads/main", "teamcity:branchSpec", "+:refs/tags/*", "reportTagRevisions", "true"));
    myBuildType.addBuildFeature(BridgeBuildFeatureConstants.TYPE, Map.of("jenkinsJob", "parent", "connectionId", "conn"));
    myBuildType.addConfigParameter(myFixture.getSingletonService(jetbrains.buildServer.serverSide.parameters.ParameterFactory.class)
        .createSimpleParameter(BridgeBuildFeatureConstants.INTERNAL_MULTIBRANCH_PARAM, "true"));
    JenkinsClientFactory clients = Mockito.mock(JenkinsClientFactory.class);
    Mockito.when(clients.getMultibranchScmSnapshot(myBuildType)).thenReturn(Optional.of(new com.jetbrains.teamcity.jenkinsbridge.model.JenkinsMultibranchScmSnapshot(
        List.of(new com.jetbrains.teamcity.jenkinsbridge.model.JenkinsMultibranchScmSnapshot.Child("parent/deb", "deb",
            com.jetbrains.teamcity.jenkinsbridge.vcs.VcsRefType.HEADS, url, false, false)))));
    BuildPromotionEx promotion = myBuildType.createBuildPromotion();
    promotion.setCustomParameters(Map.of("jenkins.branch", "parent/deb"));
    promotion.setDesiredBranchName("deb");
    Map<BuildPromotion, jetbrains.buildServer.AgentRestrictor> promotions = new LinkedHashMap<>(); promotions.put(promotion, null);
    new JenkinsQueueAgentlessPreprocessor(clients, new TeamCityBranchResolver(myFixture.getSingletonService(BranchSpecs.class)))
        .preprocess(promotions, "user");
    assertTrue(promotion.getBranch() == null || promotion.getBranch().isDefaultBranch());
    assertEquals(TeamCityVcsFailurePolicy.WARNING_ONLY, promotion.getParameterValue(TeamCityVcsFailurePolicy.INVALID_BRANCH_REPORTING));
  }
}
