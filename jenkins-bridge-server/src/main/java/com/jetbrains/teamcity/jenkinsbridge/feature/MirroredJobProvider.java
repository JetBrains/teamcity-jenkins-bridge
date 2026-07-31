package com.jetbrains.teamcity.jenkinsbridge.feature;

import com.jetbrains.teamcity.jenkinsbridge.settings.JenkinsBridgeSettings;
import com.jetbrains.teamcity.jenkinsbridge.settings.JenkinsBridgeSettingsProvider;
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
 * {@link BridgeBuildFeature}. When no configuration carries the feature, it falls back to a single
 * mirrored job synthesized from the global settings, so the legacy single-job setup keeps working.
 */
public class MirroredJobProvider {
  private final ProjectManager projectManager;
  private final JenkinsBridgeSettingsProvider settingsProvider;

  public MirroredJobProvider(
      ProjectManager projectManager,
      JenkinsBridgeSettingsProvider settingsProvider
  ) {
    this.projectManager = projectManager;
    this.settingsProvider = settingsProvider;
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

    if (jobs.isEmpty()) {
      MirroredJob legacy = legacyJobOrNull();
      if (legacy != null) {
        jobs.add(legacy);
      }
    }
    return jobs;
  }

  private MirroredJob toMirroredJob(SBuildType buildType, SBuildFeatureDescriptor descriptor) {
    Map<String, String> params = descriptor.getParameters();
    String job = params.get(BridgeBuildFeatureConstants.PARAM_JENKINS_JOB);
    int limit = parsePositiveInt(params.get(BridgeBuildFeatureConstants.PARAM_RECENT_LIMIT));
    boolean isMultibranch = Utilities.isBuildConfigMultibranch(buildType);
    return new MirroredJob(job, buildType.getExternalId(), buildType.getFullName(), limit, false, isMultibranch);
  }

  private MirroredJob legacyJobOrNull() {
    JenkinsBridgeSettings settings = settingsProvider.load();
    if (!settings.hasMinimumConfiguration()) {
      return null;
    }
    return MirroredJob.fromGlobalSettings(settings);
  }

  private static int parsePositiveInt(String value) {
    if (value == null) {
      return 0;
    }
    try {
      int parsed = Integer.parseInt(value.trim());
      return Math.max(parsed, 0);
    } catch (NumberFormatException e) {
      return 0;
    }
  }
}
