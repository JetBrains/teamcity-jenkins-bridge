package com.jetbrains.teamcity.jenkinsbridge.feature;

import com.jetbrains.teamcity.jenkinsbridge.jenkins.JenkinsClient;
import com.jetbrains.teamcity.jenkinsbridge.model.JenkinsJobParameters;
import com.jetbrains.teamcity.jenkinsbridge.model.JenkinsParameterDefinition;
import com.jetbrains.teamcity.jenkinsbridge.jenkins.JenkinsJob;
import jetbrains.buildServer.serverSide.BuildTypeOptions;
import jetbrains.buildServer.serverSide.DuplicateBuildTypeNameException;
import jetbrains.buildServer.serverSide.ProjectManager;
import jetbrains.buildServer.serverSide.SBuildFeatureDescriptor;
import jetbrains.buildServer.serverSide.SBuildType;
import jetbrains.buildServer.serverSide.SProject;
import jetbrains.buildServer.serverSide.parameters.ParameterFactory;

import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.jetbrains.teamcity.jenkinsbridge.util.Utilities.describeException;
import static com.jetbrains.teamcity.jenkinsbridge.util.Utilities.lastPathSegment;

/**
 * Creates one TeamCity build configuration per selected Jenkins job under a target project, each
 * carrying the Jenkins Bridge build feature so the poller mirrors it. Idempotent: jobs already
 * mirrored by a configuration in the target project (or its subprojects) are skipped.
 *
 * <p>A selected multibranch pipeline is expanded: a subproject is created under the target project
 * with one build configuration per branch, each marked as a multibranch pipeline job so its branch
 * is pinned from the Jenkins job name.
 *
 * <p>Runs under the caller's security context (the import controller invokes this on the web request
 * thread of the logged-in user), so TeamCity enforces the user's edit permission on the project.
 */
public class JenkinsJobImporter {
  // Configuration parameter that marks a build as agentless (matches TeamCityBuildQueuer). Imported
  // configs carry it by default, so a manual run is also agentless rather than waiting for an agent.
  private static final String AGENTLESS_PARAM = "teamcity.build.agentLess";

  private final ProjectManager projectManager;
  private final ParameterFactory parameterFactory;
  private final JenkinsClient jenkinsClient;

  public JenkinsJobImporter(ProjectManager projectManager, ParameterFactory parameterFactory,
                            JenkinsClient jenkinsClient) {
    this.projectManager = projectManager;
    this.parameterFactory = parameterFactory;
    this.jenkinsClient = jenkinsClient;
  }

  public ImportResult importJobs(String targetProjectExternalId, List<String> jenkinsJobFullNames) {
    SProject project = projectManager.findProjectByExternalId(targetProjectExternalId);
    if (project == null) {
      throw new IllegalArgumentException("Target project not found: " + targetProjectExternalId);
    }

    Map<String, SBuildType> alreadyMirrored = collectMirroredBuildTypes(project);
    ImportResult result = new ImportResult();

    for (String rawFullName : jenkinsJobFullNames) {
      String fullName = rawFullName == null ? "" : rawFullName.trim();
      if (fullName.isEmpty()) {
        continue;
      }
      SBuildType existingBuildType = alreadyMirrored.get(fullName);
      if (existingBuildType != null) {
        try {
          int importedParameters = importJenkinsParameters(existingBuildType, fullName);
          if (importedParameters > 0) {
            existingBuildType.persist();
            result.addSkipped(fullName, "already imported; added " + importedParameters + " Jenkins parameter(s)");
          } else {
            result.addSkipped(fullName, "already imported");
          }
        } catch (Exception e) {
          result.addFailed(fullName, e.getClass().getSimpleName()
              + (e.getMessage() == null ? "" : ": " + e.getMessage()));
        }
        continue;
      }

      try {
        // TODO: Check if this API call is needed, or if we can fetch the class from elsewhere
        boolean isMultiBranch = JenkinsJob.isMultibranchClass(jenkinsClient.getJobClass(fullName));
        importJob(project, fullName, isMultiBranch, alreadyMirrored, result);
      } catch (Exception e) {
        result.addFailed(fullName, describeException(e));
      }
    }

    return result;
  }

  private void importJob(SProject project, String fullName, boolean isMultibranch,
                         Set<String> alreadyMirrored, ImportResult result) throws Exception {
    if (alreadyMirrored.contains(fullName)) {
      result.addSkipped(fullName, "already imported");
      return;
    }
    String externalId = createMirrorConfig(project, fullName, isMultibranch);
    alreadyMirrored.add(fullName);
    result.addCreated(fullName, externalId);
  }

  // Creates a mirror configuration for a single Jenkins job and returns its external id.
  private String createMirrorConfig(SProject project, String fullName, boolean inMultibranchPipeline)
      throws Exception {
    String externalId = ExternalIdGenerator.resolveUnique(
        ExternalIdGenerator.baseExternalId(project.getExternalId(), fullName),
        candidate -> projectManager.findBuildTypeByExternalId(candidate) != null);

    SBuildType buildType = createBuildType(project, externalId, fullName);
    Map<String, String> featureParams = new LinkedHashMap<>();
    featureParams.put(BridgeBuildFeatureConstants.PARAM_JENKINS_JOB, fullName);
    featureParams.put(BridgeBuildFeatureConstants.PARAM_JENKINS_URL, jenkinsClient.jobUrl(fullName));
    if (inMultibranchPipeline) {
      featureParams.put(BridgeBuildFeatureConstants.PARAM_IN_MULTIBRANCH, "true");
    }
    buildType.addBuildFeature(BridgeBuildFeatureConstants.TYPE, featureParams);
    buildType.addConfigParameter(parameterFactory.createSimpleParameter(AGENTLESS_PARAM, "true"));
    importJenkinsParameters(buildType, fullName);
    buildType.setOption(BuildTypeOptions.BT_FAIL_IF_TESTS_FAIL, false); // Let Jenkins decide if failing tests fail the build. Not the case for "unstable" builds.
    buildType.persist();
    return externalId;
  }

  // Use the job's name (last path segment) as the display name; fall back to the full path if taken.
  private SBuildType createBuildType(SProject project, String externalId, String fullName) {
    String jobName = lastPathSegment(fullName);
    try {
      return project.createBuildType(externalId, jobName);
    } catch (DuplicateBuildTypeNameException nameTaken) {
      return project.createBuildType(externalId, fullName);
    }
  }

  /** Jenkins job paths already mirrored by a configuration in the given project (empty if unknown). */
  public Set<String> alreadyMirroredJobs(String projectExternalId) {
    SProject project = projectManager.findProjectByExternalId(projectExternalId);
    return project == null ? Collections.emptySet() : collectMirroredJobs(project);
  }

  private Set<String> collectMirroredJobs(SProject project) {
    Set<String> jobs = new HashSet<>();
    jobs.addAll(collectMirroredBuildTypes(project).keySet());
    return jobs;
  }

  private Map<String, SBuildType> collectMirroredBuildTypes(SProject project) {
    Set<String> jobs = new HashSet<String>();
    Map<String, SBuildType> buildTypes = new LinkedHashMap<String, SBuildType>();
    for (SBuildType buildType : project.getBuildTypes()) {
      for (SBuildFeatureDescriptor descriptor
          : buildType.getBuildFeaturesOfType(BridgeBuildFeatureConstants.TYPE)) {
        String job = descriptor.getParameters().get(BridgeBuildFeatureConstants.PARAM_JENKINS_JOB);
        if (job != null && !job.trim().isEmpty()) {
          String normalizedJob = job.trim();
          if (jobs.add(normalizedJob)) {
            buildTypes.put(normalizedJob, buildType);
          }
        }
      }
    }
    return buildTypes;
  }

  private int importJenkinsParameters(SBuildType buildType, String fullName) throws Exception {
    JenkinsJobParameters parameters = jenkinsClient.getJobParameters(fullName);
    int imported = 0;
    for (JenkinsParameterDefinition definition : parameters.getParameters()) {
      String name = definition.getName();
      if (!JenkinsTeamCityRunParameterFactory.canImport(definition)
          || buildType.getConfigParameters().containsKey(name)) {
        continue;
      }
      buildType.addConfigParameter(JenkinsTeamCityRunParameterFactory.create(parameterFactory, definition));
      imported++;
    }
    return imported;
  }

  private static String leafName(String fullName) {
    int slash = fullName.lastIndexOf('/');
    return slash >= 0 && slash < fullName.length() - 1 ? fullName.substring(slash + 1) : fullName;
  }
}
