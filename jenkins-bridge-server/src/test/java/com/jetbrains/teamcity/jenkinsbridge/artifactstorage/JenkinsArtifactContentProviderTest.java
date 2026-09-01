package com.jetbrains.teamcity.jenkinsbridge.artifactstorage;

import com.jetbrains.teamcity.jenkinsbridge.http.BridgeHttpClient;
import com.jetbrains.teamcity.jenkinsbridge.http.BridgeHttpException;
import com.jetbrains.teamcity.jenkinsbridge.jenkins.JenkinsClient;
import com.jetbrains.teamcity.jenkinsbridge.jenkins.JenkinsClientFactory;
import jetbrains.buildServer.artifacts.ArtifactData;
import jetbrains.buildServer.serverSide.BuildPromotion;
import jetbrains.buildServer.serverSide.artifacts.StoredBuildArtifactInfo;
import org.junit.Test;
import org.junit.After;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class JenkinsArtifactContentProviderTest {
  private static final String ARTIFACT_BUFFER_SIZE_PROPERTY =
      "teamcity.internal.jenkinsBridge.artifact.bufferSize";
  private final JenkinsClient jenkinsClient = mock(JenkinsClient.class);
  private final JenkinsClientFactory jenkinsClientFactory = mock(JenkinsClientFactory.class);
  private final JenkinsArtifactContentProvider provider =
      new JenkinsArtifactContentProvider(jenkinsClientFactory, new JenkinsArtifactInfoUtils());

  public JenkinsArtifactContentProviderTest() {
    when(jenkinsClientFactory.forBuildPromotion(any(BuildPromotion.class))).thenReturn(jenkinsClient);
  }

  @After
  public void clearProperties() {
    System.clearProperty(ARTIFACT_BUFFER_SIZE_PROPERTY);
  }

  @Test
  public void getContentReturnsBytesStreamedFromJenkins() throws Exception {
    StoredBuildArtifactInfo info = artifactInfo("folder/job", "7", "target/app.jar");
    doAnswer(invocation -> {
      BridgeHttpClient.StreamHandler handler = invocation.getArgument(3);
      handler.handle(new ByteArrayInputStream("hello".getBytes(StandardCharsets.UTF_8)));
      return null;
    }).when(jenkinsClient).streamArtifact(eq("folder/job"), eq(7), eq("target/app.jar"), any());

    InputStream content = provider.getContent(info);

    assertEquals("hello", readAll(content));
  }

  @Test
  public void usesConfiguredArtifactBufferSize() throws Exception {
    System.setProperty(ARTIFACT_BUFFER_SIZE_PROPERTY, "1234");
    assertEquals(1234, requestedBufferLength());
  }

  @Test
  public void usesDefaultBufferForNonPositiveProperty() throws Exception {
    System.setProperty(ARTIFACT_BUFFER_SIZE_PROPERTY, "0");
    assertEquals(64 * 1024, requestedBufferLength());
  }

  @Test
  public void usesDefaultBufferForMalformedProperty() throws Exception {
    System.setProperty(ARTIFACT_BUFFER_SIZE_PROPERTY, "not-a-number");
    assertEquals(64 * 1024, requestedBufferLength());
  }

  private int requestedBufferLength() throws Exception {
    ByteArrayInputStream source = new ByteArrayInputStream("hello".getBytes(StandardCharsets.UTF_8));
    int[] requestedLength = {0};
    doAnswer(invocation -> {
      BridgeHttpClient.StreamHandler handler = invocation.getArgument(3);
      handler.handle(new InputStream() {
        @Override
        public int read() {
          return source.read();
        }

        @Override
        public int read(byte[] bytes, int offset, int length) {
          requestedLength[0] = length;
          return source.read(bytes, offset, length);
        }
      });
      return null;
    }).when(jenkinsClient).streamArtifact(eq("job"), eq(1), eq("artifact.bin"), any());

    provider.getContent(artifactInfo("job", "1", "artifact.bin"));
    return requestedLength[0];
  }

  @Test
  public void getContentWrapsBridgeHttpExceptionAsIOException() throws Exception {
    StoredBuildArtifactInfo info = artifactInfo("job", "1", "missing.txt");
    doThrow(new BridgeHttpException("GET", "http://jenkins.instance/missing.txt", 404, "not found"))
        .when(jenkinsClient).streamArtifact(anyString(), anyInt(), anyString(), any());

    try {
      provider.getContent(info);
      fail("Expected an IOException");
    } catch (IOException e) {
      assertTrue(e.getMessage().contains("missing.txt"));
      assertTrue(e.getCause() instanceof BridgeHttpException);
    }
  }

  private static String readAll(InputStream in) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    byte[] buffer = new byte[1024];
    int read;
    while ((read = in.read(buffer)) != -1) {
      out.write(buffer, 0, read);
    }
    return new String(out.toByteArray(), StandardCharsets.UTF_8);
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
}
