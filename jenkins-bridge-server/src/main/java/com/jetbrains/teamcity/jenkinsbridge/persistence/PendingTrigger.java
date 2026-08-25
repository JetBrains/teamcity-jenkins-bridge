package com.jetbrains.teamcity.jenkinsbridge.persistence;

/**
 * A TeamCity-first run that has triggered Jenkins, but has not yet been assigned a Jenkins build
 * number. The Jenkins queue id is the primary correlation handle; the cause marker is the
 * recovery handle when Jenkins does not return queue metadata.
 */
public class PendingTrigger {
  private long teamCityPromotionId;
  private String jenkinsJob;
  private String teamCityBuildTypeExternalId;
  private String queueItemUrl;
  private long jenkinsQueueId = -1L;
  private String jenkinsController;
  private String createdAt;
  private String triggerCause;
  private String originatingNode;

  // TODO let's clean up those constructor overrides
  public PendingTrigger() {
  }

  public PendingTrigger(
      long teamCityPromotionId,
      String jenkinsJob,
      String teamCityBuildTypeExternalId,
      String queueItemUrl,
      String createdAt
  ) {
    this(teamCityPromotionId, jenkinsJob, teamCityBuildTypeExternalId, queueItemUrl, -1L, "", createdAt, "", "");
  }

  public PendingTrigger(
      long teamCityPromotionId,
      String jenkinsJob,
      String teamCityBuildTypeExternalId,
      String queueItemUrl,
      long jenkinsQueueId,
      String jenkinsController,
      String createdAt
  ) {
    this(teamCityPromotionId, jenkinsJob, teamCityBuildTypeExternalId, queueItemUrl, jenkinsQueueId,
        jenkinsController, createdAt, "", "");
  }

  public PendingTrigger(long teamCityPromotionId, String jenkinsJob, String teamCityBuildTypeExternalId,
                        String queueItemUrl, long jenkinsQueueId, String jenkinsController,
                        String createdAt, String triggerCause, String originatingNode) {
    this.teamCityPromotionId = teamCityPromotionId;
    this.jenkinsJob = jenkinsJob;
    this.teamCityBuildTypeExternalId = teamCityBuildTypeExternalId;
    this.queueItemUrl = queueItemUrl;
    this.jenkinsQueueId = jenkinsQueueId;
    this.jenkinsController = jenkinsController;
    this.createdAt = createdAt;
    this.triggerCause = triggerCause;
    this.originatingNode = originatingNode;
  }

  public long getTeamCityPromotionId() {
    return teamCityPromotionId;
  }

  public String getJenkinsJob() {
    return jenkinsJob;
  }

  public String getTeamCityBuildTypeExternalId() {
    return teamCityBuildTypeExternalId;
  }

  public String getQueueItemUrl() {
    return queueItemUrl;
  }

  public long getJenkinsQueueId() {
    return jenkinsQueueId;
  }

  public boolean hasResolvedQueueId() {
    return jenkinsQueueId >= 0;
  }

  public String getJenkinsController() {
    return jenkinsController;
  }

  public String getCreatedAt() {
    return createdAt;
  }

  public String getTriggerCause() { return triggerCause; }
  public String getOriginatingNode() { return originatingNode; }
}
