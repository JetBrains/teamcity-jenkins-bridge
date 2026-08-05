package com.jetbrains.teamcity.jenkinsbridge.model;

/** Result of a Jenkins trigger POST, including the durable queue correlation id. */
public class JenkinsTriggerResponse {
  private final String queueItemUrl;
  private final long queueId;

  public JenkinsTriggerResponse(String queueItemUrl, long queueId) {
    this.queueItemUrl = queueItemUrl;
    this.queueId = queueId;
  }

  public String getQueueItemUrl() {
    return queueItemUrl;
  }

  public long getQueueId() {
    return queueId;
  }
}
