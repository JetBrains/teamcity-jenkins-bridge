package com.jetbrains.teamcity.jenkinsbridge.teamcity;

import com.intellij.openapi.diagnostic.Logger;
import com.jetbrains.teamcity.jenkinsbridge.artifactstorage.JenkinsStorageAutomaticActivator;
import com.jetbrains.teamcity.jenkinsbridge.model.JenkinsArtifact;
import jetbrains.buildServer.ArtifactsConstants;
import jetbrains.buildServer.artifacts.ArtifactData;
import jetbrains.buildServer.artifacts.ArtifactDataInstance;
import jetbrains.buildServer.artifacts.util.ArtifactListUtil;
import jetbrains.buildServer.artifacts.util.SerializableArtifactListData;
import jetbrains.buildServer.serverSide.RunningBuildEx;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class TeamCityArtifactPublisher {

  private static final Logger LOG = Logger.getInstance(TeamCityArtifactPublisher.class.getName());

  @NotNull
  private static final Charset OUR_CHARSET = StandardCharsets.UTF_8;

  private final TeamCityRunningBuildLocator myBuildLocator;
  private final JenkinsStorageAutomaticActivator myStorageActivator;

  public TeamCityArtifactPublisher(
      TeamCityRunningBuildLocator buildLocator,
      JenkinsStorageAutomaticActivator storageActivator
  ) {
    myBuildLocator = buildLocator;
    myStorageActivator = storageActivator;
  }

  /**
   * Registers {@code artifacts} as externally stored in Jenkins by publishing an artifact list
   * file to {@link ArtifactsConstants#ARTIFACT_LIST_PATH}.
   */
  public void publishArtifactList(long buildId, List<JenkinsArtifact> artifacts) throws IOException {
    RunningBuildEx runningBuild = myBuildLocator.findRunningBuild(buildId);
    if (runningBuild == null) {
      throw new IOException("TeamCity build " + buildId + " is already finished");
    }

    String msg = "'Jenkins' artifact storage could not be configured as the active storage for TeamCity project " + runningBuild.getProjectExternalId() + ". Skipping artifact registration.";

    String projectExternalId = runningBuild.getProjectExternalId();
    if (projectExternalId == null) {
      LOG.warn(msg);
      return;
    }
    String storageFeatureId = myStorageActivator.activateJenkinsStorage(projectExternalId);
    if (storageFeatureId == null) {
      LOG.warn(msg);
      return;
    }

    List<ArtifactData> artifactDataList = new ArrayList<>();
    for (JenkinsArtifact artifact : artifacts) {
      String path = artifact.relativePath();
      artifactDataList.add(ArtifactDataInstance.create(path, artifact.size()));
    }

    if (artifactDataList.isEmpty()) {
      return;
    }

    Map<String, String> commonProperties = new LinkedHashMap<>();
    copyPromotionParameter(runningBuild, "jenkins.job", commonProperties);
    copyPromotionParameter(runningBuild, "jenkins.build.number", commonProperties);
    copyPromotionParameter(runningBuild, "jenkins.connection.id", commonProperties);

    SerializableArtifactListData listData = new SerializableArtifactListData(
        storageFeatureId, commonProperties, artifactDataList);
    StringWriter writer = new StringWriter();
    ArtifactListUtil.writeArtifactList(listData, writer);
    byte[] bytes = writer.toString().getBytes(OUR_CHARSET);
    runningBuild.publishArtifact(ArtifactsConstants.ARTIFACT_LIST_PATH, bytes);
  }

  private static void copyPromotionParameter(RunningBuildEx runningBuild, String key,
                                             Map<String, String> target) {
    String value = runningBuild.getBuildPromotion().getParameterValue(key);
    if (value != null && !value.trim().isEmpty()) {
      target.put(key, value);
    }
  }
}
