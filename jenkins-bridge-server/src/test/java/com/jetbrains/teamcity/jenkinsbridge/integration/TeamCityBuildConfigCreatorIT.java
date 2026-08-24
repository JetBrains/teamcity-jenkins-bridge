package com.jetbrains.teamcity.jenkinsbridge.integration;

import com.jetbrains.teamcity.jenkinsbridge.feature.BridgeBuildFeatureConstants;
import com.jetbrains.teamcity.jenkinsbridge.teamcity.TeamCityBuildConfigCreator;
import jetbrains.buildServer.serverSide.SBuildFeatureDescriptor;
import jetbrains.buildServer.serverSide.SBuildType;
import jetbrains.buildServer.serverSide.parameters.ParameterFactory;
import org.testng.annotations.Test;

import java.util.Collection;
import java.util.Map;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertTrue;

/** Verifies that generated Jenkins mirror configuration survives TeamCity persistence and reload. */
public class TeamCityBuildConfigCreatorIT extends TeamCityIntegrationTestBase {
  @Test
  public void createsPersistedReadOnlyAgentlessMirrorConfiguration() {
    ParameterFactory parameterFactory = myFixture.getSingletonService(ParameterFactory.class);
    TeamCityBuildConfigCreator creator = new TeamCityBuildConfigCreator(parameterFactory);

    String externalId = "jenkinsBridgeCreatorIT";
    SBuildType created = creator.createMirrorBuildType(
        myProject,
        externalId,
        "folder/pipeline",
        "connection-1",
        "http://jenkins.example/job/folder/job/pipeline/",
        "org.jenkinsci.plugins.workflow.multibranch.WorkflowMultiBranchProject",
        true);
    created.schedulePersisting("Jenkins Bridge test: persist generated mirror configuration")
        .awaitUninterruptibly();

    SBuildType reloaded = myProjectManager.findBuildTypeByExternalId(externalId);
    assertNotNull(reloaded);
    assertEquals("true", reloaded.getParameterValue("teamcity.build.agentLess"));
    assertEquals("true", reloaded.getParameterValue("teamcity.ui.settings.readOnly"));
    assertEquals("true", reloaded.getParameterValue(
        BridgeBuildFeatureConstants.INTERNAL_MULTIBRANCH_PARAM));

    Collection<SBuildFeatureDescriptor> features = reloaded.getBuildFeaturesOfType(
        BridgeBuildFeatureConstants.TYPE);
    assertEquals(1, features.size());
    Map<String, String> parameters = features.iterator().next().getParameters();
    assertEquals("connection-1", parameters.get(BridgeBuildFeatureConstants.PARAM_CONNECTION_ID));
    assertEquals("folder/pipeline", parameters.get(BridgeBuildFeatureConstants.PARAM_JENKINS_JOB));
    assertEquals("http://jenkins.example/job/folder/job/pipeline/",
        parameters.get(BridgeBuildFeatureConstants.PARAM_JENKINS_URL));
    assertEquals(
        "org.jenkinsci.plugins.workflow.multibranch.WorkflowMultiBranchProject",
        parameters.get(BridgeBuildFeatureConstants.PARAM_JENKINS_TYPE));
    assertTrue(reloaded.belongsTo(myProject));
  }
}
