package com.jetbrains.teamcity.jenkinsbridge.feature;

import com.google.gson.JsonParser;
import com.jetbrains.teamcity.jenkinsbridge.jenkins.JenkinsClient;
import com.jetbrains.teamcity.jenkinsbridge.model.JenkinsJobParameters;
import com.jetbrains.teamcity.jenkinsbridge.persistence.BuildMirrorStore;
import jetbrains.buildServer.serverSide.ProjectManager;
import org.junit.Test;

import static com.jetbrains.teamcity.jenkinsbridge.persistence.BuildMirrorStoreTest.buildMockProjectManager;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class JenkinsParameterDefinitionServiceTest {
  @Test
  public void refreshReturnsPreviousRegularJobSnapshot() throws Exception {
    ProjectManager manager = buildMockProjectManager();
    JenkinsParameterDefinitionService service = new JenkinsParameterDefinitionService(new BuildMirrorStore(manager));
    JenkinsClient client = client("http://jenkins");
    when(client.getJobParameters("job")).thenReturn(definitions("RELEASE"), definitions("DEBUG"));

    JenkinsParameterDefinitionService.RefreshResult initial = service.refresh(client, "type", "job");
    assertNull(initial.previousSnapshot());
    assertEquals(definitions("RELEASE").snapshot(), initial.metadata().getSnapshot());

    JenkinsParameterDefinitionService.RefreshResult refreshed = service.refresh(client, "type", "job");
    assertEquals(definitions("RELEASE").snapshot(), refreshed.previousSnapshot());
    assertEquals(definitions("DEBUG").snapshot(), refreshed.metadata().getSnapshot());
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
}
