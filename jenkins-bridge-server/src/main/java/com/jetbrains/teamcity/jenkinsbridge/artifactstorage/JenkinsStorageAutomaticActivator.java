package com.jetbrains.teamcity.jenkinsbridge.artifactstorage;

import com.intellij.openapi.diagnostic.Logger;
import jetbrains.buildServer.artifacts.ArtifactStorageSettings;
import jetbrains.buildServer.serverSide.ProjectManager;
import jetbrains.buildServer.serverSide.SProject;
import jetbrains.buildServer.serverSide.SProjectFeatureDescriptor;
import jetbrains.buildServer.serverSide.storage.ArtifactsStorageSettingsManager;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.Optional;
import java.util.UUID;

public class JenkinsStorageAutomaticActivator {

  private static final Logger LOG = Logger.getInstance(JenkinsStorageAutomaticActivator.class.getName());
  private final JenkinsStorageType myJenkinsStorageType;
  private final ArtifactsStorageSettingsManager mySettingsManager;
  private final ProjectManager myProjectManager;

  public JenkinsStorageAutomaticActivator(
      @NotNull ArtifactsStorageSettingsManager settingsManager,
      @NotNull JenkinsStorageType jenkinsStorageType,
      @NotNull ProjectManager projectManager
  ) {
    mySettingsManager = settingsManager;
    myJenkinsStorageType = jenkinsStorageType;
    myProjectManager = projectManager;
  }

  /**
   * Sets up the Jenkins artifact storage method if it doesn't exist for the given project and activates it if it's inactive.
   *
   * @param externalProjectId The external id of the project containing the mirrored Jenkins build configuration.
   * @return The id of the activated Jenkins artifact storage.
   */
  @Nullable
  public String activateJenkinsStorage(@NotNull String externalProjectId) {
    String jenkinsType = myJenkinsStorageType.getType();
    SProject project = myProjectManager.findProjectByExternalId(externalProjectId);
    if (project == null) {
      LOG.warn("Project with id " + externalProjectId + " not found");
      return null;
    }

    Optional<SProjectFeatureDescriptor> existing = project
        .getOwnFeaturesOfType(ArtifactStorageSettings.STORAGE_FEATURE_TYPE).stream()
        .filter(pfd -> jenkinsType.equals(pfd.getParameters().get(ArtifactStorageSettings.TEAMCITY_STORAGE_TYPE_KEY)))
        .findFirst();

    String settingsId = existing
        .map(SProjectFeatureDescriptor::getId)
        .orElseGet(() -> {
          String uuid = UUID.randomUUID().toString();
          SProjectFeatureDescriptor ptd = mySettingsManager.addSettings(project, "JENKINS-STORAGE-" + uuid, myJenkinsStorageType, true, Collections.emptyMap());
          return ptd.getId();
        });

    String activeId = mySettingsManager.findEffectiveSettings(project);
    if (!settingsId.equals(activeId)) {
      mySettingsManager.activateSettings(project, settingsId);
    }

    return settingsId;
  }
}
