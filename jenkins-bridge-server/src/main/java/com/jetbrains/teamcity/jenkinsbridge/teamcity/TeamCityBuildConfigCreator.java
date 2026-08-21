package com.jetbrains.teamcity.jenkinsbridge.teamcity;

import com.jetbrains.teamcity.jenkinsbridge.feature.BridgeBuildFeatureConstants;
import jetbrains.buildServer.serverSide.BuildTypeOptions;
import jetbrains.buildServer.serverSide.DuplicateBuildTypeNameException;
import jetbrains.buildServer.serverSide.SBuildType;
import jetbrains.buildServer.serverSide.SProject;
import jetbrains.buildServer.serverSide.parameters.ParameterFactory;

import java.util.LinkedHashMap;
import java.util.Map;

import static com.jetbrains.teamcity.jenkinsbridge.util.Utilities.lastPathSegment;

/**
 * Creates the TeamCity build configuration owned by one Jenkins mirror.
 *
 * <p>This class is deliberately limited to TeamCity configuration mutations. Jenkins HTTP
 * discovery, external-id selection, and build-parameter retrieval remain in the importer. The
 * returned configuration is not persisted here because the caller may need to add additional
 * TeamCity parameters before establishing the persistence boundary.</p>
 *
 * <p>Generated configurations are agentless, carry the bridge feature metadata, and are marked
 * read-only in the TeamCity UI. The read-only flag protects the generated configuration from
 * accidental user edits; it does not replace the importer or project permission checks.</p>
 */
public class TeamCityBuildConfigCreator {
  private static final String AGENTLESS_PARAM = "teamcity.build.agentLess";
  private static final String READ_ONLY_PARAM = "teamcity.ui.settings.readOnly";
  private static final String HIDDEN_SPEC = "text display='hidden'";

  private final ParameterFactory parameterFactory;

  public TeamCityBuildConfigCreator(ParameterFactory parameterFactory) {
    this.parameterFactory = parameterFactory;
  }

  /**
   * Creates and configures a mirror build type, using the Jenkins leaf name when available and
   * falling back to the full Jenkins path if that display name is already used in the project.
   *
   * @param project target TeamCity project
   * @param externalId collision-free TeamCity external id selected by the importer
   * @param fullName full Jenkins job path, also stored as bridge metadata
   * @param connectionId Jenkins connection identifier stored on the bridge feature
   * @param jenkinsUrl canonical Jenkins job URL stored on the bridge feature
   * @param jenkinsType Jenkins job implementation class, when available
   * @param multibranch whether the Jenkins job represents a multibranch pipeline
   * @return the newly created, configured, but not-yet-persisted build type
   */
  public SBuildType createMirrorBuildType(
      SProject project,
      String externalId,
      String fullName,
      String connectionId,
      String jenkinsUrl,
      String jenkinsType,
      boolean multibranch
  ) {
    SBuildType buildType = createBuildType(project, externalId, fullName);
    Map<String, String> featureParams = new LinkedHashMap<>();
    featureParams.put(BridgeBuildFeatureConstants.PARAM_CONNECTION_ID, connectionId);
    featureParams.put(BridgeBuildFeatureConstants.PARAM_JENKINS_JOB, fullName);
    featureParams.put(BridgeBuildFeatureConstants.PARAM_JENKINS_URL, jenkinsUrl);
    if (!jenkinsType.isEmpty()) {
      featureParams.put(BridgeBuildFeatureConstants.PARAM_JENKINS_TYPE, jenkinsType);
    }
    buildType.addBuildFeature(BridgeBuildFeatureConstants.TYPE, featureParams);
    buildType.addConfigParameter(parameterFactory.createTypedParameter(AGENTLESS_PARAM, "true", HIDDEN_SPEC));
    buildType.addConfigParameter(parameterFactory.createSimpleParameter(
        BridgeBuildFeatureConstants.INTERNAL_MULTIBRANCH_PARAM, String.valueOf(multibranch)));
    buildType.addConfigParameter(parameterFactory.createTypedParameter(READ_ONLY_PARAM, "true", HIDDEN_SPEC));
    buildType.setOption(BuildTypeOptions.BT_FAIL_IF_TESTS_FAIL, false);
    return buildType;
  }

  private SBuildType createBuildType(SProject project, String externalId, String fullName) {
    String jobName = lastPathSegment(fullName);
    try {
      return project.createBuildType(externalId, jobName);
    } catch (DuplicateBuildTypeNameException nameTaken) {
      return project.createBuildType(externalId, fullName);
    }
  }
}
