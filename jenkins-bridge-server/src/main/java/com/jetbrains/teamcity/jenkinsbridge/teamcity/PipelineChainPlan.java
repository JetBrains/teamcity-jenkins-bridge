package com.jetbrains.teamcity.jenkinsbridge.teamcity;

import com.jetbrains.teamcity.jenkinsbridge.model.GraphConfidence;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static com.jetbrains.teamcity.jenkinsbridge.util.Utilities.nullToEmpty;

public class PipelineChainPlan {
  private final String sourceBuildTypeExternalId;
  private final String jenkinsBuildKey;
  private final String topologyHash;
  private final GraphConfidence confidence;
  private final String topBuildTypeExternalId;
  private final List<Node> nodes;
  private final List<String> terminalNodeIds;

  PipelineChainPlan(
      String sourceBuildTypeExternalId,
      String jenkinsBuildKey,
      String topologyHash,
      GraphConfidence confidence,
      String topBuildTypeExternalId,
      List<Node> nodes,
      List<String> terminalNodeIds
  ) {
    this.sourceBuildTypeExternalId = nullToEmpty(sourceBuildTypeExternalId);
    this.jenkinsBuildKey = nullToEmpty(jenkinsBuildKey);
    this.topologyHash = nullToEmpty(topologyHash);
    this.confidence = confidence;
    this.topBuildTypeExternalId = nullToEmpty(topBuildTypeExternalId);
    this.nodes = copyNodes(nodes);
    this.terminalNodeIds = copyStrings(terminalNodeIds);
  }

  public String getSourceBuildTypeExternalId() {
    return sourceBuildTypeExternalId;
  }

  public String getJenkinsBuildKey() {
    return jenkinsBuildKey;
  }

  public String getTopologyHash() {
    return topologyHash;
  }

  public GraphConfidence getConfidence() {
    return confidence;
  }

  public String getTopBuildTypeExternalId() {
    return topBuildTypeExternalId;
  }

  public List<Node> getNodes() {
    return Collections.unmodifiableList(nodes);
  }

  public List<String> getTerminalNodeIds() {
    return Collections.unmodifiableList(terminalNodeIds);
  }

  public Node findNode(String nodeId) {
    for (Node node : nodes) {
      if (node.getNodeId().equals(nodeId)) {
        return node;
      }
    }
    return null;
  }

  public Node findNodeByBuildTypeExternalId(String buildTypeExternalId) {
    for (Node node : nodes) {
      if (node.getBuildTypeExternalId().equals(buildTypeExternalId)) {
        return node;
      }
    }
    return null;
  }

  private static List<Node> copyNodes(List<Node> values) {
    if (values == null || values.isEmpty()) {
      return new ArrayList<Node>();
    }
    return new ArrayList<Node>(values);
  }

  private static List<String> copyStrings(List<String> values) {
    if (values == null || values.isEmpty()) {
      return new ArrayList<String>();
    }
    return new ArrayList<String>(values);
  }

  public static class Node {
    private final String nodeId;
    private final String flowId;
    private final String name;
    private final String buildTypeExternalId;
    private final List<String> parentNodeIds;
    private final List<String> childNodeIds;

    Node(
        String nodeId,
        String flowId,
        String name,
        String buildTypeExternalId,
        List<String> parentNodeIds,
        List<String> childNodeIds
    ) {
      this.nodeId = nullToEmpty(nodeId);
      this.flowId = nullToEmpty(flowId);
      this.name = nullToEmpty(name);
      this.buildTypeExternalId = nullToEmpty(buildTypeExternalId);
      this.parentNodeIds = copyStrings(parentNodeIds);
      this.childNodeIds = copyStrings(childNodeIds);
    }

    public String getNodeId() {
      return nodeId;
    }

    public String getFlowId() {
      return flowId;
    }

    public String getName() {
      return name;
    }

    public String getBuildTypeExternalId() {
      return buildTypeExternalId;
    }

    public List<String> getParentNodeIds() {
      return Collections.unmodifiableList(parentNodeIds);
    }

    public List<String> getChildNodeIds() {
      return Collections.unmodifiableList(childNodeIds);
    }

    public boolean isTerminal() {
      return childNodeIds.isEmpty();
    }
  }
}
