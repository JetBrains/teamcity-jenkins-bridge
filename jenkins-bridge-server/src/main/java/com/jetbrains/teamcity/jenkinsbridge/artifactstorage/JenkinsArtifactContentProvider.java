package com.jetbrains.teamcity.jenkinsbridge.artifactstorage;

import com.jetbrains.teamcity.jenkinsbridge.http.BridgeHttpClient;
import com.jetbrains.teamcity.jenkinsbridge.http.BridgeHttpException;
import com.jetbrains.teamcity.jenkinsbridge.jenkins.JenkinsClient;
import com.jetbrains.teamcity.jenkinsbridge.jenkins.JenkinsClientFactory;
import jetbrains.buildServer.serverSide.artifacts.ArtifactContentProvider;
import jetbrains.buildServer.serverSide.artifacts.StoredBuildArtifactInfo;
import org.jetbrains.annotations.NotNull;

import java.io.*;

public class JenkinsArtifactContentProvider implements ArtifactContentProvider {

  private final JenkinsClientFactory myJenkinsClientFactory;
  private final JenkinsArtifactInfoUtils myJenkinsArtifactInfoUtils;

  public JenkinsArtifactContentProvider(
      @NotNull JenkinsClientFactory jenkinsClientFactory,
      @NotNull JenkinsArtifactInfoUtils jenkinsArtifactInfoUtils
  ) {
    myJenkinsClientFactory = jenkinsClientFactory;
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
      handleArtifactStream(
          myJenkinsClientFactory.forBuildPromotion(info.getBuildPromotion()),
          job, buildNumber, relativePath, buffer);
    } catch (BridgeHttpException e) {
      throw new IOException("Failed to fetch Jenkins artifact " + relativePath + ": " + e.getMessage(), e);
    }
    return new ByteArrayInputStream(buffer.toByteArray());
  }

  /**
   * Copies one Jenkins artifact into the given stream.
   *
   * @param jenkinsClient client bound to the Jenkins server holding the artifact
   * @param job           Jenkins job path
   * @param buildNumber   Jenkins build number
   * @param relativePath  artifact path inside the build
   * @param buffer        stream the artifact bytes are written to
   */
  public void handleArtifactStream(@NotNull JenkinsClient jenkinsClient, String job, int buildNumber,
                                   String relativePath, OutputStream buffer) throws BridgeHttpException {
    jenkinsClient.streamArtifact(job, buildNumber, relativePath,
        (BridgeHttpClient.StreamHandler) inputStream -> {
          byte[] chunk = new byte[8192];
          int read;
          while ((read = inputStream.read(chunk)) != -1) {
            buffer.write(chunk, 0, read);
          }
        });
  }

}
