package com.jetbrains.teamcity.jenkinsbridge.teamcity;

import org.junit.Test;

import static org.junit.Assert.*;
import static org.junit.Assert.assertTrue;

public class TeamCityBranchTest {
  
  @Test
  public void translatesRemoteTrackingBranch() {
    TeamCityBranch branch = TeamCityBranch.fromJenkinsGit("refs/remotes/origin/main");
    assertEquals("refs/heads/main", branch.ref());
    assertEquals("main", branch.displayName());
    assertFalse(branch.isDefault());
  }

  @Test
  public void translatesRemoteTrackingBranchWithSlashes() {
    TeamCityBranch branch = TeamCityBranch.fromJenkinsGit("refs/remotes/origin/feature/foo");
    assertEquals("refs/heads/feature/foo", branch.ref());
    assertEquals("feature/foo", branch.displayName());
  }

  @Test
  public void translatesOriginPrefixedAndPlainAndHeadsBranches() {
    assertEquals("refs/heads/main", TeamCityBranch.fromJenkinsGit("origin/main").ref());
    assertEquals("main", TeamCityBranch.fromJenkinsGit("origin/main").displayName());

    assertEquals("refs/heads/develop", TeamCityBranch.fromJenkinsGit("develop").ref());
    assertEquals("develop", TeamCityBranch.fromJenkinsGit("develop").displayName());

    TeamCityBranch heads = TeamCityBranch.fromJenkinsGit("refs/heads/release/1.0");
    assertEquals("refs/heads/release/1.0", heads.ref());
    assertEquals("release/1.0", heads.displayName());
  }

  @Test
  public void keepsTagRefAndUsesLastSegmentForDisplay() {
    TeamCityBranch tag = TeamCityBranch.fromJenkinsGit("refs/tags/v1.2.3");
    assertEquals("refs/tags/v1.2.3", tag.ref());
    assertEquals("v1.2.3", tag.displayName());
  }

  @Test
  public void nullOrEmptyBranchIsDefault() {
    assertTrue(TeamCityBranch.fromJenkinsGit(null).isDefault());
    assertTrue(TeamCityBranch.fromJenkinsGit("").isDefault());
    assertTrue(TeamCityBranch.fromJenkinsGit("   ").isDefault());
    assertEquals("", TeamCityBranch.fromJenkinsGit(null).displayName());
  }
}
