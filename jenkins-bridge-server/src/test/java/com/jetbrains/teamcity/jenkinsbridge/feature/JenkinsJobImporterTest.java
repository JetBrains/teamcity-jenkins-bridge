package com.jetbrains.teamcity.jenkinsbridge.feature;

import com.google.gson.JsonObject;
import com.jetbrains.teamcity.jenkinsbridge.jenkins.JenkinsClient;
import com.jetbrains.teamcity.jenkinsbridge.jenkins.JenkinsJob;
import jetbrains.buildServer.serverSide.Parameter;
import jetbrains.buildServer.serverSide.ProjectManager;
import jetbrains.buildServer.serverSide.SBuildType;
import jetbrains.buildServer.serverSide.SProject;
import jetbrains.buildServer.serverSide.parameters.ParameterFactory;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import java.util.Arrays;
import java.util.Collections;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class JenkinsJobImporterTest {
  private static final String MULTIBRANCH_CLASS =
      "org.jenkinsci.plugins.workflow.multibranch.WorkflowMultiBranchProject";
  private static final String WORKFLOW_JOB_CLASS =
      "org.jenkinsci.plugins.workflow.job.WorkflowJob";

  private final ProjectManager projectManager = mock(ProjectManager.class);
  private final ParameterFactory parameterFactory = mock(ParameterFactory.class);
  private final JenkinsClient jenkinsClient = mock(JenkinsClient.class);
  private final SProject targetProject = mock(SProject.class);
  private final SProject pipelineProject = mock(SProject.class);
  private final SBuildType buildType = mock(SBuildType.class);

  private final JenkinsJobImporter importer =
      new JenkinsJobImporter(projectManager, parameterFactory, jenkinsClient);

  @Before
  public void setUp() {
    when(projectManager.findProjectByExternalId("TeamA")).thenReturn(targetProject);
    when(targetProject.getExternalId()).thenReturn("TeamA");
    when(targetProject.getBuildTypes()).thenReturn(Collections.emptyList());
    when(targetProject.getOwnProjects()).thenReturn(Collections.emptyList());
    when(targetProject.createProject(anyString(), anyString())).thenReturn(pipelineProject);
    when(pipelineProject.getExternalId()).thenReturn("TeamA_pipeline");
    when(pipelineProject.createBuildType(anyString(), anyString())).thenReturn(buildType);
    when(jenkinsClient.jobUrl(anyString())).thenReturn("http://jenkins/job/x/");
    when(parameterFactory.createSimpleParameter(anyString(), anyString()))
        .thenReturn(mock(Parameter.class));
  }

  @Test
  public void importJobsExpandsMultibranchPipelineIntoSubprojectWithOneConfigPerBranch() throws Exception {
    when(jenkinsClient.getJobClass("pipeline")).thenReturn(MULTIBRANCH_CLASS);
    when(jenkinsClient.listJobs("pipeline")).thenReturn(Arrays.asList(
        job("main", "pipeline/main", WORKFLOW_JOB_CLASS),
        job("dev", "pipeline/dev", WORKFLOW_JOB_CLASS)));

    ImportResult result = importer.importJobs("TeamA", Collections.singletonList("pipeline"));

    verify(targetProject).createProject("TeamA_pipeline", "pipeline");

    ArgumentCaptor<String> names = ArgumentCaptor.forClass(String.class);
    verify(pipelineProject, org.mockito.Mockito.times(2)).createBuildType(anyString(), names.capture());
    assertEquals(Arrays.asList("main", "dev"), names.getAllValues());

    ArgumentCaptor<Map<String, String>> params = paramsCaptor();
    verify(buildType, org.mockito.Mockito.times(2))
        .addBuildFeature(eq(BridgeBuildFeatureConstants.TYPE), params.capture());
    for (Map<String, String> featureParams : params.getAllValues()) {
      assertEquals("true", featureParams.get(BridgeBuildFeatureConstants.PARAM_IN_MULTIBRANCH));
      assertTrue(featureParams.get(BridgeBuildFeatureConstants.PARAM_JENKINS_JOB).startsWith("pipeline/"));
    }

    assertEquals(2, result.getCreated().size());
  }

  @Test
  public void importJobsSkipsMultibranchBranchesAlreadyMirrored() throws Exception {
    jetbrains.buildServer.serverSide.SBuildFeatureDescriptor mainFeature = featureFor("pipeline/main");
    SBuildType existing = mock(SBuildType.class);
    when(existing.getBuildFeaturesOfType(BridgeBuildFeatureConstants.TYPE))
        .thenReturn(Collections.singletonList(mainFeature));
    when(targetProject.getBuildTypes()).thenReturn(Collections.singletonList(existing));

    when(jenkinsClient.getJobClass("pipeline")).thenReturn(MULTIBRANCH_CLASS);
    when(jenkinsClient.listJobs("pipeline")).thenReturn(Arrays.asList(
        job("main", "pipeline/main", WORKFLOW_JOB_CLASS),
        job("dev", "pipeline/dev", WORKFLOW_JOB_CLASS)));

    ImportResult result = importer.importJobs("TeamA", Collections.singletonList("pipeline"));

    assertEquals(1, result.getCreated().size());
    assertEquals(1, result.getSkipped().size());
    assertEquals("pipeline/main", result.getSkipped().getFirst().jenkinsJob);
    verify(pipelineProject, org.mockito.Mockito.times(1)).createBuildType(anyString(), eq("dev"));
  }

  private jetbrains.buildServer.serverSide.SBuildFeatureDescriptor featureFor(String jenkinsJob) {
    jetbrains.buildServer.serverSide.SBuildFeatureDescriptor descriptor =
        mock(jetbrains.buildServer.serverSide.SBuildFeatureDescriptor.class);
    when(descriptor.getParameters()).thenReturn(
        Collections.singletonMap(BridgeBuildFeatureConstants.PARAM_JENKINS_JOB, jenkinsJob));
    return descriptor;
  }

  private JenkinsJob job(String name, String fullName, String jenkinsClass) {
    JsonObject json = new JsonObject();
    json.addProperty("name", name);
    json.addProperty("fullName", fullName);
    json.addProperty("_class", jenkinsClass);
    return JenkinsJob.fromJson(json);
  }

  @SuppressWarnings("unchecked")
  private ArgumentCaptor<Map<String, String>> paramsCaptor() {
    return ArgumentCaptor.forClass(Map.class);
  }
}
