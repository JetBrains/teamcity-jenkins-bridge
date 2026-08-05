package com.jetbrains.teamcity.jenkinsbridge.feature;

import com.jetbrains.teamcity.jenkinsbridge.settings.MirroredJob;
import com.jetbrains.teamcity.jenkinsbridge.util.Utilities;
import jetbrains.buildServer.serverSide.ProjectManager;
import jetbrains.buildServer.serverSide.SBuildFeatureDescriptor;
import jetbrains.buildServer.serverSide.SBuildType;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Discovers the jobs to mirror by scanning every active build configuration for the
 * {@link BridgeBuildFeature}. Each discovered job names the Jenkins connection it is mirrored from.
 */
public class MirroredJobProvider {
  private final ProjectManager projectManager;

  public MirroredJobProvider(ProjectManager projectManager) {
    this.projectManager = projectManager;
  }

  public List<MirroredJob> discoverMirroredJobs() {
    List<MirroredJob> jobs = new ArrayList<>();
    for (SBuildType buildType : projectManager.getActiveBuildTypes()) {
      // isMultipleFeaturesPerBuildTypeAllowed()==false is UI-advisory only; iterate defensively.
      for (SBuildFeatureDescriptor descriptor
          : buildType.getBuildFeaturesOfType(BridgeBuildFeatureConstants.TYPE)) {
        jobs.add(toMirroredJob(buildType, descriptor));
      }
    }
    return jobs;
  }

  private MirroredJob toMirroredJob(SBuildType buildType, SBuildFeatureDescriptor descriptor) {
    Map<String, String> params = descriptor.getParameters();
    String connectionId = params.get(BridgeBuildFeatureConstants.PARAM_CONNECTION_ID);
    String job = params.get(BridgeBuildFeatureConstants.PARAM_JENKINS_JOB);
    int limit = parseRecentBuildLimit(params.get(BridgeBuildFeatureConstants.PARAM_RECENT_LIMIT));
    boolean isMultibranch = Utilities.isBuildConfigMultibranch(buildType);
    return new MirroredJob(
        connectionId, job, buildType.getExternalId(), buildType.getFullName(), limit, isMultibranch);
  }

  private static int parseRecentBuildLimit(String value) {
    if (value == null || value.trim().isEmpty()) {
      return BridgeBuildFeatureConstants.DEFAULT_RECENT_LIMIT;
    }
    try {
      return Math.max(0, Integer.parseInt(value.trim()));
    } catch (NumberFormatException e) {
      return BridgeBuildFeatureConstants.DEFAULT_RECENT_LIMIT;
    }
  }
}
