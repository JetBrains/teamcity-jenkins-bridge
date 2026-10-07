package com.jetbrains.teamcity.jenkinsbridge.feature;

import com.jetbrains.teamcity.jenkinsbridge.jenkins.JenkinsClient;
import com.jetbrains.teamcity.jenkinsbridge.jenkins.JenkinsClientFactory;
import com.jetbrains.teamcity.jenkinsbridge.persistence.BuildMirrorStore;
import jetbrains.buildServer.serverSide.Parameter;
import jetbrains.buildServer.serverSide.SBuildType;
import jetbrains.buildServer.serverSide.parameters.ParameterFactory;
import org.junit.Test;

import java.util.Set;

import static com.jetbrains.teamcity.jenkinsbridge.persistence.BuildMirrorStoreTest.buildMockProjectManager;
import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class JenkinsParameterSynchronizerTest {
  @Test
  public void regularJobRetainsLegacySnapshotComparisonAndReconciliation() throws Exception {
    BuildMirrorStore store = new BuildMirrorStore(buildMockProjectManager());
    store.saveImportedJenkinsParameterNames("type", Set.of("OLD"));
    store.saveImportedJenkinsParameterSnapshot("type", "legacy-schema");
    JenkinsClient client = mock(JenkinsClient.class);
    when(client.getControllerIdentity()).thenReturn("http://jenkins");
    when(client.getJobParameters("job")).thenReturn(JenkinsParameterDefinitionServiceTest.definitions("RELEASE"));
    SBuildType buildType = mock(SBuildType.class);
    when(buildType.getExternalId()).thenReturn("type");
    when(buildType.getParametersProvider()).thenReturn(mock(jetbrains.buildServer.parameters.ParametersProvider.class));
    ParameterFactory factory = mock(ParameterFactory.class);
    when(factory.createTypedParameter(anyString(), anyString(), anyString())).thenReturn(mock(Parameter.class));
    JenkinsParameterSynchronizer synchronizer = new JenkinsParameterSynchronizer(mock(JenkinsClientFactory.class),
        factory, store, new JenkinsParameterDefinitionService(store));

    JenkinsParameterSynchronizer.SynchronizationResult result = synchronizer.synchronize(client, buildType, "job");

    assertTrue(result.isDefinitionChanged());
    assertEquals("legacy-schema", result.getPreviousSnapshot());
    assertEquals(1, result.getSynchronizedCount());
    verify(buildType).removeParameter("OLD");
    verify(buildType).addParameter(any(Parameter.class));
    assertEquals(Set.of("RELEASE"), store.getImportedJenkinsParameterNames("type"));
    assertEquals(result.getCurrentSnapshot(), store.getImportedJenkinsParameterSnapshot("type"));
  }

  @Test
  public void sharedMultibranchConfigurationCannotBeReconciled() throws Exception {
    SBuildType buildType = mock(SBuildType.class);
    when(buildType.getParameterValue(BridgeBuildFeatureConstants.INTERNAL_MULTIBRANCH_PARAM)).thenReturn("true");
    JenkinsClient client = mock(JenkinsClient.class);
    JenkinsParameterSynchronizer synchronizer = new JenkinsParameterSynchronizer(mock(JenkinsClientFactory.class),
        mock(ParameterFactory.class), mock(BuildMirrorStore.class), mock(JenkinsParameterDefinitionService.class));
    try {
      synchronizer.synchronize(client, buildType, "job/main");
      fail("Expected child-service guard");
    } catch (IllegalArgumentException expected) {
      verifyNoInteractions(client);
      verify(buildType, never()).addParameter(any(Parameter.class));
      verify(buildType, never()).removeParameter(anyString());
    }
  }
}
