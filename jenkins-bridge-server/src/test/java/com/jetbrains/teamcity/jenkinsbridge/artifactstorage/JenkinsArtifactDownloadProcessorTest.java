package com.jetbrains.teamcity.jenkinsbridge.artifactstorage;

import com.jetbrains.teamcity.jenkinsbridge.jenkins.JenkinsClient;
import com.jetbrains.teamcity.jenkinsbridge.web.JenkinsArtifactSignedDownloadController;
import jetbrains.buildServer.artifacts.ArtifactData;
import jetbrains.buildServer.serverSide.BuildPromotion;
import jetbrains.buildServer.serverSide.artifacts.StoredBuildArtifactInfo;
import org.junit.Test;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class JenkinsArtifactDownloadProcessorTest {
  private final JenkinsArtifactDownloadSigner signer = new JenkinsArtifactDownloadSigner();
  private final JenkinsClient jenkinsClient = mock(JenkinsClient.class);
  private final JenkinsArtifactDownloadProcessor processor =
      new JenkinsArtifactDownloadProcessor(new JenkinsArtifactInfoUtils(), signer, jenkinsClient);

  @Test
  public void processDownloadRedirectsToTheSignedDownloadControllerForAnAgentRequest() throws IOException {
    StoredBuildArtifactInfo info = artifactInfo("folder/job", "7", "target/app.jar");
    HttpServletRequest request = agentRequest();
    when(request.getContextPath()).thenReturn("/bs");
    HttpServletResponse response = mock(HttpServletResponse.class);

    processor.processDownload(info, info.getBuildPromotion(), request, response);

    org.mockito.ArgumentCaptor<String> captor = forClass(String.class);
    verify(response).sendRedirect(captor.capture());
    String redirectUrl = captor.getValue();

    assertTrue(redirectUrl.startsWith("/bs" + JenkinsArtifactSignedDownloadController.PATH + "?"));
    Map<String, String> params = queryParams(redirectUrl);
    assertEquals("folder/job", params.get("job"));
    assertEquals("7", params.get("build"));
    assertEquals("target/app.jar", params.get("path"));
    assertTrue(signer.isValid(
        params.get("job"),
        Integer.parseInt(params.get("build")),
        params.get("path"),
        Long.parseLong(params.get("expires")),
        params.get("signature")));
  }

  @Test
  public void processDownloadRedirectsStraightToJenkinsForABrowserRequest() throws IOException {
    StoredBuildArtifactInfo info = artifactInfo("folder/job", "7", "target/app.jar");
    when(jenkinsClient.artifactUrl("folder/job", 7, "target/app.jar")).thenReturn("http://jenkins.instance/job/folder/job/7/artifact/target/app.jar");
    HttpServletResponse response = mock(HttpServletResponse.class);

    processor.processDownload(info, info.getBuildPromotion(), browserRequest(), response);

    verify(response).sendRedirect("http://jenkins.instance/job/folder/job/7/artifact/target/app.jar");
  }

  @Test
  public void processDownloadReturnsTrueAfterRedirecting() throws IOException {
    StoredBuildArtifactInfo info = artifactInfo("job", "1", "a.txt");

    boolean handled = processor.processDownload(
        info, info.getBuildPromotion(), agentRequest(), mock(HttpServletResponse.class));

    assertTrue(handled);
  }

  @Test(expected = IllegalArgumentException.class)
  public void processDownloadThrowsWhenJobParameterIsMissing() throws IOException {
    StoredBuildArtifactInfo info = artifactInfo(null, "1", "a.txt");

    processor.processDownload(
        info, info.getBuildPromotion(), agentRequest(), mock(HttpServletResponse.class));
  }

  private static HttpServletRequest agentRequest() {
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getHeader("User-Agent")).thenReturn("TeamCity Agent 2024.1");
    return request;
  }

  private static HttpServletRequest browserRequest() {
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getHeader("User-Agent")).thenReturn("Mozilla/5.0");
    return request;
  }

  private static StoredBuildArtifactInfo artifactInfo(String job, String buildNumber, String relativePath) {
    BuildPromotion promotion = mock(BuildPromotion.class);
    when(promotion.getParameterValue("jenkins.job")).thenReturn(job);
    when(promotion.getParameterValue("jenkins.build.number")).thenReturn(buildNumber);

    ArtifactData artifactData = mock(ArtifactData.class);
    when(artifactData.getPath()).thenReturn(relativePath);

    StoredBuildArtifactInfo info = mock(StoredBuildArtifactInfo.class);
    when(info.getBuildPromotion()).thenReturn(promotion);
    when(info.getArtifactData()).thenReturn(artifactData);
    return info;
  }

  private static Map<String, String> queryParams(String url) {
    String query = url.substring(url.indexOf('?') + 1);
    Map<String, String> result = new LinkedHashMap<>();
    for (String pair : query.split("&")) {
      int eq = pair.indexOf('=');
      String key = pair.substring(0, eq);
      String value = URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
      result.put(key, value);
    }
    return result;
  }
}
