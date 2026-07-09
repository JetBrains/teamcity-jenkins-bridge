package com.jetbrains.teamcity.jenkinsbridge.vcs;

import com.jetbrains.teamcity.jenkinsbridge.teamcity.TeamCityBranch;
import org.junit.Test;

import static org.junit.Assert.*;

public class VcsNormalizationTest {

  private final VcsNormalizer sut = new VcsNormalizer();

  @Test
  public void normalizesEquivalentUrlShapesToTheSameKey() {
    String expected = "github.com/org/repo";
    assertEquals(expected, sut.normalizeRepoUrl("https://github.com/org/repo.git"));
    assertEquals(expected, sut.normalizeRepoUrl("https://github.com/org/repo"));
    assertEquals(expected, sut.normalizeRepoUrl("http://github.com/org/repo.git/"));
    assertEquals(expected, sut.normalizeRepoUrl("git@github.com:org/repo.git"));
    assertEquals(expected, sut.normalizeRepoUrl("ssh://git@github.com/org/repo.git"));
    assertEquals(expected, sut.normalizeRepoUrl("ssh://git@github.com:22/org/repo.git"));
    assertEquals(expected, sut.normalizeRepoUrl("git://github.com/org/repo.git"));
    assertEquals(expected, sut.normalizeRepoUrl("https://GitHub.com/org/repo.git"));
  }

  @Test
  public void keepsDistinctRepositoriesDistinct() {
    assertEquals("github.com/org/repo", sut.normalizeRepoUrl("git@github.com:org/repo.git"));
    assertEquals("github.com/org/other", sut.normalizeRepoUrl("git@github.com:org/other.git"));
    assertEquals("gitlab.com/org/repo", sut.normalizeRepoUrl("https://gitlab.com/org/repo.git"));
  }

  @Test
  public void returnsNullForUnparseableUrls() {
    assertNull(sut.normalizeRepoUrl(null));
    assertNull(sut.normalizeRepoUrl(""));
    assertNull(sut.normalizeRepoUrl("   "));
    assertNull(sut.normalizeRepoUrl("https://github.com"));
  }

  @Test
  public void translatesRemoteTrackingBranch() {
    TeamCityBranch branch = sut.toTeamCityBranch("refs/remotes/origin/main");
    assertEquals("refs/heads/main", branch.ref());
    assertEquals("main", branch.displayName());
    assertFalse(branch.isDefault());
  }

  @Test
  public void translatesRemoteTrackingBranchWithSlashes() {
    TeamCityBranch branch = sut.toTeamCityBranch("refs/remotes/origin/feature/foo");
    assertEquals("refs/heads/feature/foo", branch.ref());
    assertEquals("feature/foo", branch.displayName());
  }

  @Test
  public void translatesOriginPrefixedAndPlainAndHeadsBranches() {
    assertEquals("refs/heads/main", sut.toTeamCityBranch("origin/main").ref());
    assertEquals("main", sut.toTeamCityBranch("origin/main").displayName());

    assertEquals("refs/heads/develop", sut.toTeamCityBranch("develop").ref());
    assertEquals("develop", sut.toTeamCityBranch("develop").displayName());

    TeamCityBranch heads = sut.toTeamCityBranch("refs/heads/release/1.0");
    assertEquals("refs/heads/release/1.0", heads.ref());
    assertEquals("release/1.0", heads.displayName());
  }

  @Test
  public void keepsTagRefAndUsesLastSegmentForDisplay() {
    TeamCityBranch tag = sut.toTeamCityBranch("refs/tags/v1.2.3");
    assertEquals("refs/tags/v1.2.3", tag.ref());
    assertEquals("v1.2.3", tag.displayName());
  }

  @Test
  public void nullOrEmptyBranchIsDefault() {
    assertTrue(sut.toTeamCityBranch(null).isDefault());
    assertTrue(sut.toTeamCityBranch("").isDefault());
    assertTrue(sut.toTeamCityBranch("   ").isDefault());
    assertEquals("", sut.toTeamCityBranch(null).displayName());
  }
}
