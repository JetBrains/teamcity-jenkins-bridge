package com.jetbrains.teamcity.jenkinsbridge.jenkins;

import com.google.gson.JsonObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class JenkinsJobTest {
  @Test
  public void parsesFieldsAndDefaultsFullNameToName() {
    JsonObject json = new JsonObject();
    json.addProperty("name", "my-job");
    json.addProperty("url", "http://jenkins/job/my-job/");
    json.addProperty("_class", "hudson.model.FreeStyleProject");

    JenkinsJob job = JenkinsJob.fromJson(json);

    assertEquals("my-job", job.getName());
    assertEquals("my-job", job.getFullName());
    assertEquals("http://jenkins/job/my-job/", job.getUrl());
    assertEquals("Freestyle project", job.getDisplayType());
    assertTrue(job.isImportable());
  }

  @Test
  public void usesFullNameWhenPresent() {
    JsonObject json = new JsonObject();
    json.addProperty("name", "leaf");
    json.addProperty("fullName", "team/leaf");
    json.addProperty("_class", "org.jenkinsci.plugins.workflow.job.WorkflowJob");

    JenkinsJob job = JenkinsJob.fromJson(json);

    assertEquals("team/leaf", job.getFullName());
    assertTrue(job.isImportable());
  }

  @Test
  public void foldersAndMultibranchAreNotImportable() {
    assertFalse(JenkinsJob.isImportableClass("com.cloudbees.hudson.plugins.folder.Folder"));
    assertFalse(JenkinsJob.isImportableClass("org.jenkinsci.plugins.workflow.multibranch.WorkflowMultiBranchProject"));
    assertFalse(JenkinsJob.isImportableClass("jenkins.branch.OrganizationFolder"));

    assertTrue(JenkinsJob.isImportableClass("hudson.model.FreeStyleProject"));
    assertTrue(JenkinsJob.isImportableClass("hudson.maven.MavenModuleSet"));
    assertTrue(JenkinsJob.isImportableClass("com.example.SomeNewJobPlugin"));
    assertTrue(JenkinsJob.isImportableClass(null));
  }

  @Test
  public void isMultibranchDetectsMultibranchPipelinesOnly() {
    assertTrue(JenkinsJob.isMultibranchClass("org.jenkinsci.plugins.workflow.multibranch.WorkflowMultiBranchProject"));

    assertFalse(JenkinsJob.isMultibranchClass("com.cloudbees.hudson.plugins.folder.Folder"));
    assertFalse(JenkinsJob.isMultibranchClass("jenkins.branch.OrganizationFolder"));
    assertFalse(JenkinsJob.isMultibranchClass("org.jenkinsci.plugins.workflow.job.WorkflowJob"));
    assertFalse(JenkinsJob.isMultibranchClass(null));
  }

  @Test
  public void mapsJenkinsClassesToFriendlyDisplayNames() {
    assertEquals("Pipeline", JenkinsJob.displayType("org.jenkinsci.plugins.workflow.job.WorkflowJob"));
    assertEquals("Multibranch Pipeline",
        JenkinsJob.displayType("org.jenkinsci.plugins.workflow.multibranch.WorkflowMultiBranchProject"));
    assertEquals("Folder", JenkinsJob.displayType("com.cloudbees.hudson.plugins.folder.Folder"));
    assertEquals("SomeNewJobPlugin", JenkinsJob.displayType("com.example.SomeNewJobPlugin"));
    assertEquals("Unknown", JenkinsJob.displayType(null));
  }
}
