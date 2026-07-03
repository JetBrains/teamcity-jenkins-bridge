package com.jetbrains.teamcity.jenkinsbridge.feature;

import com.jetbrains.teamcity.jenkinsbridge.jenkins.JenkinsClient;
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

/**
 * Creates one TeamCity build configuration per selected Jenkins job under a target project, each
 * carrying the Jenkins Bridge build feature so the poller mirrors it. Idempotent: jobs already
 * mirrored by a configuration in the target project are skipped.
 *
 * <p>Runs under the caller's security context (the import controller invokes this on the web request
 * thread of the logged-in user), so TeamCity enforces the user's edit permission on the project.
 */
public class JenkinsJobImporter {
  // Configuration parameter that marks a build as agentless (matches TeamCityBuildQueuer). Imported
  // configs carry it by default so a manual run is also agentless rather than waiting for an agent.
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

    Set<String> alreadyMirrored = collectMirroredJobs(project);
    ImportResult result = new ImportResult();

    for (String rawFullName : jenkinsJobFullNames) {
      String fullName = rawFullName == null ? "" : rawFullName.trim();
      if (fullName.length() == 0) {
        continue;
      }
      if (alreadyMirrored.contains(fullName)) {
        result.addSkipped(fullName, "already imported");
        continue;
      }

      try {
        String externalId = ExternalIdGenerator.resolveUnique(
            ExternalIdGenerator.baseExternalId(project.getExternalId(), fullName),
            candidate -> projectManager.findBuildTypeByExternalId(candidate) != null);

        SBuildType buildType = createBuildType(project, externalId, fullName);
        Map<String, String> featureParams = new LinkedHashMap<String, String>();
        featureParams.put(BridgeBuildFeatureConstants.PARAM_JENKINS_JOB, fullName);
        featureParams.put(BridgeBuildFeatureConstants.PARAM_JENKINS_URL, jenkinsClient.jobUrl(fullName));
        buildType.addBuildFeature(BridgeBuildFeatureConstants.TYPE, featureParams);
        buildType.addConfigParameter(parameterFactory.createSimpleParameter(AGENTLESS_PARAM, "true"));
        buildType.setOption(BuildTypeOptions.BT_FAIL_IF_TESTS_FAIL, false); // Let Jenkins decide if failing tests fail the build. Not the case for "unstable" builds.
        buildType.persist();

        alreadyMirrored.add(fullName);
        result.addCreated(fullName, externalId);
      } catch (Exception e) {
        result.addFailed(fullName, e.getClass().getSimpleName()
            + (e.getMessage() == null ? "" : ": " + e.getMessage()));
      }
    }

    return result;
  }

  // Use the job's leaf segment as the display name; fall back to the full path if that name is taken.
  private SBuildType createBuildType(SProject project, String externalId, String fullName)
      throws Exception {
    String leafName = leafName(fullName);
    try {
      return project.createBuildType(externalId, leafName);
    } catch (DuplicateBuildTypeNameException nameTaken) {
      return project.createBuildType(externalId, fullName);
    }
  }

  /** Jenkins job paths already mirrored by a configuration in the given project (empty if unknown). */
  public Set<String> alreadyMirroredJobs(String projectExternalId) {
    SProject project = projectManager.findProjectByExternalId(projectExternalId);
    return project == null ? Collections.<String>emptySet() : collectMirroredJobs(project);
  }

  private Set<String> collectMirroredJobs(SProject project) {
    Set<String> jobs = new HashSet<String>();
    for (SBuildType buildType : project.getBuildTypes()) {
      for (SBuildFeatureDescriptor descriptor
          : buildType.getBuildFeaturesOfType(BridgeBuildFeatureConstants.TYPE)) {
        String job = descriptor.getParameters().get(BridgeBuildFeatureConstants.PARAM_JENKINS_JOB);
        if (job != null && job.trim().length() > 0) {
          jobs.add(job.trim());
        }
      }
    }
    return jobs;
  }

  private static String leafName(String fullName) {
    int slash = fullName.lastIndexOf('/');
    return slash >= 0 && slash < fullName.length() - 1 ? fullName.substring(slash + 1) : fullName;
  }
}
