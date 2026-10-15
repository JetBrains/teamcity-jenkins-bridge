package com.jetbrains.teamcity.jenkinsbridge.web;

import com.google.gson.JsonParser;
import com.jetbrains.teamcity.jenkinsbridge.jenkins.JenkinsClient;
import com.jetbrains.teamcity.jenkinsbridge.jenkins.JenkinsJob;
import jetbrains.buildServer.web.openapi.WebControllerManager;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class JenkinsMultibranchChildrenControllerTest {
  @Test
  public void returnsOnlyRunnableChildrenWithCanonicalPathsAndDisplayNames() throws Exception {
    JenkinsClient client = mock(JenkinsClient.class);
    when(client.listJobs("team/pipeline")).thenReturn(List.of(
        job("main", "team/pipeline/main", "main", "org.jenkinsci.plugins.workflow.job.WorkflowJob", true),
        job("feature", "team/pipeline/feature%2Flogin", "feature/login",
            "org.jenkinsci.plugins.workflow.job.WorkflowJob", true),
        job("disabled", "team/pipeline/disabled", "disabled",
            "org.jenkinsci.plugins.workflow.job.WorkflowJob", false),
        job("nested", "team/pipeline/nested/folder", "folder",
            "com.cloudbees.hudson.plugins.folder.Folder", true),
        job("unrelated", "team/pipeline-other/main", "main",
            "org.jenkinsci.plugins.workflow.job.WorkflowJob", true)));

    List<JenkinsMultibranchChildrenController.ChildOption> result =
        new JenkinsMultibranchChildrenController(mock(WebControllerManager.class), null, null).runnableChildren(client, "team/pipeline");

    assertEquals(2, result.size());
    assertEquals("main", result.get(0).name);
    assertEquals("team/pipeline/main", result.get(0).fullName);
    assertEquals("feature/login", result.get(1).name);
    assertEquals("team/pipeline/feature%2Flogin", result.get(1).fullName);
  }

  private static JenkinsJob job(String name, String fullName, String displayName, String type, boolean buildable) {
    return JenkinsJob.fromJson(new JsonParser().parse("{\"name\":\"" + name
        + "\",\"fullName\":\"" + fullName + "\",\"displayName\":\"" + displayName
        + "\",\"_class\":\"" + type + "\",\"buildable\":" + buildable + "}").getAsJsonObject());
  }
}
