package com.jetbrains.teamcity.jenkinsbridge.persistence;

/**
 * A TeamCity-first run that has triggered Jenkins, but has not yet been assigned a Jenkins build
 * number. The Jenkins queue item URL is the v1 correlation handle.
 */
public class PendingTrigger {
  private long teamCityPromotionId;
  private String jenkinsJob;
  private String teamCityBuildTypeExternalId;
  private String queueItemUrl;
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
    this.teamCityPromotionId = teamCityPromotionId;
    this.jenkinsJob = jenkinsJob;
    this.teamCityBuildTypeExternalId = teamCityBuildTypeExternalId;
    this.queueItemUrl = queueItemUrl;
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

  public String getCreatedAt() {
    return createdAt;
  }
}
