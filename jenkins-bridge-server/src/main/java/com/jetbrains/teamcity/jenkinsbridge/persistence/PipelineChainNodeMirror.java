package com.jetbrains.teamcity.jenkinsbridge.persistence;

import static com.jetbrains.teamcity.jenkinsbridge.util.Utilities.nullToEmpty;

public class PipelineChainNodeMirror {
  private String nodeId;
  private String flowId;
  private String buildTypeExternalId;
  private Long promotionId;
  private String lastStatus;
  private boolean runningSent;
  private boolean finished;

  // Gson needs a no-arg constructor.
  public PipelineChainNodeMirror() {
  }

  public PipelineChainNodeMirror(String nodeId, String flowId, String buildTypeExternalId, Long promotionId) {
    this.nodeId = nullToEmpty(nodeId);
    this.flowId = nullToEmpty(flowId);
    this.buildTypeExternalId = nullToEmpty(buildTypeExternalId);
    this.promotionId = promotionId;
  }

  public String getNodeId() {
    return nullToEmpty(nodeId);
  }

  public String getFlowId() {
    return nullToEmpty(flowId);
  }

  public String getBuildTypeExternalId() {
    return nullToEmpty(buildTypeExternalId);
  }

  public Long getPromotionId() {
    return promotionId;
  }

  public String getLastStatus() {
    return nullToEmpty(lastStatus);
  }

  public void setLastStatus(String lastStatus) {
    this.lastStatus = nullToEmpty(lastStatus);
  }

  public boolean isRunningSent() {
    return runningSent;
  }

  public void setRunningSent(boolean runningSent) {
    this.runningSent = runningSent;
  }

  public boolean isFinished() {
    return finished;
  }

  public void setFinished(boolean finished) {
    this.finished = finished;
  }

}
