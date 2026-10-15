package com.jetbrains.teamcity.jenkinsbridge.jenkins;

import com.jetbrains.teamcity.jenkinsbridge.connection.JenkinsConnectionResolver;
import com.jetbrains.teamcity.jenkinsbridge.feature.BridgeBuildFeatureConstants;
import com.jetbrains.teamcity.jenkinsbridge.http.BridgeHttpClient;
import com.jetbrains.teamcity.jenkinsbridge.model.JenkinsMultibranchScmSnapshot;
import com.jetbrains.teamcity.jenkinsbridge.vcs.VcsRefType;
import com.jetbrains.teamcity.jenkinsbridge.xml.JaxbUnmarshaller;
import jetbrains.buildServer.serverSide.SBuildFeatureDescriptor;
import jetbrains.buildServer.serverSide.SBuildType;
import org.junit.Before;
import org.junit.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class JenkinsClientFactoryTest {
  private final SBuildType type = mock(SBuildType.class);
  private final SBuildFeatureDescriptor feature = mock(SBuildFeatureDescriptor.class);
  private final JenkinsClient client = mock(JenkinsClient.class);
  private final JenkinsClientFactory factory = spy(new JenkinsClientFactory(mock(BridgeHttpClient.class),
      mock(JaxbUnmarshaller.class), mock(JenkinsConnectionResolver.class)));

  @Before
  public void setUp() {
    when(type.getBuildFeaturesOfType(BridgeBuildFeatureConstants.TYPE)).thenReturn(List.of(feature));
    when(feature.getParameters()).thenReturn(Map.of(BridgeBuildFeatureConstants.PARAM_JENKINS_JOB, "parent"));
    doReturn(client).when(factory).forBuildType(type);
  }

  @Test
  public void eachLookupReadsCurrentDefaultFromJenkins() throws Exception {
    when(client.getMultibranchScmSnapshot("parent")).thenReturn(snapshot("main"), snapshot("deb"));

    assertEquals(Optional.of("refs/heads/main"), factory.getMultibranchScmSnapshot(type)
        .flatMap(data -> data.defaultRef("https://github.com/org/repo.git")));
    assertEquals(Optional.of("refs/heads/deb"), factory.getMultibranchScmSnapshot(type)
        .flatMap(data -> data.defaultRef("https://github.com/org/repo.git")));
    verify(client, times(2)).getMultibranchScmSnapshot("parent");
  }

  @Test
  public void failedLookupReturnsEmptyAfterAnEarlierSuccess() throws Exception {
    when(client.getMultibranchScmSnapshot("parent")).thenReturn(snapshot("main"))
        .thenThrow(new IllegalStateException("Jenkins unavailable"));

    assertTrue(factory.getMultibranchScmSnapshot(type).isPresent());
    assertTrue(factory.getMultibranchScmSnapshot(type).isEmpty());
    verify(client, times(2)).getMultibranchScmSnapshot("parent");
  }

  @Test
  public void changedParentIsReadOnNextLookup() throws Exception {
    when(client.getMultibranchScmSnapshot("parent")).thenReturn(snapshot("main"));
    when(client.getMultibranchScmSnapshot("another-parent")).thenReturn(snapshot("deb"));
    assertTrue(factory.getMultibranchScmSnapshot(type).isPresent());
    when(feature.getParameters()).thenReturn(Map.of(BridgeBuildFeatureConstants.PARAM_JENKINS_JOB, " another-parent "));

    assertEquals(Optional.of("refs/heads/deb"), factory.getMultibranchScmSnapshot(type)
        .flatMap(data -> data.defaultRef("https://github.com/org/repo.git")));
    verify(client).getMultibranchScmSnapshot("another-parent");
  }

  @Test
  public void missingBridgeFeatureDoesNotRequestMetadata() {
    when(type.getBuildFeaturesOfType(BridgeBuildFeatureConstants.TYPE)).thenReturn(List.of());
    assertTrue(factory.getMultibranchScmSnapshot(type).isEmpty());
    verifyNoInteractions(client);
  }

  @Test
  public void missingOrBlankParentDoesNotRequestMetadata() {
    when(feature.getParameters()).thenReturn(Map.of());
    assertTrue(factory.getMultibranchScmSnapshot(type).isEmpty());
    when(feature.getParameters()).thenReturn(Map.of(BridgeBuildFeatureConstants.PARAM_JENKINS_JOB, " "));
    assertTrue(factory.getMultibranchScmSnapshot(type).isEmpty());
    verifyNoInteractions(client);
  }

  private JenkinsMultibranchScmSnapshot snapshot(String head) {
    return new JenkinsMultibranchScmSnapshot(List.of(new JenkinsMultibranchScmSnapshot.Child(
        "parent/" + head, head, VcsRefType.HEADS, "https://github.com/org/repo.git", true, false)));
  }
}
