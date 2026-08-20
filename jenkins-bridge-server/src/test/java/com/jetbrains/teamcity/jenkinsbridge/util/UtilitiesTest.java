package com.jetbrains.teamcity.jenkinsbridge.util;

import com.google.gson.JsonObject;
import com.jetbrains.teamcity.jenkinsbridge.feature.BridgeBuildFeatureConstants;
import jetbrains.buildServer.serverSide.ProjectManager;
import jetbrains.buildServer.serverSide.SBuildType;
import org.junit.Test;


import static org.junit.Assert.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class UtilitiesTest {

  @Test
  public void normalizeRepositoryUrlNormalizesEquivalentUrlShapesToTheSameKey() {
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
  public void normalizeRepositoryUrlKeepsDistinctRepositoriesDistinct() {
    assertEquals("github.com/org/repo", Utilities.normalizeRepositoryUrl("git@github.com:org/repo.git"));
    assertEquals("github.com/org/other", Utilities.normalizeRepositoryUrl("git@github.com:org/other.git"));
    assertEquals("gitlab.com/org/repo", Utilities.normalizeRepositoryUrl("https://gitlab.com/org/repo.git"));
  }

  @Test
  public void normalizeRepositoryUrlReturnsNullForUnparseableUrls() {
    assertNull(Utilities.normalizeRepositoryUrl(null));
    assertNull(Utilities.normalizeRepositoryUrl(""));
    assertNull(Utilities.normalizeRepositoryUrl("   "));
    assertNull(Utilities.normalizeRepositoryUrl("https://github.com"));
  }

  @Test
  public void nullToEmptyReturnsEmptyStringForNull() {
    assertEquals("", Utilities.nullToEmpty(null));
  }

  @Test
  @SuppressWarnings("all")
  public void nullToEmptyReturnsOriginalStringForNonNullValue() {
    assertEquals("value", Utilities.nullToEmpty("value"));
  }

  @Test
  public void findBuildTypeReturnsNullForBlankBuildTypeId() {
    ProjectManager projectManager = mock(ProjectManager.class);

    assertNull(Utilities.findBuildType("  ", projectManager));

    verify(projectManager, never()).findBuildTypeByExternalId("  ");
    verify(projectManager, never()).findBuildTypeById("  ");
  }

  @Test
  public void findBuildTypeReturnsBuildTypeFoundByExternalId() {
    ProjectManager projectManager = mock(ProjectManager.class);
    SBuildType buildType = mock(SBuildType.class);
    when(projectManager.findBuildTypeByExternalId("Build_Type")).thenReturn(buildType);

    assertSame(buildType, Utilities.findBuildType("Build_Type", projectManager));

    verify(projectManager, never()).findBuildTypeById("Build_Type");
  }

  @Test
  public void findBuildTypeFallsBackToInternalId() {
    ProjectManager projectManager = mock(ProjectManager.class);
    SBuildType buildType = mock(SBuildType.class);
    when(projectManager.findBuildTypeById("bt1")).thenReturn(buildType);

    assertSame(buildType, Utilities.findBuildType("bt1", projectManager));
  }

  @Test
  public void stringValueReturnsEmptyStringForMissingNullOrNullObjectValues() {
    JsonObject object = new JsonObject();
    object.add("presentNull", null);

    assertEquals("", Utilities.stringValue(null, "key"));
    assertEquals("", Utilities.stringValue(object, "missing"));
    assertEquals("", Utilities.stringValue(object, "presentNull"));
  }

  @Test
  public void stringValueReturnsJsonPrimitiveAsString() {
    JsonObject object = new JsonObject();
    object.addProperty("name", "jenkins");

    assertEquals("jenkins", Utilities.stringValue(object, "name"));
  }

  @Test
  public void lastPathSegmentReturnsBranchNameOfMultibranchJobPath() {
    assertEquals("main", Utilities.lastPathSegment("pipeline/main"));
    assertEquals("main", Utilities.lastPathSegment("team/pipeline/main"));
  }

  @Test
  public void lastPathSegmentReturnsWholeValueWithoutSlashOrTrailingSlash() {
    assertEquals("job", Utilities.lastPathSegment("job"));
    assertEquals("team/pipeline/", Utilities.lastPathSegment("team/pipeline/"));
  }

  @Test
  public void decodeBranchFragmentDecodesPercentEscapes() {
    assertEquals("someone/feature", Utilities.decodeBranchFragment("someone%2Ffeature"));
    assertEquals("release 1.0", Utilities.decodeBranchFragment("release%201.0"));
  }

  @Test
  public void decodeBranchFragmentKeepsNamesWithoutEscapes() {
    assertEquals("main", Utilities.decodeBranchFragment("main"));
    assertEquals("feature+x", Utilities.decodeBranchFragment("feature+x"));
    assertEquals("100%", Utilities.decodeBranchFragment("100%"));
  }

  @Test
  public void describeExceptionIncludesClassNameAndMessage() {
    assertEquals("IllegalArgumentException: bad input",
        Utilities.describeException(new IllegalArgumentException("bad input")));
  }

  @Test
  public void describeExceptionOmitsMessageSeparatorWhenMessageIsNull() {
    assertEquals("RuntimeException", Utilities.describeException(new RuntimeException()));
  }

  @Test
  public void describeThrowableIncludesClassNameAndMessage() {
    assertEquals("AssertionError: failed assertion",
        Utilities.describeException(new AssertionError("failed assertion")));
  }

  @Deprecated
  @Test
  public void mapPullRequestBranchNameReturnsPullNumber() {
    assertEquals("pull/123", Utilities.mapPullRequestBranchName(" PR-123"));
    assertEquals("pull/1", Utilities.mapPullRequestBranchName("MR-1 "));
  }

  @Deprecated
  @Test
  public void mapPullRequestBranchNameLeavesNonPrBranchesTheSame() {
    assertEquals("something", Utilities.mapPullRequestBranchName("something"));
    assertEquals("PR-123a", Utilities.mapPullRequestBranchName("PR-123a"));
    assertEquals("PR-123-headd", Utilities.mapPullRequestBranchName("PR-123-headd"));
  }

  @Deprecated
  @Test
  public void mapPullRequestBranchNamePreservesSuffixes() {
    assertEquals("pull/123", Utilities.mapPullRequestBranchName(" PR-123-head"));
    assertEquals("pull/1", Utilities.mapPullRequestBranchName("MR-1-merge "));
  }

  @Test
  public void looksLikePullOrMergeRequestBranchReturnsTrueForPullAndMergeRequestNames() {
    assertTrue(Utilities.looksLikePullOrMergeRequestBranch("PR-1"));
    assertTrue(Utilities.looksLikePullOrMergeRequestBranch("PR-123-head"));
    assertTrue(Utilities.looksLikePullOrMergeRequestBranch("MR-1-merge"));
    assertTrue(Utilities.looksLikePullOrMergeRequestBranch(" PR-1 "));
  }

  @Test
  public void looksLikePullOrMergeRequestBranchReturnsFalseForRegularBranchNames() {
    assertFalse(Utilities.looksLikePullOrMergeRequestBranch("main"));
    assertFalse(Utilities.looksLikePullOrMergeRequestBranch("PR-123a"));
    assertFalse(Utilities.looksLikePullOrMergeRequestBranch("PR-123-headd"));
  }

  @Test
  public void isBuildConfigMultibranchReturnsFalseWhenTheInternalParameterIsAbsent() {
    SBuildType buildType = mock(SBuildType.class);

    assertFalse(Utilities.isBuildConfigMultibranch(buildType));
  }

  @Test
  public void isBuildConfigMultibranchReturnsFalseWhenTheInternalParameterIsFalse() {
    SBuildType buildType = mock(SBuildType.class);
    when(buildType.getParameterValue(BridgeBuildFeatureConstants.INTERNAL_MULTIBRANCH_PARAM)).thenReturn("false");

    assertFalse(Utilities.isBuildConfigMultibranch(buildType));
  }

  @Test
  public void isBuildConfigMultibranchReturnsTrueWhenTheInternalParameterIsTrue() {
    SBuildType buildType = mock(SBuildType.class);
    when(buildType.getParameterValue(BridgeBuildFeatureConstants.INTERNAL_MULTIBRANCH_PARAM)).thenReturn("true");

    assertTrue(Utilities.isBuildConfigMultibranch(buildType));
  }

  @Test
  public void firstNonBlankStringReturnsFirstNonBlankString() {
    assertEquals("first", Utilities.firstNonBlankString("first", "second"));
    assertEquals("second", Utilities.firstNonBlankString(null, "second"));
    assertEquals("", Utilities.firstNonBlankString());
    assertEquals("", Utilities.firstNonBlankString((String) null));
    assertEquals("", Utilities.firstNonBlankString(null, null));
  }
}
