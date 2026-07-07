package com.jetbrains.teamcity.jenkinsbridge.artifactstorage;

import com.jetbrains.teamcity.jenkinsbridge.http.BridgeHttpClient;
import com.jetbrains.teamcity.jenkinsbridge.http.BridgeHttpException;
import com.jetbrains.teamcity.jenkinsbridge.jenkins.JenkinsClient;
import jetbrains.buildServer.serverSide.artifacts.ArtifactContentProvider;
import jetbrains.buildServer.serverSide.artifacts.StoredBuildArtifactInfo;
import org.jetbrains.annotations.NotNull;

import java.io.*;

public class JenkinsArtifactContentProvider implements ArtifactContentProvider {

  private final JenkinsClient myJenkinsClient;
  private final JenkinsArtifactInfoUtils myJenkinsArtifactInfoUtils;

  public JenkinsArtifactContentProvider(
      @NotNull JenkinsClient jenkinsClient,
      @NotNull JenkinsArtifactInfoUtils jenkinsArtifactInfoUtils
  ) {
    myJenkinsClient = jenkinsClient;
    myJenkinsArtifactInfoUtils = jenkinsArtifactInfoUtils;
  }

  @NotNull
  @Override
  public String getType() {
    return JenkinsStorageConstants.JENKINS_STORAGE_TYPE;
  }

  @NotNull
  @Override
  public InputStream getContent(@NotNull StoredBuildArtifactInfo info) throws IOException {
    String job = myJenkinsArtifactInfoUtils.jenkinsJob(info);
    int buildNumber = myJenkinsArtifactInfoUtils.jenkinsBuildNumber(info);
    String relativePath = myJenkinsArtifactInfoUtils.jenkinsRelativePath(info);

    final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    try {
      handleArtifactStream(job, buildNumber, relativePath, buffer);
    } catch (BridgeHttpException e) {
      throw new IOException("Failed to fetch Jenkins artifact " + relativePath + ": " + e.getMessage(), e);
    }
    return new ByteArrayInputStream(buffer.toByteArray());
  }

  public void handleArtifactStream(String job, int buildNumber, String relativePath, OutputStream buffer) throws BridgeHttpException {
    myJenkinsClient.streamArtifact(job, buildNumber, relativePath,
        (BridgeHttpClient.StreamHandler) inputStream -> {
          byte[] chunk = new byte[8192];
          int read;
          while ((read = inputStream.read(chunk)) != -1) {
            buffer.write(chunk, 0, read);
          }
        });
  }
}
