package com.jetbrains.teamcity.jenkinsbridge.feature;

import com.google.gson.JsonParser;
import com.jetbrains.teamcity.jenkinsbridge.http.BridgeHttpException;
import com.jetbrains.teamcity.jenkinsbridge.jenkins.JenkinsClient;
import com.jetbrains.teamcity.jenkinsbridge.model.JenkinsJobParameters;
import com.jetbrains.teamcity.jenkinsbridge.persistence.BuildMirrorStore;
import com.jetbrains.teamcity.jenkinsbridge.settings.MirroredChildJob;
import com.jetbrains.teamcity.jenkinsbridge.settings.MirroredJob;
import jetbrains.buildServer.serverSide.ProjectManager;
import org.junit.Test;

import static com.jetbrains.teamcity.jenkinsbridge.persistence.BuildMirrorStoreTest.buildMockProjectManager;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class JenkinsParameterDefinitionServiceTest {
  @Test
  public void childrenRetainDifferentDefinitionsAcrossReload() throws Exception {
    ProjectManager manager = buildMockProjectManager();
    JenkinsParameterDefinitionService service = new JenkinsParameterDefinitionService(new BuildMirrorStore(manager));
    JenkinsClient client = client("http://jenkins");
    MirroredChildJob main = child("type", "job", "job/main");
    MirroredChildJob feature = child("type", "job", "job/feature%2Flogin");
    when(client.getJobParameters(main.jenkinsJob())).thenReturn(definitions("RELEASE"));
    when(client.getJobParameters(feature.jenkinsJob())).thenReturn(definitions("DEBUG"));

    assertTrue(service.getCachedChild(client, main).isEmpty());
    assertNull(service.refreshChild(client, main).previousSnapshot());
    service.refreshChild(client, feature);

    JenkinsParameterDefinitionService reloaded = new JenkinsParameterDefinitionService(new BuildMirrorStore(manager));
    assertEquals(definitions("RELEASE").snapshot(), reloaded.getCachedChild(client, main).orElseThrow().getSnapshot());
    assertEquals(definitions("DEBUG").snapshot(), reloaded.getCachedChild(client, feature).orElseThrow().getSnapshot());
    assertEquals("false", reloaded.getCachedChild(client, main).orElseThrow().getDefinitions()
        .getParameters().getFirst().getDefaultValue());
    assertEquals(2, reloaded.getCachedChild(client, main).orElseThrow().getDefinitions()
        .getParameters().getFirst().getChoices().size());
  }

  @Test
  public void cacheIsScopedToControllerMappingParentAndChild() throws Exception {
    JenkinsParameterDefinitionService service = new JenkinsParameterDefinitionService(new BuildMirrorStore(buildMockProjectManager()));
    JenkinsClient first = client("http://one");
    JenkinsClient second = client("http://two");
    MirroredChildJob selected = child("type", "job", "job/main");
    when(first.getJobParameters("job/main")).thenReturn(definitions("RELEASE"));
    service.refreshChild(first, selected);

    assertTrue(service.getCachedChild(second, selected).isEmpty());
    assertTrue(service.getCachedChild(first, child("other", "job", "job/main")).isEmpty());
    assertTrue(service.getCachedChild(first, child("type", "other-parent", "job/main")).isEmpty());
    assertTrue(service.getCachedChild(first, child("type", "job", "job/other")).isEmpty());
  }

  @Test
  public void failedRefreshPreservesCacheAndSuccessfulEmptySchemaReplacesIt() throws Exception {
    JenkinsParameterDefinitionService service = new JenkinsParameterDefinitionService(new BuildMirrorStore(buildMockProjectManager()));
    JenkinsClient client = client("http://jenkins");
    MirroredChildJob child = child("type", "job", "job/main");
    when(client.getJobParameters("job/main")).thenReturn(definitions("RELEASE"));
    service.refreshChild(client, child);
    when(client.getJobParameters("job/main")).thenThrow(new BridgeHttpException("GET", "job/main", 503, "unavailable"));
    try {
      service.refreshChild(client, child);
      fail("Expected Jenkins failure");
    } catch (BridgeHttpException expected) {
      assertEquals(definitions("RELEASE").snapshot(), service.getCachedChild(client, child).orElseThrow().getSnapshot());
    }

    doReturn(JenkinsJobParameters.empty()).when(client).getJobParameters("job/main");
    JenkinsParameterDefinitionService.RefreshResult result = service.refreshChild(client, child);
    assertEquals(definitions("RELEASE").snapshot(), result.previousSnapshot());
    assertFalse(service.getCachedChild(client, child).orElseThrow().getDefinitions().isParameterized());
  }

  public static JenkinsJobParameters definitions(String name) {
    return JenkinsJobParameters.fromJson(new JsonParser().parse("{\"property\":[{\"parameterDefinitions\":["
        + "{\"name\":\"" + name + "\",\"type\":\"ChoiceParameterDefinition\","
        + "\"defaultParameterValue\":{\"value\":\"false\"},\"choices\":[\"false\",\"true\"]}]}]}").getAsJsonObject());
  }

  private static JenkinsClient client(String controller) {
    JenkinsClient client = mock(JenkinsClient.class);
    when(client.getControllerIdentity()).thenReturn(controller);
    return client;
  }

  private static MirroredChildJob child(String owner, String parent, String job) {
    return new MirroredChildJob(new MirroredJob("conn", parent, owner, "Build", 1, true), job);
  }
}
