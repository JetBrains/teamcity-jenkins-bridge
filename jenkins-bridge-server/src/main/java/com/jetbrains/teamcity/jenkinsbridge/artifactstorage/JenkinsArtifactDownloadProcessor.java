package com.jetbrains.teamcity.jenkinsbridge.artifactstorage;

import com.jetbrains.teamcity.jenkinsbridge.jenkins.JenkinsClient;
import com.jetbrains.teamcity.jenkinsbridge.web.JenkinsArtifactSignedDownloadController;
import jetbrains.buildServer.serverSide.BuildPromotion;
import jetbrains.buildServer.serverSide.artifacts.StoredBuildArtifactInfo;
import jetbrains.buildServer.web.openapi.artifacts.ArtifactDownloadProcessor;
import jetbrains.buildServer.web.util.WebUtil;
import org.jetbrains.annotations.NotNull;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

public class JenkinsArtifactDownloadProcessor implements ArtifactDownloadProcessor {

  private static final Charset OUR_CHARSET = StandardCharsets.UTF_8;
  private final JenkinsArtifactInfoUtils myJenkinsArtifactInfoUtils;
  private final JenkinsArtifactDownloadSigner mySigner;
  private final JenkinsClient myJenkinsClient;

  public JenkinsArtifactDownloadProcessor(
      @NotNull JenkinsArtifactInfoUtils jenkinsArtifactInfoUtils,
      @NotNull JenkinsArtifactDownloadSigner signer,
      @NotNull JenkinsClient jenkinsClient
  ) {
    myJenkinsArtifactInfoUtils = jenkinsArtifactInfoUtils;
    mySigner = signer;
    myJenkinsClient = jenkinsClient;
  }

  @NotNull
  @Override
  public String getType() {
    return JenkinsStorageConstants.JENKINS_STORAGE_TYPE;
  }

  @Override
  public boolean processDownload(@NotNull StoredBuildArtifactInfo info,
                                 @NotNull BuildPromotion buildPromotion,
                                 @NotNull HttpServletRequest httpServletRequest,
                                 @NotNull HttpServletResponse httpServletResponse) throws IOException {
    String job = myJenkinsArtifactInfoUtils.jenkinsJob(info);
    int buildNumber = myJenkinsArtifactInfoUtils.jenkinsBuildNumber(info);
    String relativePath = myJenkinsArtifactInfoUtils.jenkinsRelativePath(info);

    if (!WebUtil.isTeamCityAgent(httpServletRequest)) {
      httpServletResponse.sendRedirect(myJenkinsClient.artifactUrl(job, buildNumber, relativePath));
      return true;
    }

    ExpiringSignature signature = mySigner.sign(job, buildNumber, relativePath);

    // Agents are not authenticated, so redirect to a custom endpoint that adds the Authorization header,
    // since it cannot be added here directly.
    String redirectUrl = httpServletRequest.getContextPath()
        + JenkinsArtifactSignedDownloadController.PATH
        + "?job=" + URLEncoder.encode(job, OUR_CHARSET)
        + "&build=" + buildNumber
        + "&path=" + URLEncoder.encode(relativePath, OUR_CHARSET)
        + "&expires=" + signature.expiry()
        + "&signature=" + signature.signature();
    httpServletResponse.sendRedirect(redirectUrl);
    return true;
  }

}
