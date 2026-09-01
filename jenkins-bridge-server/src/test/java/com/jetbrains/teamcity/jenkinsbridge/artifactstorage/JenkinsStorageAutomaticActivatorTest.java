package com.jetbrains.teamcity.jenkinsbridge.artifactstorage;

import com.jetbrains.teamcity.jenkinsbridge.feature.BridgeBuildFeatureConstants;
import jetbrains.buildServer.artifacts.ArtifactStorageSettings;
import jetbrains.buildServer.serverSide.ProjectManager;
import jetbrains.buildServer.serverSide.BuildAttributes;
import jetbrains.buildServer.serverSide.BuildPromotionEx;
import jetbrains.buildServer.serverSide.SProject;
import jetbrains.buildServer.serverSide.SProjectFeatureDescriptor;
import jetbrains.buildServer.serverSide.artifacts.ArtifactStorageTypeRegistry;
import jetbrains.buildServer.serverSide.storage.ArtifactsStorageSettingsManager;
import jetbrains.buildServer.web.openapi.PluginDescriptor;
import org.junit.Test;

import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class JenkinsStorageAutomaticActivatorTest {
  private final ArtifactsStorageSettingsManager settingsManager = mock(ArtifactsStorageSettingsManager.class);
  private final JenkinsStorageType jenkinsStorageType =
      new JenkinsStorageType(mock(ArtifactStorageTypeRegistry.class), mock(PluginDescriptor.class));
  private final ProjectManager projectManager = mock(ProjectManager.class);
  private final JenkinsStorageAutomaticActivator activator =
      new JenkinsStorageAutomaticActivator(settingsManager, jenkinsStorageType, projectManager);

  @Test
  public void ensureJenkinsStorageAlreadyExistsDoesNotActivateProjectStorage() {
    SProject project = projectWithExternalId("Project1");
    jenkinsFeature(project, "JENKINS-STORAGE");
    String result = activator.ensureJenkinsStorage("Project1");

    assertEquals("JENKINS-STORAGE", result);
    verify(settingsManager, never()).addSettings(any(), any(), any(), anyBoolean(), anyMap());
  }

  @Test
  public void ensureJenkinsStorageReusesExistingInactiveStorage() {
    SProject project = projectWithExternalId("Project2");
    jenkinsFeature(project, "JENKINS-STORAGE");
    String result = activator.ensureJenkinsStorage("Project2");

    assertEquals("JENKINS-STORAGE", result);
    verify(settingsManager, never()).activateSettings(any(), any());
  }

  @Test
  public void ensureJenkinsStorageCreatesSettingsWhenNoneExist() {
    SProject project = projectWithExternalId("Project3");
    SProjectFeatureDescriptor created = mock(SProjectFeatureDescriptor.class);
    when(created.getId()).thenReturn("STORAGE-NEW");
    when(settingsManager.addSettings(eq(project), any(), eq(jenkinsStorageType), eq(false), anyMap()))
        .thenReturn(created);
    String result = activator.ensureJenkinsStorage("Project3");

    assertEquals("STORAGE-NEW", result);
  }

  @Test
  public void ensureJenkinsStorageReturnsNullWhenProjectNotFound() {
    when(projectManager.findProjectByExternalId("MissingProject")).thenReturn(null);
    String result = activator.ensureJenkinsStorage("MissingProject");
    assertNull(result);
  }

  @Test
  public void configurePromotionStorageAttachesStorageReference() {
    SProject project = projectWithExternalId("Project4");
    jenkinsFeature(project, "JENKINS-STORAGE");
    BuildPromotionEx promotion = mock(BuildPromotionEx.class);

    activator.configurePromotionStorage(promotion, "Project4");

    verify(promotion).setAttribute(BuildAttributes.STORAGE_SETTINGS_REFERENCE, "JENKINS-STORAGE");
  }

  @Test
  public void configurePromotionStorageRecordsWarningWhenStorageIsUnavailable() {
    BuildPromotionEx promotion = mock(BuildPromotionEx.class);
    when(promotion.getId()).thenReturn(42L);

    activator.configurePromotionStorage(promotion, "MissingProject");

    verify(promotion).setAttribute(
        eq(BridgeBuildFeatureConstants.JENKINS_STORAGE_WARNING_ATTRIBUTE), anyString());
  }

  private SProject projectWithExternalId(String externalId) {
    SProject project = mock(SProject.class);
    when(projectManager.findProjectByExternalId(externalId)).thenReturn(project);
    when(project.getOwnFeaturesOfType(ArtifactStorageSettings.STORAGE_FEATURE_TYPE))
        .thenReturn(Collections.emptyList());
    return project;
  }

  private void jenkinsFeature(SProject project, String id) {
    SProjectFeatureDescriptor descriptor = mock(SProjectFeatureDescriptor.class);
    when(descriptor.getId()).thenReturn(id);
    when(descriptor.getParameters())
        .thenReturn(Collections.singletonMap(ArtifactStorageSettings.TEAMCITY_STORAGE_TYPE_KEY, "jenkins"));
    when(project.getOwnFeaturesOfType(ArtifactStorageSettings.STORAGE_FEATURE_TYPE))
        .thenReturn(Collections.singletonList(descriptor));
  }
}
