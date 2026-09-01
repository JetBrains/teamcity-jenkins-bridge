package com.jetbrains.teamcity.jenkinsbridge.model;

import com.google.gson.JsonParser;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

public class JenkinsPipelineGraphTest {
  private final JsonParser parser = new JsonParser();

  @Test
  public void buildsLinearFallbackWhenNoParentsExist() {
    JenkinsPipelineGraph graph = graph("job#1", stages(
        stage("1", "Build", "SUCCESS"),
        stage("2", "Test", "SUCCESS"),
        stage("3", "Deploy", "SUCCESS")), Collections.<JenkinsWfapiNode>emptyList());

    assertEquals(GraphConfidence.LINEAR_FALLBACK, graph.getConfidence());
    assertEquals(Collections.<String>emptyList(), graph.getNodes().get(0).getParentIds());
    assertEquals(Arrays.asList("1"), graph.getNodes().get(1).getParentIds());
    assertEquals(Arrays.asList("2"), graph.getNodes().get(2).getParentIds());
    assertTrue(graph.getDiagnostics().get(0).contains("using previous stage"));
  }

  @Test
  public void collapsesParallelBranchContainersToStageEdges() {
    JenkinsStages stages = stages(
        stage("1", "Setup", "SUCCESS"),
        stage("2", "Test", "SUCCESS"),
        stage("5", "Linux", "SUCCESS"),
        stage("6", "Windows", "SUCCESS"),
        stage("9", "Deploy", "SUCCESS"));
    List<JenkinsWfapiNode> raw = Arrays.asList(
        raw("2", "Test", parents("1")),
        raw("3", "Branch: Linux", parents("2")),
        raw("4", "Branch: Windows", parents("2")),
        raw("5", "Linux", parents("3")),
        raw("6", "Windows", parents("4")),
        raw("8", "Join", parents("5", "6")),
        raw("9", "Deploy", parents("8")));

    JenkinsPipelineGraph graph = graph("job#7", stages, raw);

    assertEquals(GraphConfidence.COLLAPSED, graph.getConfidence());
    assertEquals(Arrays.asList("2"), node(graph, "5").getParentIds());
    assertEquals(Arrays.asList("2"), node(graph, "6").getParentIds());
    assertEquals(Arrays.asList("5", "6"), node(graph, "9").getParentIds());
    assertEquals(Arrays.asList("5", "6"), node(graph, "2").getChildIds());
  }

  @Test
  public void overlappingStageTimingsRemainLinearFallbackWithoutExplicitEdges() {
    JenkinsPipelineGraph graph = graph("job#36", stages(
        timedStage("6", "Setup", "SUCCESS", 1000, 400),
        timedStage("12", "Parallel Tests", "SUCCESS", 1500, 100),
        timedStage("20", "Linux Tests", "SUCCESS", 1600, 3900),
        timedStage("22", "Windows Tests", "SUCCESS", 1650, 2400),
        timedStage("24", "Mac Tests", "SUCCESS", 1700, 1500),
        timedStage("44", "Deploy", "NOT_EXECUTED", 5700, 100),
        timedStage("49", "Summary", "SUCCESS", 5900, 100)), Collections.<JenkinsWfapiNode>emptyList());

    assertEquals(GraphConfidence.LINEAR_FALLBACK, graph.getConfidence());
    assertEquals(Arrays.asList("6"), node(graph, "12").getParentIds());
    assertEquals(Arrays.asList("12"), node(graph, "20").getParentIds());
    assertEquals(Arrays.asList("20"), node(graph, "22").getParentIds());
    assertEquals(Arrays.asList("22"), node(graph, "24").getParentIds());
    assertEquals(Arrays.asList("24"), node(graph, "44").getParentIds());
    assertEquals(Arrays.asList("44"), node(graph, "49").getParentIds());
    assertFalse(graph.getDiagnostics().toString().contains("DEMO_ONLY"));
  }

  @Test
  public void keepsSkippedStagesAsGraphNodes() {
    JenkinsPipelineGraph graph = graph("job#2", stages(
        stage("1", "Build", "SUCCESS"),
        stage("2", "Maybe Deploy", "NOT_EXECUTED")), Arrays.asList(
        raw("2", "Maybe Deploy", parents("1"))));

    assertEquals(2, graph.getNodes().size());
    assertEquals("NOT_EXECUTED", node(graph, "2").getStatus());
  }

  @Test
  public void topologyHashIgnoresStatusButChangesWithEdges() {
    JenkinsPipelineGraph success = graph("job#3", stages(
        stage("1", "Build", "SUCCESS"),
        stage("2", "Test", "SUCCESS")), Arrays.asList(raw("2", "Test", parents("1"))));
    JenkinsPipelineGraph failed = graph("job#3", stages(
        stage("1", "Build", "SUCCESS"),
        stage("2", "Test", "FAILURE")), Arrays.asList(raw("2", "Test", parents("1"))));
    JenkinsPipelineGraph differentEdges = graph("job#3", stages(
        stage("1", "Build", "SUCCESS"),
        stage("2", "Test", "SUCCESS"),
        stage("3", "Deploy", "SUCCESS")), Arrays.asList(
        raw("2", "Test", parents("1")),
        raw("3", "Deploy", parents("1"))));

    assertEquals(success.getTopologyHash(), failed.getTopologyHash());
    assertNotEquals(success.getTopologyHash(), differentEdges.getTopologyHash());
  }

  @Test
  public void parsesObjectAndStringParentNodesAndLogLinks() {
    JenkinsWfapiNode stringParent = JenkinsWfapiNode.fromJson(parser.parse(
        "{\"id\":\"7\",\"name\":\"sh\",\"parentNodes\":[\"6\"],\"_links\":{\"log\":{\"href\":\"x\"}}}"
    ).getAsJsonObject());
    JenkinsWfapiNode objectParent = JenkinsWfapiNode.fromJson(parser.parse(
        "{\"id\":\"8\",\"name\":\"echo\",\"parentNodes\":[{\"id\":\"7\"}]}"
    ).getAsJsonObject());

    assertEquals(Arrays.asList("6"), stringParent.getParentIds());
    assertTrue(stringParent.isLogNode());
    assertEquals(Arrays.asList("7"), objectParent.getParentIds());
    assertFalse(objectParent.isLogNode());
  }

  private JenkinsPipelineGraph graph(String prefix, JenkinsStages stages, List<JenkinsWfapiNode> rawNodes) {
    return JenkinsPipelineGraph.fromWfapi(prefix, stages, rawNodes, new ArrayList<String>());
  }

  private JenkinsPipelineGraphNode node(JenkinsPipelineGraph graph, String id) {
    for (JenkinsPipelineGraphNode node : graph.getNodes()) {
      if (id.equals(node.getId())) {
        return node;
      }
    }
    throw new AssertionError("Missing graph node " + id);
  }

  private JenkinsStages stages(String... stageJson) {
    StringBuilder json = new StringBuilder("{\"stages\":[");
    for (int i = 0; i < stageJson.length; i++) {
      if (i > 0) {
        json.append(',');
      }
      json.append(stageJson[i]);
    }
    json.append("]}");
    return JenkinsStages.fromJson(parser.parse(json.toString()).getAsJsonObject());
  }

  private String stage(String id, String name, String status) {
    return "{\"id\":\"" + id + "\",\"name\":\"" + name + "\",\"status\":\"" + status + "\"}";
  }

  private String timedStage(String id, String name, String status, long startTimeMillis, long durationMillis) {
    return "{\"id\":\"" + id + "\",\"name\":\"" + name + "\",\"status\":\"" + status
        + "\",\"startTimeMillis\":" + startTimeMillis + ",\"durationMillis\":" + durationMillis + "}";
  }

  private JenkinsWfapiNode raw(String id, String name, List<String> parents) {
    return new JenkinsWfapiNode(id, name, "", "SUCCESS", 1000, 10, parents, false);
  }

  private List<String> parents(String... ids) {
    return Arrays.asList(ids);
  }
}
