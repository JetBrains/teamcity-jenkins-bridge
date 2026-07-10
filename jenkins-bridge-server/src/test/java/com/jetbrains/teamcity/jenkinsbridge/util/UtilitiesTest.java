package com.jetbrains.teamcity.jenkinsbridge.util;

import com.google.gson.JsonObject;
import jetbrains.buildServer.serverSide.ProjectManager;
import jetbrains.buildServer.serverSide.SBuildType;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertNull;
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
  public void describeExceptionIncludesClassNameAndMessage() {
    assertEquals("IllegalArgumentException: bad input",
        Utilities.describeException(new IllegalArgumentException("bad input")));
  }

  @Test
  public void describeExceptionOmitsMessageSeparatorWhenMessageIsNull() {
    assertEquals("RuntimeException", Utilities.describeException(new RuntimeException()));
  }
}
