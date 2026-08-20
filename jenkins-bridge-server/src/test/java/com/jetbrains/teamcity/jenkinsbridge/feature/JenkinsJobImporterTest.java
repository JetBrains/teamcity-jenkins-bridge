package com.jetbrains.teamcity.jenkinsbridge.feature;

import com.jetbrains.teamcity.jenkinsbridge.jenkins.JenkinsClient;
import com.jetbrains.teamcity.jenkinsbridge.jenkins.JenkinsClientFactory;
import com.jetbrains.teamcity.jenkinsbridge.http.BridgeHttpException;
import com.jetbrains.teamcity.jenkinsbridge.model.JenkinsJobParameters;
import jetbrains.buildServer.parameters.ParametersProvider;
import jetbrains.buildServer.serverSide.Parameter;
import jetbrains.buildServer.serverSide.PersistTask;
import jetbrains.buildServer.serverSide.ProjectManager;
import jetbrains.buildServer.serverSide.SBuildFeatureDescriptor;
import jetbrains.buildServer.serverSide.SBuildType;
import jetbrains.buildServer.serverSide.SProject;
import jetbrains.buildServer.serverSide.parameters.ParameterFactory;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import java.util.Collections;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class JenkinsJobImporterTest {
  private static final String MULTIBRANCH_CLASS =
      "org.jenkinsci.plugins.workflow.multibranch.WorkflowMultiBranchProject";

  private final ProjectManager projectManager = mock(ProjectManager.class);
  private final ParameterFactory parameterFactory = mock(ParameterFactory.class);
  private final JenkinsClient jenkinsClient = mock(JenkinsClient.class);
  private final JenkinsClientFactory jenkinsClientFactory = mock(JenkinsClientFactory.class);
  private final SProject targetProject = mock(SProject.class);
  private final SBuildType buildType = mock(SBuildType.class);
  private final PersistTask persistTask = mock(PersistTask.class);

  private final JenkinsJobImporter importer =
      new JenkinsJobImporter(projectManager, parameterFactory, jenkinsClientFactory);

  @Before
  public void setUp() throws Exception {
    when(jenkinsClientFactory.forConnectionId(targetProject, "conn1")).thenReturn(jenkinsClient);
    when(projectManager.findProjectByExternalId("TeamA")).thenReturn(targetProject);
    when(targetProject.getExternalId()).thenReturn("TeamA");
    when(targetProject.getBuildTypes()).thenReturn(Collections.emptyList());
    when(targetProject.createBuildType(anyString(), anyString())).thenReturn(buildType);
    when(buildType.schedulePersisting(anyString())).thenReturn(persistTask);
    when(jenkinsClient.jobUrl(anyString())).thenReturn("http://jenkins/job/x/");
    when(jenkinsClient.getJobParameters(anyString())).thenReturn(JenkinsJobParameters.empty());
    when(parameterFactory.createSimpleParameter(anyString(), anyString()))
        .thenReturn(mock(Parameter.class));
  }

  @Test
  public void importJobsImportsMultibranchPipelineAsOneConfigWithFlag() throws Exception {
    when(jenkinsClient.getJobClass("pipeline")).thenReturn(MULTIBRANCH_CLASS);

    ImportResult result = importer.importJobs("TeamA", "conn1", Collections.singletonList("pipeline"));

    ArgumentCaptor<Map<String, String>> params = paramsCaptor();
    verify(buildType).addBuildFeature(eq(BridgeBuildFeatureConstants.TYPE), params.capture());
    assertEquals("pipeline", params.getValue().get(BridgeBuildFeatureConstants.PARAM_JENKINS_JOB));
    assertEquals("conn1", params.getValue().get(BridgeBuildFeatureConstants.PARAM_CONNECTION_ID));
    verify(parameterFactory).createSimpleParameter(BridgeBuildFeatureConstants.INTERNAL_MULTIBRANCH_PARAM, "true");
    assertEquals(1, result.getCreated().size());
  }

  @Test
  public void importJobsSkipsAlreadyMirroredJob() throws Exception {
    SBuildFeatureDescriptor existingFeature = mock(SBuildFeatureDescriptor.class);
    when(existingFeature.getParameters()).thenReturn(
        Collections.singletonMap(BridgeBuildFeatureConstants.PARAM_JENKINS_JOB, "pipeline"));
    SBuildType existing = mock(SBuildType.class);
    when(existing.getBuildFeaturesOfType(BridgeBuildFeatureConstants.TYPE))
        .thenReturn(Collections.singletonList(existingFeature));
    when(existing.getParametersProvider()).thenReturn(mock(ParametersProvider.class));
    when(targetProject.getBuildTypes()).thenReturn(Collections.singletonList(existing));
    when(jenkinsClient.getJobClass("pipeline")).thenReturn(MULTIBRANCH_CLASS);

    ImportResult result = importer.importJobs("TeamA", "conn1", Collections.singletonList("pipeline"));

    assertEquals(0, result.getCreated().size());
    assertEquals(1, result.getSkipped().size());
    assertEquals("pipeline", result.getSkipped().getFirst().jenkinsJob);
    assertEquals("already imported", result.getSkipped().getFirst().detail);
    verify(jenkinsClient, never()).getJobParameters("pipeline");
    verify(existing, never()).schedulePersisting(anyString());
  }

  @Test
  public void importJobsRecordsKnownJenkinsFailureForOnlyThatJob() throws Exception {
    when(jenkinsClient.getJobClass("pipeline"))
        .thenThrow(new BridgeHttpException("GET", "http://jenkins/job/pipeline/api/json", 503, "unavailable"));

    ImportResult result = importer.importJobs("TeamA", "conn1", Collections.singletonList("pipeline"));

    assertEquals(1, result.getFailed().size());
    assertEquals("pipeline", result.getFailed().getFirst().jenkinsJob);
    assertEquals(0, result.getCreated().size());
  }

  @Test(expected = NullPointerException.class)
  public void importJobsDoesNotHideUnexpectedRuntimeFailure() throws Exception {
    when(jenkinsClient.getJobClass("pipeline")).thenThrow(new NullPointerException("bridge defect"));

    importer.importJobs("TeamA", "conn1", Collections.singletonList("pipeline"));
  }

  @SuppressWarnings("unchecked")
  private ArgumentCaptor<Map<String, String>> paramsCaptor() {
    return ArgumentCaptor.forClass(Map.class);
  }
}
