package com.jetbrains.teamcity.jenkinsbridge.teamcity;

import com.jetbrains.teamcity.jenkinsbridge.artifactstorage.JenkinsStorageAutomaticActivator;
import com.jetbrains.teamcity.jenkinsbridge.model.JenkinsArtifact;
import jetbrains.buildServer.ArtifactsConstants;
import jetbrains.buildServer.artifacts.util.ArtifactListUtil;
import jetbrains.buildServer.artifacts.util.SerializableArtifactListData;
import jetbrains.buildServer.serverSide.RunningBuildEx;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class TeamCityArtifactPublisherTest {
  @Test
  public void publishesToResolvedRunningBuild() throws Exception {
    CapturingRunningBuild capture = new CapturingRunningBuild();
    TeamCityArtifactPublisher publisher = new TeamCityArtifactPublisher(new FixedLocator(capture.proxy()), null);

    publisher.publishArtifact(42L, "jenkins-artifacts/out.txt",
        new ByteArrayInputStream("hello".getBytes("UTF-8")));

    assertEquals("jenkins-artifacts/out.txt", capture.path);
    assertEquals("hello", capture.content);
  }

  @Test(expected = java.io.IOException.class)
  public void failsWhenBuildIsNoLongerRunning() throws Exception {
    TeamCityArtifactPublisher publisher = new TeamCityArtifactPublisher(new FixedLocator(null), null);

    publisher.publishArtifact(42L, "jenkins-artifacts/out.txt",
        new ByteArrayInputStream("hello".getBytes("UTF-8")));
  }

  @Test
  public void publishArtifactListWritesSerializedArtifactsWhenStorageActivated() throws Exception {
    RunningBuildEx runningBuild = mock(RunningBuildEx.class);
    when(runningBuild.getProjectExternalId()).thenReturn("Project1");
    JenkinsStorageAutomaticActivator activator = mock(JenkinsStorageAutomaticActivator.class);
    when(activator.activateJenkinsStorage("Project1")).thenReturn("STORAGE-1");
    TeamCityArtifactPublisher publisher =
        new TeamCityArtifactPublisher(new FixedLocator(runningBuild), activator);

    List<JenkinsArtifact> artifacts = Arrays.asList(
        new JenkinsArtifact("app.jar", "target/app.jar", 100),
        new JenkinsArtifact("report.txt", "reports/report.txt", 50));

    publisher.publishArtifactList(42L, artifacts);

    ArgumentCaptor<byte[]> captor = ArgumentCaptor.forClass(byte[].class);
    verify(runningBuild).publishArtifact(eq(ArtifactsConstants.ARTIFACT_LIST_PATH), captor.capture());

    SerializableArtifactListData listData =
        ArtifactListUtil.readArtifactList(new ByteArrayInputStream(captor.getValue()));
    assertEquals("STORAGE-1", listData.getStorageSettingsId());
    assertEquals(2, listData.getArtifactList().size());
    assertEquals("target/app.jar", listData.getArtifactList().get(0).getPath());
    assertEquals(100L, listData.getArtifactList().get(0).getSize());
    assertEquals("reports/report.txt", listData.getArtifactList().get(1).getPath());
  }

  @Test
  public void publishArtifactListSkipsWhenProjectExternalIdIsMissing() throws Exception {
    RunningBuildEx runningBuild = mock(RunningBuildEx.class);
    when(runningBuild.getProjectExternalId()).thenReturn(null);
    JenkinsStorageAutomaticActivator activator = mock(JenkinsStorageAutomaticActivator.class);
    TeamCityArtifactPublisher publisher =
        new TeamCityArtifactPublisher(new FixedLocator(runningBuild), activator);

    publisher.publishArtifactList(42L, Arrays.asList(new JenkinsArtifact("a.txt", "a.txt", 1)));

    verify(activator, never()).activateJenkinsStorage(anyString());
    verify(runningBuild, never()).publishArtifact(anyString(), any(byte[].class));
  }

  @Test
  public void publishArtifactListSkipsWhenStorageCouldNotBeActivated() throws Exception {
    RunningBuildEx runningBuild = mock(RunningBuildEx.class);
    when(runningBuild.getProjectExternalId()).thenReturn("Project1");
    JenkinsStorageAutomaticActivator activator = mock(JenkinsStorageAutomaticActivator.class);
    when(activator.activateJenkinsStorage("Project1")).thenReturn(null);
    TeamCityArtifactPublisher publisher =
        new TeamCityArtifactPublisher(new FixedLocator(runningBuild), activator);

    publisher.publishArtifactList(42L, Arrays.asList(new JenkinsArtifact("a.txt", "a.txt", 1)));

    verify(runningBuild, never()).publishArtifact(anyString(), any(byte[].class));
  }

  @Test
  public void publishArtifactListDoesNothingForEmptyArtifactListEvenAfterActivation() throws Exception {
    RunningBuildEx runningBuild = mock(RunningBuildEx.class);
    when(runningBuild.getProjectExternalId()).thenReturn("Project1");
    JenkinsStorageAutomaticActivator activator = mock(JenkinsStorageAutomaticActivator.class);
    when(activator.activateJenkinsStorage("Project1")).thenReturn("STORAGE-1");
    TeamCityArtifactPublisher publisher =
        new TeamCityArtifactPublisher(new FixedLocator(runningBuild), activator);

    publisher.publishArtifactList(42L, Collections.<JenkinsArtifact>emptyList());

    verify(activator).activateJenkinsStorage("Project1");
    verify(runningBuild, never()).publishArtifact(anyString(), any(byte[].class));
  }

  @Test(expected = java.io.IOException.class)
  public void publishArtifactListFailsWhenBuildIsNoLongerRunning() throws Exception {
    TeamCityArtifactPublisher publisher =
        new TeamCityArtifactPublisher(new FixedLocator(null), mock(JenkinsStorageAutomaticActivator.class));

    publisher.publishArtifactList(42L, Arrays.asList(new JenkinsArtifact("a.txt", "a.txt", 1)));
  }

  private static class FixedLocator extends TeamCityRunningBuildLocator {
    private final RunningBuildEx runningBuild;

    FixedLocator(RunningBuildEx runningBuild) {
      super(null, null);
      this.runningBuild = runningBuild;
    }

    @Override
    public RunningBuildEx findRunningBuild(long id) {
      return runningBuild;
    }
  }

  private static class CapturingRunningBuild implements InvocationHandler {
    String path;
    String content;

    RunningBuildEx proxy() {
      return (RunningBuildEx) Proxy.newProxyInstance(
          RunningBuildEx.class.getClassLoader(),
          new Class<?>[]{RunningBuildEx.class},
          this);
    }

    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
      if ("publishArtifact".equals(method.getName())
          && args != null
          && args.length == 2
          && args[0] instanceof String
          && args[1] instanceof InputStream) {
        path = (String) args[0];
        content = read((InputStream) args[1]);
        return null;
      }
      if ("toString".equals(method.getName())) {
        return "capturing-running-build";
      }
      if ("hashCode".equals(method.getName())) {
        return Integer.valueOf(1);
      }
      if ("equals".equals(method.getName())) {
        return Boolean.valueOf(proxy == args[0]);
      }
      Class<?> returnType = method.getReturnType();
      if (returnType.equals(Boolean.TYPE)) {
        return Boolean.FALSE;
      }
      if (returnType.equals(Integer.TYPE)) {
        return Integer.valueOf(0);
      }
      if (returnType.equals(Long.TYPE)) {
        return Long.valueOf(0L);
      }
      return null;
    }

    private String read(InputStream inputStream) throws Exception {
      StringBuilder result = new StringBuilder();
      int read;
      while ((read = inputStream.read()) != -1) {
        result.append((char) read);
      }
      return result.toString();
    }
  }
}
