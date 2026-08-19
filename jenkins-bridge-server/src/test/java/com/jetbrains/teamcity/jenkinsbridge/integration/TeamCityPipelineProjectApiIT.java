package com.jetbrains.teamcity.jenkinsbridge.integration;

import com.google.gson.JsonObject;
import com.jetbrains.teamcity.jenkinsbridge.model.JenkinsBuildInfo;
import com.jetbrains.teamcity.jenkinsbridge.model.JenkinsPipelineGraph;
import com.jetbrains.teamcity.jenkinsbridge.model.JenkinsPipelineGraphNode;
import com.jetbrains.teamcity.jenkinsbridge.persistence.BuildMirror;
import com.jetbrains.teamcity.jenkinsbridge.persistence.PipelineChainMirror;
import com.jetbrains.teamcity.jenkinsbridge.persistence.PipelineChainNodeMirror;
import com.jetbrains.teamcity.jenkinsbridge.teamcity.TeamCityPipelineChainService;
import jetbrains.buildServer.serverSide.BuildPromotion;
import jetbrains.buildServer.serverSide.BuildPromotionEx;
import jetbrains.buildServer.serverSide.SBuildType;
import jetbrains.buildServer.serverSide.SProject;
import jetbrains.buildServer.serverSide.dependency.DependencyFactory;
import jetbrains.buildServer.serverSide.parameters.ParameterFactory;
import org.testng.annotations.Test;

import java.util.Arrays;
import java.util.Collections;

/** Verifies native pipeline topology through TeamCity's project and promotion internals. */
public class TeamCityPipelineProjectApiIT extends TeamCityIntegrationTestBase {
  @Test
  public void createsLinearChainProjectBuildTypesDependenciesAndPromotionsIdempotently()
      throws Exception {
    ParameterFactory parameterFactory = myFixture.getSingletonService(ParameterFactory.class);
    myBuildType.addConfigParameter(parameterFactory.createSimpleParameter(
        "jenkins.bridge.pipelineChain.enabled", "true"));
    myBuildType.persist();

    BuildMirror mirror = BuildMirror.create(
        "source-job#21", "source-job", buildInfo(21), myBuildType.getExternalId(), "it-now");
    TeamCityPipelineChainService service = new TeamCityPipelineChainService(
        myProjectManager,
        parameterFactory,
        myFixture.getSingletonService(DependencyFactory.class),
        myFixture.getSingletonService(jetbrains.buildServer.serverSide.BuildCustomizerFactory.class));

    JenkinsPipelineGraph graph = linearGraph();
    PipelineChainMirror chain = service.ensureChain(mirror, graph);
    assertNotNull(chain);
    assertTrue(chain.isQueued());
    assertEquals(3, chain.getNodes().size());
    assertEquals(Collections.singletonList("deploy"), chain.getTerminalNodeIds());

    String generatedProjectId = myBuildType.getProject().getExternalId()
        + "_JenkinsBridgeGenerated_virtual";
    SProject generatedProject = myProjectManager.findProjectByExternalId(generatedProjectId);
    assertNotNull(generatedProject);
    assertTrue(generatedProject.isVirtual());

    for (String nodeId : Arrays.asList("build", "test", "deploy")) {
      PipelineChainNodeMirror node = chain.getNode(nodeId);
      assertNotNull(node);
      SBuildType buildType = myProjectManager.findBuildTypeByExternalId(node.getBuildTypeExternalId());
      assertNotNull(buildType);
      assertTrue(buildType.belongsTo(generatedProject));
      assertEquals("true", buildType.getParametersProvider().get("jenkins.bridge.generated.chain"));
      BuildPromotion promotion = myFixture.getBuildPromotionManager().findPromotionById(node.getPromotionId());
      assertNotNull(promotion);
      assertEquals(node.getBuildTypeExternalId(), promotion.getBuildTypeExternalId());
    }

    BuildPromotionEx topPromotion = (BuildPromotionEx) myFixture.getBuildPromotionManager()
        .findPromotionById(chain.getTopPromotionId());
    assertNotNull(topPromotion);
    assertTrue(topPromotion.getDependencies().stream()
        .anyMatch(dependency -> dependency.getDependOn().getBuildTypeExternalId()
            .equals(chain.getNode("deploy").getBuildTypeExternalId())));

    mirror.setPipelineChain(chain);
    PipelineChainMirror second = service.ensureChain(mirror, graph);
    assertSame(chain, second);
    assertEquals(3, generatedProject.getOwnBuildTypes().size());
  }

  private static JenkinsPipelineGraph linearGraph() {
    return JenkinsPipelineGraph.explicit(
        JenkinsPipelineGraph.SOURCE_WFAPI,
        Arrays.asList(
            node("build", "Build", Collections.emptyList(), Collections.singletonList("test")),
            node("test", "Test", Collections.singletonList("build"), Collections.singletonList("deploy")),
            node("deploy", "Deploy", Collections.singletonList("test"), Collections.emptyList())),
        Collections.emptyList());
  }

  private static JenkinsPipelineGraphNode node(String id, String name,
      java.util.List<String> parents, java.util.List<String> children) {
    return new JenkinsPipelineGraphNode(id, "flow-21:" + id, name, "SUCCESS", 0L, 0L,
        parents, children, Collections.emptyList());
  }

  private static JenkinsBuildInfo buildInfo(int number) {
    JsonObject json = new JsonObject();
    json.addProperty("number", number);
    json.addProperty("building", true);
    json.addProperty("url", "http://jenkins/job/source-job/" + number + "/");
    return JenkinsBuildInfo.fromJson(json);
  }
}
