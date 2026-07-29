package com.jetbrains.teamcity.jenkinsbridge.web;

import com.jetbrains.teamcity.jenkinsbridge.artifactstorage.ExpiringSignature;
import com.jetbrains.teamcity.jenkinsbridge.artifactstorage.JenkinsArtifactContentProvider;
import com.jetbrains.teamcity.jenkinsbridge.artifactstorage.JenkinsArtifactDownloadSigner;
import com.jetbrains.teamcity.jenkinsbridge.artifactstorage.JenkinsArtifactInfoUtils;
import com.jetbrains.teamcity.jenkinsbridge.http.BridgeHttpClient;
import com.jetbrains.teamcity.jenkinsbridge.http.BridgeHttpException;
import com.jetbrains.teamcity.jenkinsbridge.jenkins.JenkinsClient;
import com.jetbrains.teamcity.jenkinsbridge.settings.JenkinsBridgeSettings;
import com.jetbrains.teamcity.jenkinsbridge.settings.JenkinsBridgeSettingsProvider;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class JenkinsArtifactSignedDownloadControllerTest {
  private static final Charset OUR_CHARSET = StandardCharsets.UTF_8;
  private final JenkinsArtifactDownloadSigner signer = new JenkinsArtifactDownloadSigner();
  private final JenkinsBridgeSettings settings = mock(JenkinsBridgeSettings.class);
  private final JenkinsBridgeSettingsProvider settingsProvider = mock(JenkinsBridgeSettingsProvider.class);

  @Test
  public void streamsTheArtifactWhenTheSignatureIsValid() throws Exception {
    when(settings.getJenkinsUrl()).thenReturn("http://jenkins.instance");
    when(settingsProvider.load()).thenReturn(settings);
    JenkinsClient jenkinsClient = new JenkinsClient(settingsProvider, new StubStreamHttpClient("artifact bytes"), null);
    JenkinsArtifactContentProvider contentProvider = new JenkinsArtifactContentProvider(jenkinsClient, new JenkinsArtifactInfoUtils());
    JenkinsArtifactSignedDownloadController controller = new JenkinsArtifactSignedDownloadController(
        mock(WebControllerManager.class), contentProvider, signer);

    ExpiringSignature signed = signer.sign("job", 7, "target/app.jar");
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
    JenkinsClient jenkinsClient = new JenkinsClient(settingsProvider, new StubStreamHttpClient("artifact bytes"), null);
    JenkinsArtifactContentProvider contentProvider = new JenkinsArtifactContentProvider(jenkinsClient, new JenkinsArtifactInfoUtils());
    JenkinsArtifactSignedDownloadController controller = new JenkinsArtifactSignedDownloadController(
        mock(WebControllerManager.class), contentProvider, signer);

    long expiry = signer.sign("job", 7, "target/app.jar").expiry();
    HttpServletRequest request = requestWith("job", "7", "target/app.jar", expiry, "not-the-real-signature");
    HttpServletResponse response = mock(HttpServletResponse.class);
    StringWriter body = new StringWriter();
    when(response.getWriter()).thenReturn(new PrintWriter(body));

    controller.doHandle(request, response);

    verify(response).setStatus(403);
  }

  @Test
  public void rejectsAnExpiredSignatureWith403() throws Exception {
    JenkinsClient jenkinsClient = new JenkinsClient(settingsProvider, new StubStreamHttpClient("artifact bytes"), null);
    JenkinsArtifactContentProvider contentProvider = new JenkinsArtifactContentProvider(jenkinsClient, new JenkinsArtifactInfoUtils());
    JenkinsArtifactSignedDownloadController controller = new JenkinsArtifactSignedDownloadController(
        mock(WebControllerManager.class), contentProvider, signer);

    long expiry = 0L;
    ExpiringSignature signed = signer.sign("job", 7, "target/app.jar");
    HttpServletRequest request = requestWith("job", "7", "target/app.jar", expiry, signed.signature());
    HttpServletResponse response = mock(HttpServletResponse.class);
    StringWriter body = new StringWriter();
    when(response.getWriter()).thenReturn(new PrintWriter(body));

    controller.doHandle(request, response);

    verify(response).setStatus(403);
  }

  @Test
  public void respondsWith400WhenParametersAreMissing() throws Exception {
    JenkinsClient jenkinsClient = new JenkinsClient(settingsProvider, new StubStreamHttpClient("artifact bytes"), null);
    JenkinsArtifactContentProvider contentProvider = new JenkinsArtifactContentProvider(jenkinsClient, new JenkinsArtifactInfoUtils());
    JenkinsArtifactSignedDownloadController controller = new JenkinsArtifactSignedDownloadController(
        mock(WebControllerManager.class), contentProvider, signer);

    HttpServletRequest request = mock(HttpServletRequest.class);
    HttpServletResponse response = mock(HttpServletResponse.class);
    StringWriter body = new StringWriter();
    when(response.getWriter()).thenReturn(new PrintWriter(body));

    controller.doHandle(request, response);

    verify(response).setStatus(400);
  }

  private static HttpServletRequest requestWith(
      String job, String build, String path, long expiry, String signature) {
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getParameter("job")).thenReturn(job);
    when(request.getParameter("build")).thenReturn(build);
    when(request.getParameter("path")).thenReturn(path);
    when(request.getParameter("expires")).thenReturn(String.valueOf(expiry));
    when(request.getParameter("signature")).thenReturn(signature);
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
