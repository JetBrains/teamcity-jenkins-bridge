package com.jetbrains.teamcity.jenkinsbridge.util;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class UtilitiesTest {
  
  @Test
  public void normalizesEquivalentUrlShapesToTheSameKey() {
    String expected = "github.com/org/repo";
    assertEquals(expected, Utilities.normalizeRepositoryUrl("https://github.com/org/repo.git"));
    assertEquals(expected, Utilities.normalizeRepositoryUrl("https://github.com/org/repo"));
    assertEquals(expected, Utilities.normalizeRepositoryUrl("http://github.com/org/repo.git/"));
    assertEquals(expected, Utilities.normalizeRepositoryUrl("git@github.com:org/repo.git"));
    assertEquals(expected, Utilities.normalizeRepositoryUrl("ssh://git@github.com/org/repo.git"));
    assertEquals(expected, Utilities.normalizeRepositoryUrl("ssh://git@github.com:22/org/repo.git"));
    assertEquals(expected, Utilities.normalizeRepositoryUrl("git://github.com/org/repo.git"));
    assertEquals(expected, Utilities.normalizeRepositoryUrl("https://GitHub.com/org/repo.git"));
  }

  @Test
  public void keepsDistinctRepositoriesDistinct() {
    assertEquals("github.com/org/repo", Utilities.normalizeRepositoryUrl("git@github.com:org/repo.git"));
    assertEquals("github.com/org/other", Utilities.normalizeRepositoryUrl("git@github.com:org/other.git"));
    assertEquals("gitlab.com/org/repo", Utilities.normalizeRepositoryUrl("https://gitlab.com/org/repo.git"));
  }

  @Test
  public void returnsNullForUnparseableUrls() {
    assertNull(Utilities.normalizeRepositoryUrl(null));
    assertNull(Utilities.normalizeRepositoryUrl(""));
    assertNull(Utilities.normalizeRepositoryUrl("   "));
    assertNull(Utilities.normalizeRepositoryUrl("https://github.com"));
  }
}
