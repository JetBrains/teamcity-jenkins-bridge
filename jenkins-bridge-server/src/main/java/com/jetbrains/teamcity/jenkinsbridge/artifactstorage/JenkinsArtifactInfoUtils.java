package com.jetbrains.teamcity.jenkinsbridge.artifactstorage;

import jetbrains.buildServer.artifacts.ArtifactData;
import jetbrains.buildServer.serverSide.artifacts.StoredBuildArtifactInfo;
import org.jetbrains.annotations.NotNull;

public class JenkinsArtifactInfoUtils {
  public String jenkinsJob(@NotNull StoredBuildArtifactInfo info) {
    String job = info.getBuildPromotion().getParameterValue("jenkins.job");
    if (job == null || job.isEmpty()) {
      throw new IllegalArgumentException("Missing 'jenkins.job' parameter on build promotion");
    }
    return job;
  }

  public int jenkinsBuildNumber(@NotNull StoredBuildArtifactInfo info) {
    String raw = info.getBuildPromotion().getParameterValue("jenkins.build.number");
    if (raw == null || raw.isEmpty()) {
      throw new IllegalArgumentException("Missing 'jenkins.build.number' parameter on build promotion");
    }
    try {
      return Integer.parseInt(raw.trim());
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException("Invalid 'jenkins.build.number': " + raw, e);
    }
  }

  public String jenkinsRelativePath(@NotNull StoredBuildArtifactInfo info) {
    ArtifactData artifactData = info.getArtifactData();
    if (artifactData == null) {
      throw new IllegalArgumentException("Can not process artifact download request for a folder");
    }
    return artifactData.getPath();
  }
}
