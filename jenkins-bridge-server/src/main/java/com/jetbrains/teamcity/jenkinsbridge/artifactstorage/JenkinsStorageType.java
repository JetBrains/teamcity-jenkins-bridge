package com.jetbrains.teamcity.jenkinsbridge.artifactstorage;

import jetbrains.buildServer.serverSide.artifacts.ArtifactStorageType;
import jetbrains.buildServer.serverSide.artifacts.ArtifactStorageTypeRegistry;
import jetbrains.buildServer.web.openapi.PluginDescriptor;
import org.jetbrains.annotations.NotNull;

public class JenkinsStorageType extends ArtifactStorageType {

  @NotNull
  private final String mySettingsJSP;

  public JenkinsStorageType(@NotNull ArtifactStorageTypeRegistry registry,
                            @NotNull PluginDescriptor descriptor) {
    mySettingsJSP = descriptor.getPluginResourcesPath(JenkinsStorageConstants.JENKINS_STORAGE_SETTINGS_PATH + ".jsp");
    registry.registerStorageType(this);
  }

  @NotNull
  @Override
  public String getType() {
    return JenkinsStorageConstants.JENKINS_STORAGE_TYPE;
  }

  @NotNull
  @Override
  public String getName() {
    return "Jenkins";
  }

  @NotNull
  @Override
  public String getDescription() {
    return "Delegates artifact storage to Jenkins. TeamCity only links to it.";
  }

  @NotNull
  @Override
  public String getEditStorageParametersPath() {
    return mySettingsJSP;
  }
}
