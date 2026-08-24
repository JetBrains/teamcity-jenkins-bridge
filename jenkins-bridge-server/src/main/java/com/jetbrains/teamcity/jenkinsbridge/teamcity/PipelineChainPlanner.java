package com.jetbrains.teamcity.jenkinsbridge.teamcity;

import com.jetbrains.teamcity.jenkinsbridge.model.GraphConfidence;
import com.jetbrains.teamcity.jenkinsbridge.model.JenkinsPipelineGraph;
import com.jetbrains.teamcity.jenkinsbridge.model.JenkinsPipelineGraphNode;
import com.jetbrains.teamcity.jenkinsbridge.persistence.BuildMirror;
import jetbrains.buildServer.serverSide.identifiers.IdentifiersUtil;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

public class PipelineChainPlanner {
  public boolean canCreateNativeChain(JenkinsPipelineGraph graph) {
    if (graph == null || !graph.isPipeline() || graph.getNodes().isEmpty()) {
      return false;
    }
    GraphConfidence confidence = graph.getConfidence();
    return confidence == GraphConfidence.EXPLICIT
        || confidence == GraphConfidence.COLLAPSED
        || confidence == GraphConfidence.LINEAR_FALLBACK;
  }

  public PipelineChainPlan plan(BuildMirror mirror, String sourceBuildTypeExternalId, JenkinsPipelineGraph graph) {
    if (!canCreateNativeChain(graph)) {
      throw new IllegalArgumentException("Pipeline graph is not eligible for native TeamCity chain creation");
    }

    String buildKey = mirror == null ? "" : mirror.getJenkinsBuildKey();
    String runHash = shortHash(buildKey, 12);
    List<PipelineChainPlan.Node> nodes = new ArrayList<PipelineChainPlan.Node>();
    List<String> terminalNodeIds = new ArrayList<String>();

    for (JenkinsPipelineGraphNode graphNode : graph.getNodes()) {
      String externalId = buildTypeExternalId(sourceBuildTypeExternalId, runHash, graphNode.getId());
      PipelineChainPlan.Node node = new PipelineChainPlan.Node(
          graphNode.getId(),
          graphNode.getFlowId(),
          graphNode.getName(),
          externalId,
          graphNode.getParentIds(),
          graphNode.getChildIds());
      nodes.add(node);
      if (node.isTerminal()) {
        terminalNodeIds.add(node.getNodeId());
      }
    }

    return new PipelineChainPlan(
        sourceBuildTypeExternalId,
        buildKey,
        graph.getTopologyHash(),
        graph.getConfidence(),
        topBuildTypeExternalId(sourceBuildTypeExternalId, runHash),
        nodes,
        terminalNodeIds);
  }

  private String topBuildTypeExternalId(String sourceBuildTypeExternalId, String runHash) {
    return pipelineExternalId(sourceBuildTypeExternalId, "JenkinsFlow_" + runHash + "_Top");
  }

  private String buildTypeExternalId(String sourceBuildTypeExternalId, String runHash, String nodeId) {
    String safeNodeId = nodeId == null ? "Node" : nodeId;
    String userString = "JenkinsFlow_" + runHash + "_" + safeNodeId;
    return pipelineExternalId(sourceBuildTypeExternalId, userString);
  }

  private String pipelineExternalId(String sourceBuildTypeExternalId, String userString) {
    String source = sourceBuildTypeExternalId == null || sourceBuildTypeExternalId.trim().isEmpty()
        ? "JenkinsBridge" : sourceBuildTypeExternalId;
    return IdentifiersUtil.generateUniqueExternalIdByUserString(
        source,
        userString,
        false,
        candidate -> false);
  }

  private static String shortHash(String value, int length) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] bytes = digest.digest((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
      StringBuilder result = new StringBuilder();
      for (byte b : bytes) {
        String hex = Integer.toHexString(b & 0xff);
        if (hex.length() == 1) {
          result.append('0');
        }
        result.append(hex);
      }
      return result.substring(0, Math.min(length, result.length()));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is not available", e);
    }
  }
}
