package com.jetbrains.teamcity.jenkinsbridge.artifactstorage;

import jetbrains.buildServer.artifacts.ArtifactData;
import jetbrains.buildServer.serverSide.BuildPromotion;
import jetbrains.buildServer.serverSide.artifacts.StoredBuildArtifactInfo;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class JenkinsArtifactInfoUtilsTest {
  private final JenkinsArtifactInfoUtils utils = new JenkinsArtifactInfoUtils();

  @Test
  public void jenkinsJobReturnsParameterValue() {
    StoredBuildArtifactInfo info = infoWithParameter("jenkins.job", "folder/job");

    assertEquals("folder/job", utils.jenkinsJob(info));
  }

  @Test(expected = IllegalArgumentException.class)
  public void jenkinsJobThrowsWhenParameterIsMissing() {
    utils.jenkinsJob(infoWithParameter("jenkins.job", null));
  }

  @Test(expected = IllegalArgumentException.class)
  public void jenkinsJobThrowsWhenParameterIsEmpty() {
    utils.jenkinsJob(infoWithParameter("jenkins.job", ""));
  }

  @Test
  public void jenkinsBuildNumberParsesValidNumber() {
    StoredBuildArtifactInfo info = infoWithParameter("jenkins.build.number", "42");

    assertEquals(42, utils.jenkinsBuildNumber(info));
  }

  @Test
  public void jenkinsBuildNumberTrimsWhitespace() {
    StoredBuildArtifactInfo info = infoWithParameter("jenkins.build.number", " 42 ");

    assertEquals(42, utils.jenkinsBuildNumber(info));
  }

  @Test(expected = IllegalArgumentException.class)
  public void jenkinsBuildNumberThrowsWhenParameterIsMissing() {
    utils.jenkinsBuildNumber(infoWithParameter("jenkins.build.number", null));
  }

  @Test(expected = IllegalArgumentException.class)
  public void jenkinsBuildNumberThrowsWhenParameterIsEmpty() {
    utils.jenkinsBuildNumber(infoWithParameter("jenkins.build.number", ""));
  }

  @Test(expected = IllegalArgumentException.class)
  public void jenkinsBuildNumberThrowsWhenParameterIsNotANumber() {
    utils.jenkinsBuildNumber(infoWithParameter("jenkins.build.number", "not-a-number"));
  }

  @Test
  public void jenkinsRelativePathReturnsArtifactDataPath() {
    ArtifactData artifactData = mock(ArtifactData.class);
    when(artifactData.getPath()).thenReturn("target/app.jar");
    StoredBuildArtifactInfo info = mock(StoredBuildArtifactInfo.class);
    when(info.getArtifactData()).thenReturn(artifactData);

    assertEquals("target/app.jar", utils.jenkinsRelativePath(info));
  }

  @Test(expected = IllegalArgumentException.class)
  public void jenkinsRelativePathThrowsWhenArtifactDataIsAFolder() {
    StoredBuildArtifactInfo info = mock(StoredBuildArtifactInfo.class);
    when(info.getArtifactData()).thenReturn(null);

    utils.jenkinsRelativePath(info);
  }

  private static StoredBuildArtifactInfo infoWithParameter(String name, String value) {
    BuildPromotion promotion = mock(BuildPromotion.class);
    when(promotion.getParameterValue(name)).thenReturn(value);
    StoredBuildArtifactInfo info = mock(StoredBuildArtifactInfo.class);
    when(info.getBuildPromotion()).thenReturn(promotion);
    return info;
  }
}
