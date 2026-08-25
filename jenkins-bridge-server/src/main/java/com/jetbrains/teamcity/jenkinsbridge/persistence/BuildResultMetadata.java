package com.jetbrains.teamcity.jenkinsbridge.persistence;

import com.jetbrains.teamcity.jenkinsbridge.model.JenkinsPipelineGraph;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Durable Jenkins result-page data retained after transient {@link BuildMirror} synchronization state
 * is pruned. It is not an active mirror and must never be used by polling to rediscover a build.
 * TeamCity cleanup removes this record with its corresponding build.
 */
public class BuildResultMetadata {
  private String jenkinsBuildKey;
  private String jenkinsJob;
  private int jenkinsBuildNumber;
  private long jenkinsBuildTimestamp;
  private String jenkinsBuildUrl;
  private Long teamCityBuildId;
  private String teamCityBuildTypeId;
  private JenkinsPipelineGraph pipelineGraph;

  public BuildResultMetadata() {
  }

  @NotNull
  public static BuildResultMetadata from(@NotNull BuildMirror mirror) {
    BuildResultMetadata metadata = new BuildResultMetadata();
    metadata.jenkinsBuildKey = mirror.getJenkinsBuildKey();
    metadata.jenkinsJob = mirror.getJenkinsJob();
    metadata.jenkinsBuildNumber = mirror.getJenkinsBuildNumber();
    metadata.jenkinsBuildTimestamp = mirror.getJenkinsBuildTimestamp();
    metadata.jenkinsBuildUrl = mirror.getJenkinsBuildUrl();
    metadata.teamCityBuildId = mirror.getTeamCityBuildId();
    metadata.teamCityBuildTypeId = mirror.getTeamCityBuildTypeId();
    metadata.pipelineGraph = mirror.getPipelineGraph();
    return metadata;
  }

  @Nullable public String getJenkinsBuildKey() { return jenkinsBuildKey; }
  @Nullable public String getJenkinsJob() { return jenkinsJob; }
  public int getJenkinsBuildNumber() { return jenkinsBuildNumber; }
  public long getJenkinsBuildTimestamp() { return jenkinsBuildTimestamp; }
  @Nullable public String getJenkinsBuildUrl() { return jenkinsBuildUrl; }
  @Nullable public Long getTeamCityBuildId() { return teamCityBuildId; }
  @Nullable public String getTeamCityBuildTypeId() { return teamCityBuildTypeId; }
  @Nullable public JenkinsPipelineGraph getPipelineGraph() { return pipelineGraph; }
}
