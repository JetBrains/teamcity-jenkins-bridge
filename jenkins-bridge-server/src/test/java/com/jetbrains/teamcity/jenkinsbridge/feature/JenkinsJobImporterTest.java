package com.jetbrains.teamcity.jenkinsbridge.feature;

import com.jetbrains.teamcity.jenkinsbridge.jenkins.JenkinsClient;
import jetbrains.buildServer.serverSide.Parameter;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class JenkinsJobImporterTest {
  private static final String MULTIBRANCH_CLASS =
      "org.jenkinsci.plugins.workflow.multibranch.WorkflowMultiBranchProject";

  private final ProjectManager projectManager = mock(ProjectManager.class);
  private final ParameterFactory parameterFactory = mock(ParameterFactory.class);
  private final JenkinsClient jenkinsClient = mock(JenkinsClient.class);
  private final SProject targetProject = mock(SProject.class);
  private final SBuildType buildType = mock(SBuildType.class);

  private final JenkinsJobImporter importer =
      new JenkinsJobImporter(projectManager, parameterFactory, jenkinsClient);

  @Before
  public void setUp() {
    when(projectManager.findProjectByExternalId("TeamA")).thenReturn(targetProject);
    when(targetProject.getExternalId()).thenReturn("TeamA");
    when(targetProject.getBuildTypes()).thenReturn(Collections.emptyList());
    when(targetProject.createBuildType(anyString(), anyString())).thenReturn(buildType);
    when(jenkinsClient.jobUrl(anyString())).thenReturn("http://jenkins/job/x/");
    when(parameterFactory.createSimpleParameter(anyString(), anyString()))
        .thenReturn(mock(Parameter.class));
  }

  @Test
  public void importJobsImportsMultibranchPipelineAsOneConfigWithFlag() throws Exception {
    when(jenkinsClient.getJobClass("pipeline")).thenReturn(MULTIBRANCH_CLASS);

    ImportResult result = importer.importJobs("TeamA", Collections.singletonList("pipeline"));

    ArgumentCaptor<Map<String, String>> params = paramsCaptor();
    verify(buildType).addBuildFeature(eq(BridgeBuildFeatureConstants.TYPE), params.capture());
    assertEquals("true", params.getValue().get(BridgeBuildFeatureConstants.PARAM_IN_MULTIBRANCH));
    assertEquals("pipeline", params.getValue().get(BridgeBuildFeatureConstants.PARAM_JENKINS_JOB));
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
    when(targetProject.getBuildTypes()).thenReturn(Collections.singletonList(existing));
    when(jenkinsClient.getJobClass("pipeline")).thenReturn(MULTIBRANCH_CLASS);

    ImportResult result = importer.importJobs("TeamA", Collections.singletonList("pipeline"));

    assertEquals(0, result.getCreated().size());
    assertEquals(1, result.getSkipped().size());
    assertEquals("pipeline", result.getSkipped().getFirst().jenkinsJob);
  }

  @SuppressWarnings("unchecked")
  private ArgumentCaptor<Map<String, String>> paramsCaptor() {
    return ArgumentCaptor.forClass(Map.class);
  }
}
