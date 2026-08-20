package com.jetbrains.teamcity.jenkinsbridge.persistence;

/**
 * A TeamCity-first run that has triggered Jenkins, but has not yet been assigned a Jenkins build
 * number. The Jenkins queue id is the authoritative correlation handle.
 */
public class PendingTrigger {
  private long teamCityPromotionId;
  private String jenkinsJob;
  private String teamCityBuildTypeExternalId;
  private String queueItemUrl;
  private long jenkinsQueueId = -1L;
  private String jenkinsController;
  private String createdAt;

  public PendingTrigger() {
  }

  public PendingTrigger(
      long teamCityPromotionId,
      String jenkinsJob,
      String teamCityBuildTypeExternalId,
      String queueItemUrl,
      String createdAt
  ) {
    this(teamCityPromotionId, jenkinsJob, teamCityBuildTypeExternalId, queueItemUrl, -1L, "", createdAt);
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
    this.teamCityPromotionId = teamCityPromotionId;
    this.jenkinsJob = jenkinsJob;
    this.teamCityBuildTypeExternalId = teamCityBuildTypeExternalId;
    this.queueItemUrl = queueItemUrl;
    this.jenkinsQueueId = jenkinsQueueId;
    this.jenkinsController = jenkinsController;
    this.createdAt = createdAt;
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
}
