package com.jetbrains.teamcity.jenkinsbridge.web;

import com.jetbrains.teamcity.jenkinsbridge.artifactstorage.ExpiringSignature;
import com.jetbrains.teamcity.jenkinsbridge.artifactstorage.JenkinsArtifactContentProvider;
import com.jetbrains.teamcity.jenkinsbridge.artifactstorage.JenkinsArtifactDownloadSigner;
import com.jetbrains.teamcity.jenkinsbridge.artifactstorage.JenkinsArtifactInfoUtils;
import com.jetbrains.teamcity.jenkinsbridge.connection.JenkinsConnection;
import com.jetbrains.teamcity.jenkinsbridge.http.BridgeHttpClient;
import com.jetbrains.teamcity.jenkinsbridge.http.BridgeHttpException;
import com.jetbrains.teamcity.jenkinsbridge.jenkins.JenkinsClient;
import com.jetbrains.teamcity.jenkinsbridge.jenkins.JenkinsClientFactory;
import jetbrains.buildServer.serverSide.BuildPromotion;
import jetbrains.buildServer.serverSide.BuildPromotionManager;
import jetbrains.buildServer.web.openapi.WebControllerManager;
import org.junit.Test;
import org.springframework.web.servlet.ModelAndView;

import javax.servlet.ServletOutputStream;
import javax.servlet.WriteListener;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.ByteArrayOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class JenkinsArtifactSignedDownloadControllerTest {
  private static final Charset OUR_CHARSET = StandardCharsets.UTF_8;
  private static final long BUILD_ID = 4242L;

  private final JenkinsArtifactDownloadSigner signer = new JenkinsArtifactDownloadSigner();
  private final JenkinsClientFactory jenkinsClientFactory = mock(JenkinsClientFactory.class);
  private final BuildPromotionManager buildPromotionManager = mock(BuildPromotionManager.class);

  @Test
  public void streamsTheArtifactWhenTheSignatureIsValid() throws Exception {
    JenkinsArtifactSignedDownloadController controller = controller("artifact bytes");

    ExpiringSignature signed = signer.sign(BUILD_ID, "job", 7, "target/app.jar");
    HttpServletRequest request = requestWith("job", "7", "target/app.jar", signed.expiry(), signed.signature());
    HttpServletResponse response = mock(HttpServletResponse.class);
    FakeOutputStream out = new FakeOutputStream();
    when(response.getOutputStream()).thenReturn(out);

    ModelAndView result = controller.doHandle(request, response);

    assertNull(result);
    verify(response).setContentType("application/octet-stream");
    assertArrayEquals("artifact bytes".getBytes(OUR_CHARSET), out.buffer.toByteArray());
  }

  @Test
  public void rejectsAnInvalidSignatureWith403() throws Exception {
    JenkinsArtifactSignedDownloadController controller = controller("artifact bytes");

    long expiry = signer.sign(BUILD_ID, "job", 7, "target/app.jar").expiry();
    HttpServletRequest request = requestWith("job", "7", "target/app.jar", expiry, "not-the-real-signature");
    HttpServletResponse response = responseCollectingBody();

    controller.doHandle(request, response);

    verify(response).setStatus(403);
  }

  @Test
  public void rejectsAnExpiredSignatureWith403() throws Exception {
    JenkinsArtifactSignedDownloadController controller = controller("artifact bytes");

    ExpiringSignature signed = signer.sign(BUILD_ID, "job", 7, "target/app.jar");
    HttpServletRequest request = requestWith("job", "7", "target/app.jar", 0L, signed.signature());
    HttpServletResponse response = responseCollectingBody();

    controller.doHandle(request, response);

    verify(response).setStatus(403);
  }

  @Test
  public void rejectsASignatureIssuedForAnotherBuildWith403() throws Exception {
    JenkinsArtifactSignedDownloadController controller = controller("artifact bytes");

    ExpiringSignature signed = signer.sign(BUILD_ID + 1, "job", 7, "target/app.jar");
    HttpServletRequest request = requestWith("job", "7", "target/app.jar", signed.expiry(), signed.signature());
    HttpServletResponse response = responseCollectingBody();

    controller.doHandle(request, response);

    verify(response).setStatus(403);
  }

  @Test
  public void respondsWith400WhenParametersAreMissing() throws Exception {
    JenkinsArtifactSignedDownloadController controller = controller("artifact bytes");

    HttpServletRequest request = mock(HttpServletRequest.class);
    HttpServletResponse response = responseCollectingBody();

    controller.doHandle(request, response);

    verify(response).setStatus(400);
  }

  private JenkinsArtifactSignedDownloadController controller(String artifactContent) {
    JenkinsClient jenkinsClient = new JenkinsClient(
        new JenkinsConnection("http://jenkins.instance", "user", "token"),
        new StubStreamHttpClient(artifactContent),
        null);
    when(jenkinsClientFactory.forBuildPromotion(any(BuildPromotion.class))).thenReturn(jenkinsClient);
    when(buildPromotionManager.findPromotionOrReplacement(BUILD_ID)).thenReturn(mock(BuildPromotion.class));

    JenkinsArtifactContentProvider contentProvider =
        new JenkinsArtifactContentProvider(jenkinsClientFactory, new JenkinsArtifactInfoUtils());
    return new JenkinsArtifactSignedDownloadController(
        mock(WebControllerManager.class), contentProvider, signer, buildPromotionManager, jenkinsClientFactory);
  }

  private static HttpServletResponse responseCollectingBody() throws Exception {
    HttpServletResponse response = mock(HttpServletResponse.class);
    when(response.getWriter()).thenReturn(new PrintWriter(new StringWriter()));
    return response;
  }

  private static HttpServletRequest requestWith(
      String job, String build, String path, long expiry, String signature) {
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getParameter("job")).thenReturn(job);
    when(request.getParameter("build")).thenReturn(build);
    when(request.getParameter("path")).thenReturn(path);
    when(request.getParameter("expires")).thenReturn(String.valueOf(expiry));
    when(request.getParameter("signature")).thenReturn(signature);
    when(request.getParameter("buildId")).thenReturn(String.valueOf(BUILD_ID));
    return request;
  }

  private static class StubStreamHttpClient extends BridgeHttpClient {
    private final String content;

    StubStreamHttpClient(String content) {
      this.content = content;
    }

    @Override
    public void getStream(String url, String user, String password, String accept, StreamHandler handler)
        throws BridgeHttpException {
      try {
        handler.handle(new java.io.ByteArrayInputStream(content.getBytes(OUR_CHARSET)));
      } catch (java.io.IOException e) {
        throw new BridgeHttpException("GET", url, e);
      }
    }
  }

  private static class FakeOutputStream extends ServletOutputStream {
    final ByteArrayOutputStream buffer = new ByteArrayOutputStream();

    @Override
    public void write(int b) {
      buffer.write(b);
    }

    @Override
    public boolean isReady() {
      return true;
    }

    @Override
    public void setWriteListener(WriteListener writeListener) {
    }
  }
}
