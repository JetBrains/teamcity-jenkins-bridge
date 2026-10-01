package com.jetbrains.teamcity.jenkinsbridge.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static com.jetbrains.teamcity.jenkinsbridge.util.Utilities.nullToEmpty;

public class JenkinsPipelineGraphNode {
  private String id;
  private String flowId;
  private String name;
  private String status;
  private long startTimeMillis;
  private long durationMillis;
  private List<String> parentIds;
  private List<String> childIds;
  private List<String> logNodeIds;
  private String hierarchyParentId;
  private boolean synthetic;

  // Gson needs a no-arg constructor.
  public JenkinsPipelineGraphNode() {
  }

  public JenkinsPipelineGraphNode(
      String id,
      String flowId,
      String name,
      String status,
      long startTimeMillis,
      long durationMillis,
      List<String> parentIds,
      List<String> childIds,
      List<String> logNodeIds
  ) {
    this.id = nullToEmpty(id);
    this.flowId = nullToEmpty(flowId);
    this.name = nullToEmpty(name);
    this.status = nullToEmpty(status);
    this.startTimeMillis = startTimeMillis;
    this.durationMillis = durationMillis;
    this.parentIds = copy(parentIds);
    this.childIds = copy(childIds);
    this.logNodeIds = copy(logNodeIds);
  }

  public JenkinsPipelineGraphNode(
      String id, String flowId, String name, String status, long startTimeMillis, long durationMillis,
      List<String> parentIds, List<String> childIds, List<String> logNodeIds,
      String hierarchyParentId, boolean synthetic
  ) {
    this(id, flowId, name, status, startTimeMillis, durationMillis, parentIds, childIds, logNodeIds);
    this.hierarchyParentId = nullToEmpty(hierarchyParentId);
    this.synthetic = synthetic;
  }

  public String getId() {
    return nullToEmpty(id);
  }

  public String getFlowId() {
    return nullToEmpty(flowId);
  }

  public String getName() {
    return nullToEmpty(name);
  }

  public String getStatus() {
    return nullToEmpty(status);
  }

  public long getStartTimeMillis() {
    return startTimeMillis;
  }

  public long getDurationMillis() {
    return durationMillis;
  }

  public List<String> getParentIds() {
    return parentIds == null ? Collections.<String>emptyList() : Collections.unmodifiableList(parentIds);
  }

  public List<String> getChildIds() {
    return childIds == null ? Collections.<String>emptyList() : Collections.unmodifiableList(childIds);
  }

  public List<String> getLogNodeIds() {
    return logNodeIds == null ? Collections.<String>emptyList() : Collections.unmodifiableList(logNodeIds);
  }

  /** Jenkins Pipeline Overview containment; never interpreted as a snapshot dependency. */
  public String getHierarchyParentId() {
    return nullToEmpty(hierarchyParentId);
  }

  public boolean isSynthetic() {
    return synthetic;
  }

  private static List<String> copy(List<String> values) {
    if (values == null || values.isEmpty()) {
      return new ArrayList<String>();
    }
    return new ArrayList<String>(values);
  }

}
