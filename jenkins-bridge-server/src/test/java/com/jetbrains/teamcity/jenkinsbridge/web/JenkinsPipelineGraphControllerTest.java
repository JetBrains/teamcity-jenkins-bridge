package com.jetbrains.teamcity.jenkinsbridge.web;

import com.jetbrains.teamcity.jenkinsbridge.model.JenkinsPipelineGraph;
import com.jetbrains.teamcity.jenkinsbridge.model.JenkinsPipelineGraphNode;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class JenkinsPipelineGraphControllerTest {

  @Test
  public void graphViewMapsNodesStatusesAndEdges() {
    List<JenkinsPipelineGraphNode> nodes = Arrays.asList(
        node("6", "Setup", "SUCCESS", Collections.<String>emptyList(), Arrays.asList("9")),
        node("9", "Deploy", "IN_PROGRESS", Arrays.asList("6"), Collections.<String>emptyList()));
    JenkinsPipelineGraph graph = JenkinsPipelineGraph.explicit(
        JenkinsPipelineGraph.SOURCE_BLUE_OCEAN, nodes, Collections.<String>emptyList());

    JenkinsPipelineGraphController.GraphView view = JenkinsPipelineGraphController.GraphView.of(graph);

    assertTrue(view.pipeline);
    assertEquals(JenkinsPipelineGraph.SOURCE_BLUE_OCEAN, view.source);
    assertEquals(2, view.nodes.size());
    assertEquals("Setup", view.nodes.get(0).name);
    assertEquals("SUCCESS", view.nodes.get(0).status);
    assertEquals(Arrays.asList("9"), view.nodes.get(0).children);
    assertEquals("IN_PROGRESS", view.nodes.get(1).status);
    assertEquals(Arrays.asList("6"), view.nodes.get(1).parents);
  }

  @Test
  public void notPipelineViewIsEmpty() {
    JenkinsPipelineGraphController.GraphView view = JenkinsPipelineGraphController.GraphView.notPipeline();
    assertFalse(view.pipeline);
    assertTrue(view.nodes.isEmpty());
  }

  private JenkinsPipelineGraphNode node(String id, String name, String status,
                                        List<String> parents, List<String> children) {
    return new JenkinsPipelineGraphNode(
        id, "flow:" + id, name, status, 1000L, 100L, parents, children, Collections.<String>emptyList());
  }
}
