package com.jetbrains.teamcity.jenkinsbridge.feature;

import com.jetbrains.teamcity.jenkinsbridge.http.BridgeHttpException;
import com.jetbrains.teamcity.jenkinsbridge.jenkins.JenkinsClient;
import com.jetbrains.teamcity.jenkinsbridge.jenkins.JenkinsClientFactory;
import com.jetbrains.teamcity.jenkinsbridge.jenkins.JenkinsDataException;
import com.jetbrains.teamcity.jenkinsbridge.model.JenkinsJobParameters;
import com.jetbrains.teamcity.jenkinsbridge.persistence.BuildMirrorStore;
import jetbrains.buildServer.serverSide.SBuildFeatureDescriptor;
import jetbrains.buildServer.serverSide.SBuildType;
import jetbrains.buildServer.serverSide.parameters.ParameterFactory;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;

/**
 * Owns the complete Jenkins-parameter synchronization flow for a mirrored build configuration.
 * Callers decide when to persist the TeamCity build configuration or whether a definition change
 * should prevent a trigger; this service owns fetching, reconciliation, and bridge-state updates.
 */
public final class JenkinsParameterSynchronizer {
  private final JenkinsClientFactory jenkinsClientFactory;
  private final ParameterFactory parameterFactory;
  private final BuildMirrorStore mirrorStore;

  public JenkinsParameterSynchronizer(@NotNull JenkinsClientFactory jenkinsClientFactory,
                                      @NotNull ParameterFactory parameterFactory,
                                      @NotNull BuildMirrorStore mirrorStore) {
    this.jenkinsClientFactory = jenkinsClientFactory;
    this.parameterFactory = parameterFactory;
    this.mirrorStore = mirrorStore;
  }

  @NotNull
  public SynchronizationResult synchronize(@NotNull SBuildType buildType)
      throws BridgeHttpException, JenkinsDataException {
    SBuildFeatureDescriptor feature = featureOf(buildType);
    if (feature == null) {
      return SynchronizationResult.empty();
    }
    String job = feature.getParameters().get(BridgeBuildFeatureConstants.PARAM_JENKINS_JOB);
    if (job == null || job.trim().isEmpty()) {
      return SynchronizationResult.empty();
    }
    return synchronize(jenkinsClientFactory.forBuildType(buildType), buildType, job.trim());
  }

  @NotNull
  public SynchronizationResult synchronize(@NotNull JenkinsClient client,
                                           @NotNull SBuildType buildType,
                                           @NotNull String job)
      throws BridgeHttpException, JenkinsDataException {
    JenkinsJobParameters definitions = client.getJobParameters(job);
    try {
      String previousSnapshot = mirrorStore.getImportedJenkinsParameterSnapshot(
          buildType.getExternalId());
      JenkinsTeamCityRunParameterFactory.SynchronizationResult teamCityResult =
          JenkinsTeamCityRunParameterFactory.synchronize(
              parameterFactory,
              buildType,
              definitions,
              mirrorStore.getImportedJenkinsParameterNames(buildType.getExternalId()));
      String currentSnapshot = JenkinsTeamCityRunParameterFactory.snapshot(definitions);

      mirrorStore.saveImportedJenkinsParameterNames(
          buildType.getExternalId(), teamCityResult.getImportedNames());
      mirrorStore.saveImportedJenkinsParameterSnapshot(buildType.getExternalId(), currentSnapshot);

      return new SynchronizationResult(
          definitions,
          previousSnapshot,
          currentSnapshot,
          teamCityResult.isChanged(),
          teamCityResult.getSynchronizedCount());
    } catch (IOException e) {
      throw new IllegalStateException("Could not persist Jenkins parameter state for "
          + buildType.getExternalId(), e);
    }
  }

  @Nullable
  private static SBuildFeatureDescriptor featureOf(@NotNull SBuildType buildType) {
    return buildType.getBuildFeaturesOfType(BridgeBuildFeatureConstants.TYPE)
        .stream().findFirst().orElse(null);
  }

  public static final class SynchronizationResult {
    private final JenkinsJobParameters definitions;
    private final String previousSnapshot;
    private final String currentSnapshot;
    private final boolean teamCityChanged;
    private final int synchronizedCount;

    private SynchronizationResult(JenkinsJobParameters definitions, String previousSnapshot,
                                  String currentSnapshot, boolean teamCityChanged,
                                  int synchronizedCount) {
      this.definitions = definitions;
      this.previousSnapshot = previousSnapshot;
      this.currentSnapshot = currentSnapshot;
      this.teamCityChanged = teamCityChanged;
      this.synchronizedCount = synchronizedCount;
    }

    private static SynchronizationResult empty() {
      return new SynchronizationResult(JenkinsJobParameters.empty(), null, null, false, 0);
    }

    @NotNull
    public JenkinsJobParameters getDefinitions() {
      return definitions;
    }

    @Nullable
    public String getPreviousSnapshot() {
      return previousSnapshot;
    }

    @Nullable
    public String getCurrentSnapshot() {
      return currentSnapshot;
    }

    public boolean isDefinitionChanged() {
      return previousSnapshot != null && !previousSnapshot.equals(currentSnapshot);
    }

    public boolean isTeamCityChanged() {
      return teamCityChanged;
    }

    public int getSynchronizedCount() {
      return synchronizedCount;
    }
  }
}
