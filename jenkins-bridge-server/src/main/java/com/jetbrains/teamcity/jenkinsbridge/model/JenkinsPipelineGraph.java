package com.jetbrains.teamcity.jenkinsbridge.model;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

import static com.jetbrains.teamcity.jenkinsbridge.util.Utilities.nullToEmpty;

public class JenkinsPipelineGraph {
  public static final String SOURCE_WFAPI = "WFAPI";
  public static final String SOURCE_BLUE_OCEAN = "BLUE_OCEAN";
  public static final String SOURCE_PIPELINE_GRAPH_VIEW = "PIPELINE_GRAPH_VIEW";

  private boolean pipeline;
  private String source;
  private List<JenkinsPipelineGraphNode> nodes;
  private String topologyHash;
  private GraphConfidence confidence;
  private List<String> diagnostics;
  private Boolean complete;

  // Gson needs a no-arg constructor.
  public JenkinsPipelineGraph() {
  }

  public JenkinsPipelineGraph(
      boolean pipeline,
      String source,
      List<JenkinsPipelineGraphNode> nodes,
      String topologyHash,
      GraphConfidence confidence,
      List<String> diagnostics
  ) {
    this.pipeline = pipeline;
    this.source = source;
    this.nodes = copyNodes(nodes);
    this.topologyHash = nullToEmpty(topologyHash);
    this.confidence = confidence == null ? GraphConfidence.UNAVAILABLE : confidence;
    this.diagnostics = copyStrings(diagnostics);
  }

  public static JenkinsPipelineGraph unavailable(List<String> diagnostics) {
    return new JenkinsPipelineGraph(
        false,
        SOURCE_WFAPI,
        Collections.<JenkinsPipelineGraphNode>emptyList(),
        "",
        GraphConfidence.UNAVAILABLE,
        diagnostics);
  }

  public static JenkinsPipelineGraph fromWfapi(
      String flowIdPrefix,
      JenkinsStages stages,
      List<JenkinsWfapiNode> rawNodes,
      List<String> diagnostics
  ) {
    List<String> allDiagnostics = copyStrings(diagnostics);
    if (stages == null || !stages.isPipeline()) {
      allDiagnostics.add("WFAPI stage description is unavailable");
      return unavailable(allDiagnostics);
    }
    if (stages.getStages().isEmpty()) {
      allDiagnostics.add("WFAPI returned a Pipeline run with no stages yet");
      return new JenkinsPipelineGraph(
          true,
          SOURCE_WFAPI,
          Collections.<JenkinsPipelineGraphNode>emptyList(),
          "",
          GraphConfidence.UNAVAILABLE,
          allDiagnostics);
    }

    Map<String, JenkinsWfapiNode> rawById = mergeRawNodes(stages, rawNodes);
    List<String> selectedIds = new ArrayList<String>();
    Map<String, JenkinsStage> selectedStages = new LinkedHashMap<String, JenkinsStage>();
    for (JenkinsStage stage : stages.getStages()) {
      if (stage.getId().length() == 0) {
        allDiagnostics.add("Skipping WFAPI stage with blank id: " + stage.getName());
        continue;
      }
      selectedIds.add(stage.getId());
      selectedStages.put(stage.getId(), stage);
    }

    Set<String> selectedIdSet = new LinkedHashSet<String>(selectedIds);
    Map<String, Set<String>> parentsByStage = new LinkedHashMap<String, Set<String>>();
    boolean usedFallback = false;
    boolean usedCollapse = false;

    for (int i = 0; i < selectedIds.size(); i++) {
      String id = selectedIds.get(i);
      AncestorResult ancestors = nearestSelectedAncestors(id, rawById, selectedIdSet);
      Set<String> parents = ancestors.ids;
      if (ancestors.usedIntermediate) {
        usedCollapse = true;
      }
      if (parents.isEmpty() && i > 0) {
        parents.add(selectedIds.get(i - 1));
        usedFallback = true;
        allDiagnostics.add("No selected WFAPI parent found for stage " + id + "; using previous stage");
      }
      parentsByStage.put(id, parents);
    }

    Map<String, Set<String>> childrenByStage = new LinkedHashMap<String, Set<String>>();
    for (String id : selectedIds) {
      childrenByStage.put(id, new LinkedHashSet<String>());
    }
    for (Map.Entry<String, Set<String>> entry : parentsByStage.entrySet()) {
      for (String parent : entry.getValue()) {
        Set<String> children = childrenByStage.get(parent);
        if (children != null) {
          children.add(entry.getKey());
        } else {
          allDiagnostics.add("Stage " + entry.getKey() + " references missing parent " + parent);
        }
      }
    }

    List<JenkinsPipelineGraphNode> graphNodes = new ArrayList<JenkinsPipelineGraphNode>();
    for (String id : selectedIds) {
      JenkinsStage stage = selectedStages.get(id);
      JenkinsWfapiNode raw = rawById.get(id);
      graphNodes.add(new JenkinsPipelineGraphNode(
          id,
          nullToEmpty(flowIdPrefix) + ":" + id,
          stage.getName(),
          stage.getStatus(),
          stage.getStartTimeMillis(),
          stage.getDurationMillis(),
          inSelectedOrder(parentsByStage.get(id), selectedIds),
          inSelectedOrder(childrenByStage.get(id), selectedIds),
          logNodeIdsForStage(id, rawById)));
      if (raw == null) {
        allDiagnostics.add("No raw WFAPI node metadata found for selected stage " + id);
      }
    }

    GraphConfidence confidence = usedFallback
        ? GraphConfidence.LINEAR_FALLBACK
        : (usedCollapse ? GraphConfidence.COLLAPSED : GraphConfidence.EXPLICIT);

    return new JenkinsPipelineGraph(
        true,
        SOURCE_WFAPI,
        graphNodes,
        topologyHash(graphNodes),
        confidence,
        allDiagnostics);
  }

  public static JenkinsPipelineGraph explicit(
      String source,
      List<JenkinsPipelineGraphNode> nodes,
      List<String> diagnostics
  ) {
    List<JenkinsPipelineGraphNode> graphNodes = copyNodes(nodes);
    return new JenkinsPipelineGraph(
        true,
        source,
        graphNodes,
        topologyHash(graphNodes),
        GraphConfidence.EXPLICIT,
        diagnostics);
  }

  /** A faithful stage tree without inferred execution edges. */
  public static JenkinsPipelineGraph hierarchy(List<JenkinsPipelineGraphNode> nodes, boolean complete,
                                                List<String> diagnostics) {
    List<JenkinsPipelineGraphNode> graphNodes = copyNodes(nodes);
    JenkinsPipelineGraph graph = new JenkinsPipelineGraph(true, SOURCE_PIPELINE_GRAPH_VIEW, graphNodes,
        topologyHash(graphNodes), GraphConfidence.HIERARCHY_ONLY, diagnostics);
    graph.complete = complete;
    return graph;
  }

  public boolean isPipeline() {
    return pipeline;
  }

  public String getSource() {
    return nullToEmpty(source);
  }

  public List<JenkinsPipelineGraphNode> getNodes() {
    return nodes == null ? Collections.<JenkinsPipelineGraphNode>emptyList() : Collections.unmodifiableList(nodes);
  }

  public String getTopologyHash() {
    return nullToEmpty(topologyHash);
  }

  public GraphConfidence getConfidence() {
    return confidence == null ? GraphConfidence.UNAVAILABLE : confidence;
  }

  public boolean isComplete() {
    return Boolean.TRUE.equals(complete);
  }

  public List<String> getDiagnostics() {
    return diagnostics == null ? Collections.<String>emptyList() : Collections.unmodifiableList(diagnostics);
  }

  private static Map<String, JenkinsWfapiNode> mergeRawNodes(JenkinsStages stages, List<JenkinsWfapiNode> rawNodes) {
    Map<String, JenkinsWfapiNode> rawById = new LinkedHashMap<String, JenkinsWfapiNode>();
    for (JenkinsStage stage : stages.getStages()) {
      putOrMerge(rawById, JenkinsWfapiNode.fromStage(stage));
    }
    if (rawNodes != null) {
      for (JenkinsWfapiNode rawNode : rawNodes) {
        putOrMerge(rawById, rawNode);
      }
    }
    return rawById;
  }

  private static void putOrMerge(Map<String, JenkinsWfapiNode> rawById, JenkinsWfapiNode node) {
    if (node == null || !node.hasId()) {
      return;
    }
    JenkinsWfapiNode existing = rawById.get(node.getId());
    rawById.put(node.getId(), existing == null ? node : existing.merge(node));
  }

  private static AncestorResult nearestSelectedAncestors(
      String id,
      Map<String, JenkinsWfapiNode> rawById,
      Set<String> selectedIds
  ) {
    AncestorResult result = new AncestorResult();
    JenkinsWfapiNode start = rawById.get(id);
    if (start == null) {
      return result;
    }

    Queue<String> queue = new ArrayDeque<String>(start.getParentIds());
    Set<String> visited = new LinkedHashSet<String>();
    while (!queue.isEmpty()) {
      String parent = queue.remove();
      if (!visited.add(parent)) {
        continue;
      }
      if (selectedIds.contains(parent)) {
        result.ids.add(parent);
        continue;
      }
      JenkinsWfapiNode parentNode = rawById.get(parent);
      if (parentNode != null) {
        result.usedIntermediate = true;
        queue.addAll(parentNode.getParentIds());
      }
    }
    return result;
  }

  private static List<String> logNodeIdsForStage(String stageId, Map<String, JenkinsWfapiNode> rawById) {
    List<String> ids = new ArrayList<String>();
    for (JenkinsWfapiNode node : rawById.values()) {
      if (!node.isLogNode()) {
        continue;
      }
      if (isDescendantOf(node, stageId, rawById)) {
        ids.add(node.getId());
      }
    }
    return ids;
  }

  private static boolean isDescendantOf(JenkinsWfapiNode node, String ancestorId, Map<String, JenkinsWfapiNode> rawById) {
    Queue<String> queue = new ArrayDeque<String>(node.getParentIds());
    Set<String> visited = new LinkedHashSet<String>();
    while (!queue.isEmpty()) {
      String parent = queue.remove();
      if (!visited.add(parent)) {
        continue;
      }
      if (ancestorId.equals(parent)) {
        return true;
      }
      JenkinsWfapiNode parentNode = rawById.get(parent);
      if (parentNode != null) {
        queue.addAll(parentNode.getParentIds());
      }
    }
    return false;
  }

  private static List<String> inSelectedOrder(Set<String> ids, List<String> selectedIds) {
    if (ids == null || ids.isEmpty()) {
      return Collections.emptyList();
    }
    List<String> ordered = new ArrayList<String>();
    for (String selectedId : selectedIds) {
      if (ids.contains(selectedId)) {
        ordered.add(selectedId);
      }
    }
    return ordered;
  }

  private static String topologyHash(List<JenkinsPipelineGraphNode> graphNodes) {
    List<String> lines = new ArrayList<String>();
    for (JenkinsPipelineGraphNode node : graphNodes) {
      lines.add("node:" + node.getId());
      if (!node.getHierarchyParentId().isEmpty()) {
        lines.add("contains:" + node.getHierarchyParentId() + "->" + node.getId());
      }
      for (String child : node.getChildIds()) {
        lines.add("edge:" + node.getId() + "->" + child);
      }
    }
    Collections.sort(lines);
    StringBuilder canonical = new StringBuilder();
    for (String line : lines) {
      canonical.append(line).append('\n');
    }
    return sha256(canonical.toString());
  }

  private static String sha256(String value) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
      StringBuilder result = new StringBuilder();
      for (byte b : bytes) {
        String hex = Integer.toHexString(b & 0xff);
        if (hex.length() == 1) {
          result.append('0');
        }
        result.append(hex);
      }
      return result.toString();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is not available", e);
    }
  }

  private static List<JenkinsPipelineGraphNode> copyNodes(List<JenkinsPipelineGraphNode> values) {
    if (values == null || values.isEmpty()) {
      return new ArrayList<JenkinsPipelineGraphNode>();
    }
    return new ArrayList<JenkinsPipelineGraphNode>(values);
  }

  private static List<String> copyStrings(List<String> values) {
    if (values == null || values.isEmpty()) {
      return new ArrayList<String>();
    }
    return new ArrayList<String>(values);
  }

  private static class AncestorResult {
    private final Set<String> ids = new LinkedHashSet<String>();
    private boolean usedIntermediate;
  }
}
