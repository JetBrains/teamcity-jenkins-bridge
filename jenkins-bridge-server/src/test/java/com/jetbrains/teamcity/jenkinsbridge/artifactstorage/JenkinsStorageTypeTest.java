package com.jetbrains.teamcity.jenkinsbridge.artifactstorage;

import com.jetbrains.teamcity.jenkinsbridge.jenkins.JenkinsClient;
import jetbrains.buildServer.serverSide.artifacts.ArtifactStorageTypeRegistry;
import jetbrains.buildServer.web.openapi.PluginDescriptor;
import org.junit.Test;

import static org.junit.Assert.*;
import static org.mockito.Mockito.mock;

public class JenkinsStorageTypeTest {

  private final JenkinsClient jenkinsClient = mock(JenkinsClient.class);
  private final JenkinsArtifactInfoUtils utils = new JenkinsArtifactInfoUtils();
  private final JenkinsArtifactDownloadSigner signer = new JenkinsArtifactDownloadSigner();
  private final JenkinsArtifactDownloadProcessor downloadProcessor = new JenkinsArtifactDownloadProcessor(utils, signer, jenkinsClient);
  private final JenkinsArtifactContentProvider contentProvider = new JenkinsArtifactContentProvider(jenkinsClient, utils);
  private final JenkinsStorageType storageType = new JenkinsStorageType(mock(ArtifactStorageTypeRegistry.class), mock(PluginDescriptor.class));


  @Test
  public void getTypeContainsJenkins() {
    assertTrue(storageType.getType().toLowerCase().contains("jenkins"));
  }

  @Test
  public void storageTypesAreTheSame() {
    assertEquals(downloadProcessor.getType(), storageType.getType());
    assertEquals(contentProvider.getType(), storageType.getType());
  }

  @Test
  public void getNameContainsJenkins() {
    assertTrue(storageType.getName().toLowerCase().contains("jenkins"));
  }
}