package com.jetbrains.teamcity.jenkinsbridge.teamcity;

import com.google.gson.JsonObject;
import com.jetbrains.teamcity.jenkinsbridge.model.GraphConfidence;
import com.jetbrains.teamcity.jenkinsbridge.model.JenkinsBuildInfo;
import com.jetbrains.teamcity.jenkinsbridge.model.JenkinsPipelineGraph;
import com.jetbrains.teamcity.jenkinsbridge.model.JenkinsPipelineGraphNode;
import com.jetbrains.teamcity.jenkinsbridge.persistence.BuildMirror;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class PipelineChainPlannerTest {
  private final PipelineChainPlanner planner = new PipelineChainPlanner();

  @Test
  public void planCreatesTeamCityNodesForParallelGraph() {
    BuildMirror mirror = BuildMirror.create(
        "TestTc_JenkinsTcTest::tc-test#33",
        "tc-test",
        buildInfo(33),
        "TestTc_JenkinsTcTest",
        "now");

    PipelineChainPlan plan = planner.plan(mirror, "TestTc_JenkinsTcTest", parallelGraph(GraphConfidence.COLLAPSED));

    assertEquals("TestTc_JenkinsTcTest", plan.getSourceBuildTypeExternalId());
    assertEquals("TestTc_JenkinsTcTest::tc-test#33", plan.getJenkinsBuildKey());
    assertTrue(plan.getTopBuildTypeExternalId().startsWith("TestTc_JenkinsTcTest_JenkinsFlow"));
    assertTrue(plan.getTopBuildTypeExternalId().endsWith("Top"));
    assertEquals(5, plan.getNodes().size());
    assertEquals(Arrays.asList("5"), plan.getTerminalNodeIds());

    PipelineChainPlan.Node linux = plan.findNode("3");
    PipelineChainPlan.Node windows = plan.findNode("4");
    PipelineChainPlan.Node deploy = plan.findNode("5");

    assertNotNull(linux);
    assertNotNull(windows);
    assertNotNull(deploy);
    assertEquals(Arrays.asList("2"), linux.getParentNodeIds());
    assertEquals(Arrays.asList("2"), windows.getParentNodeIds());
    assertEquals(Arrays.asList("3", "4"), deploy.getParentNodeIds());
    assertTrue(linux.getBuildTypeExternalId().startsWith("TestTc_JenkinsTcTest_JenkinsFlow"));
    assertTrue(linux.getBuildTypeExternalId().endsWith("3"));
  }

  @Test
  public void explicitCollapsedAndLinearGraphsAreEligibleForNativeChains() {
    assertTrue(planner.canCreateNativeChain(parallelGraph(GraphConfidence.EXPLICIT)));
    assertTrue(planner.canCreateNativeChain(parallelGraph(GraphConfidence.COLLAPSED)));
    assertTrue(planner.canCreateNativeChain(parallelGraph(GraphConfidence.LINEAR_FALLBACK)));
    assertFalse(planner.canCreateNativeChain(parallelGraph(GraphConfidence.UNAVAILABLE)));
    assertFalse(planner.canCreateNativeChain(JenkinsPipelineGraph.unavailable(Collections.singletonList("404"))));
  }

  private static JenkinsPipelineGraph parallelGraph(GraphConfidence confidence) {
    return new JenkinsPipelineGraph(
        true,
        JenkinsPipelineGraph.SOURCE_WFAPI,
        Arrays.asList(
            node("1", "Setup", Collections.<String>emptyList(), Arrays.asList("2")),
            node("2", "Parallel Tests", Arrays.asList("1"), Arrays.asList("3", "4")),
            node("3", "Linux Tests", Arrays.asList("2"), Arrays.asList("5")),
            node("4", "Windows Tests", Arrays.asList("2"), Arrays.asList("5")),
            node("5", "Deploy", Arrays.asList("3", "4"), Collections.<String>emptyList())
        ),
        "topology-hash",
        confidence,
        Collections.<String>emptyList());
  }

  private static JenkinsPipelineGraphNode node(
      String id,
      String name,
      java.util.List<String> parents,
      java.util.List<String> children
  ) {
    return new JenkinsPipelineGraphNode(
        id,
        "job#33:" + id,
        name,
        "SUCCESS",
        0L,
        0L,
        parents,
        children,
        Collections.<String>emptyList());
  }

  private static JenkinsBuildInfo buildInfo(int number) {
    JsonObject json = new JsonObject();
    json.addProperty("number", number);
    json.addProperty("building", true);
    json.addProperty("url", "http://jenkins/job/tc-test/" + number + "/");
    return JenkinsBuildInfo.fromJson(json);
  }
}
