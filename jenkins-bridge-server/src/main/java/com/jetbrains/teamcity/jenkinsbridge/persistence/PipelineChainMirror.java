package com.jetbrains.teamcity.jenkinsbridge.persistence;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.jetbrains.teamcity.jenkinsbridge.util.Utilities.nullToEmpty;

public class PipelineChainMirror {
  private String topologyHash;
  private String confidence;
  private String topBuildTypeExternalId;
  private Long topPromotionId;
  private Map<String, PipelineChainNodeMirror> nodes;
  private List<String> terminalNodeIds;
  private List<Long> terminalPromotionIds;
  private boolean queued;

  // Gson needs a no-arg constructor.
  public PipelineChainMirror() {
  }

  public PipelineChainMirror(
      String topologyHash,
      String confidence,
      String topBuildTypeExternalId,
      Long topPromotionId,
      Map<String, PipelineChainNodeMirror> nodes,
      List<String> terminalNodeIds,
      List<Long> terminalPromotionIds,
      boolean queued
  ) {
    this.topologyHash = nullToEmpty(topologyHash);
    this.confidence = nullToEmpty(confidence);
    this.topBuildTypeExternalId = nullToEmpty(topBuildTypeExternalId);
    this.topPromotionId = topPromotionId;
    this.nodes = copyNodes(nodes);
    this.terminalNodeIds = copyStrings(terminalNodeIds);
    this.terminalPromotionIds = copyLongs(terminalPromotionIds);
    this.queued = queued;
  }

  public String getTopologyHash() {
    return nullToEmpty(topologyHash);
  }

  public String getConfidence() {
    return nullToEmpty(confidence);
  }

  public String getTopBuildTypeExternalId() {
    return nullToEmpty(topBuildTypeExternalId);
  }

  public Long getTopPromotionId() {
    return topPromotionId;
  }

  public Map<String, PipelineChainNodeMirror> getNodes() {
    if (nodes == null) {
      return Collections.emptyMap();
    }
    return Collections.unmodifiableMap(nodes);
  }

  public PipelineChainNodeMirror getNode(String nodeId) {
    return nodes == null ? null : nodes.get(nodeId);
  }

  public List<String> getTerminalNodeIds() {
    return terminalNodeIds == null ? Collections.<String>emptyList() : Collections.unmodifiableList(terminalNodeIds);
  }

  public List<Long> getTerminalPromotionIds() {
    return terminalPromotionIds == null ? Collections.<Long>emptyList() : Collections.unmodifiableList(terminalPromotionIds);
  }

  public boolean isQueued() {
    return queued;
  }

  public boolean matchesQueuedTopology(String hash) {
    return queued && getTopologyHash().equals(nullToEmpty(hash));
  }

  private static Map<String, PipelineChainNodeMirror> copyNodes(Map<String, PipelineChainNodeMirror> values) {
    if (values == null || values.isEmpty()) {
      return new LinkedHashMap<String, PipelineChainNodeMirror>();
    }
    return new LinkedHashMap<String, PipelineChainNodeMirror>(values);
  }

  private static List<String> copyStrings(List<String> values) {
    if (values == null || values.isEmpty()) {
      return new ArrayList<String>();
    }
    return new ArrayList<String>(values);
  }

  private static List<Long> copyLongs(List<Long> values) {
    if (values == null || values.isEmpty()) {
      return new ArrayList<Long>();
    }
    return new ArrayList<Long>(values);
  }

}