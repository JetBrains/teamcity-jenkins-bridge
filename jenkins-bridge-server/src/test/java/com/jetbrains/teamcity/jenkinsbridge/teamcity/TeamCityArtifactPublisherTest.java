package com.jetbrains.teamcity.jenkinsbridge.teamcity;

import com.jetbrains.teamcity.jenkinsbridge.model.JenkinsArtifact;
import jetbrains.buildServer.ArtifactsConstants;
import jetbrains.buildServer.artifacts.util.ArtifactListUtil;
import jetbrains.buildServer.artifacts.util.SerializableArtifactListData;
import jetbrains.buildServer.serverSide.BuildAttributes;
import jetbrains.buildServer.serverSide.BuildPromotionEx;
import jetbrains.buildServer.serverSide.RunningBuildEx;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import java.io.ByteArrayInputStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class TeamCityArtifactPublisherTest {
  @Test
  public void publishArtifactListWritesSerializedArtifactsWhenPromotionHasStorageReference() throws Exception {
    RunningBuildEx runningBuild = mock(RunningBuildEx.class);
    when(runningBuild.getProjectExternalId()).thenReturn("Project1");
    BuildPromotionEx promotion = mock(BuildPromotionEx.class);
    when(promotion.getParameterValue("jenkins.job")).thenReturn("job");
    when(promotion.getParameterValue("jenkins.build.number")).thenReturn("7");
    when(promotion.getParameterValue("jenkins.connection.id")).thenReturn("conn1");
    when(promotion.getAttribute(BuildAttributes.STORAGE_SETTINGS_REFERENCE)).thenReturn("STORAGE-1");
    when(runningBuild.getBuildPromotion()).thenReturn(promotion);
    TeamCityArtifactPublisher publisher =
        new TeamCityArtifactPublisher(new FixedLocator(runningBuild));

    List<JenkinsArtifact> artifacts = Arrays.asList(
        new JenkinsArtifact("app.jar", "target/app.jar", 100),
        new JenkinsArtifact("report.txt", "reports/report.txt", 50));

    publisher.publishArtifactList(42L, artifacts);

    ArgumentCaptor<byte[]> captor = ArgumentCaptor.forClass(byte[].class);
    verify(runningBuild).publishArtifact(eq(ArtifactsConstants.ARTIFACT_LIST_PATH), captor.capture());

    SerializableArtifactListData listData =
        ArtifactListUtil.readArtifactList(new ByteArrayInputStream(captor.getValue()));
    assertEquals("STORAGE-1", listData.getStorageSettingsId());
    assertEquals(2, listData.getArtifactList().size());
    assertEquals("target/app.jar", listData.getArtifactList().get(0).getPath());
    assertEquals(100L, listData.getArtifactList().get(0).getSize());
    assertEquals("reports/report.txt", listData.getArtifactList().get(1).getPath());
    assertEquals("job", listData.getCommonProperties().get("jenkins.job"));
    assertEquals("7", listData.getCommonProperties().get("jenkins.build.number"));
    assertEquals("conn1", listData.getCommonProperties().get("jenkins.connection.id"));
  }

  @Test
  public void publishArtifactListSkipsWhenProjectExternalIdIsMissing() throws Exception {
    RunningBuildEx runningBuild = mock(RunningBuildEx.class);
    when(runningBuild.getProjectExternalId()).thenReturn(null);
    TeamCityArtifactPublisher publisher =
        new TeamCityArtifactPublisher(new FixedLocator(runningBuild));

    publisher.publishArtifactList(42L, Arrays.asList(new JenkinsArtifact("a.txt", "a.txt", 1)));

    verify(runningBuild, never()).publishArtifact(anyString(), any(byte[].class));
  }

  @Test
  public void publishArtifactListSkipsWhenPromotionHasNoStorageReference() throws Exception {
    RunningBuildEx runningBuild = mock(RunningBuildEx.class);
    when(runningBuild.getProjectExternalId()).thenReturn("Project1");
    TeamCityArtifactPublisher publisher =
        new TeamCityArtifactPublisher(new FixedLocator(runningBuild));

    publisher.publishArtifactList(42L, Arrays.asList(new JenkinsArtifact("a.txt", "a.txt", 1)));

    verify(runningBuild, never()).publishArtifact(anyString(), any(byte[].class));
  }

  @Test
  public void publishArtifactListDoesNotNeedStorageForEmptyArtifactList() throws Exception {
    RunningBuildEx runningBuild = mock(RunningBuildEx.class);
    when(runningBuild.getProjectExternalId()).thenReturn("Project1");
    TeamCityArtifactPublisher publisher =
        new TeamCityArtifactPublisher(new FixedLocator(runningBuild));

    publisher.publishArtifactList(42L, Collections.<JenkinsArtifact>emptyList());

    verify(runningBuild, never()).publishArtifact(anyString(), any(byte[].class));
  }

  @Test(expected = java.io.IOException.class)
  public void publishArtifactListFailsWhenBuildIsNoLongerRunning() throws Exception {
    TeamCityArtifactPublisher publisher =
        new TeamCityArtifactPublisher(new FixedLocator(null));

    publisher.publishArtifactList(42L, Arrays.asList(new JenkinsArtifact("a.txt", "a.txt", 1)));
  }

  private static class FixedLocator extends TeamCityRunningBuildLocator {
    private final RunningBuildEx runningBuild;

    FixedLocator(RunningBuildEx runningBuild) {
      super(null, null, null);
      this.runningBuild = runningBuild;
    }

    @Override
    public RunningBuildEx findRunningBuild(long id) {
      return runningBuild;
    }
  }

}
