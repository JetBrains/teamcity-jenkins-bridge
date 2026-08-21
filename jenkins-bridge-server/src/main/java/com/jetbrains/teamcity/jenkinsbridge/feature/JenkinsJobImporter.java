package com.jetbrains.teamcity.jenkinsbridge.feature;

import com.jetbrains.teamcity.jenkinsbridge.http.BridgeHttpException;
import com.jetbrains.teamcity.jenkinsbridge.jenkins.JenkinsClient;
import com.jetbrains.teamcity.jenkinsbridge.jenkins.JenkinsClientFactory;
import com.jetbrains.teamcity.jenkinsbridge.jenkins.JenkinsDataException;
import com.jetbrains.teamcity.jenkinsbridge.model.JenkinsJobParameters;
import com.jetbrains.teamcity.jenkinsbridge.model.JenkinsParameterDefinition;
import com.jetbrains.teamcity.jenkinsbridge.jenkins.JenkinsJob;
import jetbrains.buildServer.serverSide.BuildTypeOptions;
import jetbrains.buildServer.serverSide.DuplicateBuildTypeNameException;
import jetbrains.buildServer.serverSide.MultiNodeLocks;
import jetbrains.buildServer.serverSide.ProjectManager;
import jetbrains.buildServer.serverSide.SBuildFeatureDescriptor;
import jetbrains.buildServer.serverSide.SBuildType;
import jetbrains.buildServer.serverSide.SProject;
import jetbrains.buildServer.serverSide.identifiers.IdentifiersUtil;
import jetbrains.buildServer.serverSide.parameters.ParameterFactory;
import com.intellij.openapi.diagnostic.Logger;

import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

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
  private static final Logger LOG = Logger.getInstance(JenkinsJobImporter.class.getName());
  // Configuration parameter that marks a build as agentless (matches TeamCityBuildQueuer). Imported
  // configs carry it by default, so a manual run is also agentless rather than waiting for an agent.
  private static final String AGENTLESS_PARAM = "teamcity.build.agentLess";
  // Hidden display so this technical parameter does not clutter the Run Custom Build dialog; hiding
  // affects only the dialog, not the value (isAgentLessBuild() still reads it).
  private static final String HIDDEN_SPEC = "text display='hidden'";

  private final ProjectManager projectManager;
  private final ParameterFactory parameterFactory;
  private final JenkinsClientFactory jenkinsClientFactory;
  private final MultiNodeLocks multiNodeLocks;

  private static final String IMPORT_LOCK_TYPE = "jenkinsBridgeImport";
  private static final long IMPORT_LOCK_TIMEOUT_MILLIS = 1_000L;

  public JenkinsJobImporter(ProjectManager projectManager, ParameterFactory parameterFactory,
                            JenkinsClientFactory jenkinsClientFactory) {
    this(projectManager, parameterFactory, jenkinsClientFactory, null);
  }

  public JenkinsJobImporter(ProjectManager projectManager, ParameterFactory parameterFactory,
                            JenkinsClientFactory jenkinsClientFactory, MultiNodeLocks multiNodeLocks) {
    this.projectManager = projectManager;
    this.parameterFactory = parameterFactory;
    this.jenkinsClientFactory = jenkinsClientFactory;
    this.multiNodeLocks = multiNodeLocks;
  }

  /**
   * Creates one build configuration per selected Jenkins job.
   *
   * @param targetProjectExternalId project the configurations are created in
   * @param connectionId            id of the Jenkins connection the jobs are read from
   * @param jenkinsJobFullNames     Jenkins job paths to import
   * @return what was created, skipped and failed
   */
  public ImportResult importJobs(String targetProjectExternalId, String connectionId,
                                 List<String> jenkinsJobFullNames) {
    SProject project = projectManager.findProjectByExternalId(targetProjectExternalId);
    if (project == null) {
      throw new IllegalArgumentException("Target project not found: " + targetProjectExternalId);
    }
    JenkinsClient jenkinsClient = jenkinsClientFactory.forConnectionId(project, connectionId);

    Set<String> alreadyMirroredJobs = collectMirroredJobs(project);
    ImportResult result = new ImportResult();

    for (String rawFullName : jenkinsJobFullNames) {
      String fullName = rawFullName == null ? "" : rawFullName.trim();
      if (fullName.isEmpty()) {
        continue;
      }
      if (alreadyMirroredJobs.contains(fullName)) {
        result.addSkipped(fullName, "already imported");
        continue;
      }

      try {
        importJobWithLock(project, connectionId, jenkinsClient, fullName, alreadyMirroredJobs, result);
      } catch (BridgeHttpException | JenkinsDataException e) {
        result.addFailed(fullName, describeException(e));
      }
    }

    return result;
  }

  private void importJobWithLock(SProject project, String connectionId, JenkinsClient jenkinsClient,
                                 String fullName, Set<String> alreadyMirrored, ImportResult result)
      throws BridgeHttpException, JenkinsDataException {
    // The three-argument constructor is retained for isolated legacy unit tests. The Spring bean
    // receives TeamCity's database-backed lock service, which is required for multi-node safety.
    if (multiNodeLocks == null) {
      importJob(project, connectionId, jenkinsClient, fullName, alreadyMirrored, result);
      return;
    }

    MultiNodeLocks.Lock lock;
    try {
      lock = multiNodeLocks.tryLock(IMPORT_LOCK_TYPE,
          lockId(project.getExternalId(), connectionId, fullName), IMPORT_LOCK_TIMEOUT_MILLIS);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      result.addFailed(fullName, "Import interrupted while waiting for another node");
      return;
    }
    if (lock == null) {
      LOG.info("Jenkins Bridge import lock was not acquired for project " + project.getExternalId()
          + ", connection " + connectionId + ", Jenkins job " + fullName);
      result.addFailed(fullName,
          "Another TeamCity node is currently importing this Jenkins job. Please refresh and try again.");
      return;
    }
    try {
      LOG.debug("Jenkins Bridge import lock acquired for project " + project.getExternalId()
          + ", connection " + connectionId + ", Jenkins job " + fullName);
      // The set collected before locking may be stale: another node can have completed the import
      // while this request was waiting. Always re-read the shared project state under the lock.
      Set<String> currentMirroredJobs = collectMirroredJobs(project);
      importJob(project, connectionId, jenkinsClient, fullName, currentMirroredJobs, result);
      alreadyMirrored.addAll(currentMirroredJobs);
    } finally {
      LOG.debug("Jenkins Bridge import lock released for project " + project.getExternalId()
          + ", connection " + connectionId + ", Jenkins job " + fullName);
      lock.close();
    }
  }

  private static long lockId(String projectExternalId, String connectionId, String fullName) {
    String key = projectExternalId + "\u0000" + connectionId + "\u0000" + fullName;
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256").digest(key.getBytes(java.nio.charset.StandardCharsets.UTF_8));
      return ByteBuffer.wrap(digest).getLong();
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is not available", impossible);
    }
  }

  private void importJob(SProject project, String connectionId, JenkinsClient jenkinsClient, String fullName,
                         Set<String> alreadyMirrored, ImportResult result)
      throws BridgeHttpException, JenkinsDataException {
    if (alreadyMirrored.contains(fullName)) {
      result.addSkipped(fullName, "already imported");
      return;
    }
    String externalId = createMirrorConfig(project, connectionId, jenkinsClient, fullName);
    alreadyMirrored.add(fullName);
    result.addCreated(fullName, externalId);
  }

  // Creates a mirror configuration for a single Jenkins job and returns its external id.
  private String createMirrorConfig(SProject project, String connectionId, JenkinsClient jenkinsClient,
                                    String fullName) throws BridgeHttpException, JenkinsDataException {
    String jenkinsType = jenkinsClient.getJobClass(fullName);
    boolean isMultibranch = JenkinsJob.isMultibranchClass(jenkinsType);
    String externalId = IdentifiersUtil.generateUniqueExternalIdByUserString(
        project.getExternalId(),
        fullName,
        false,
        candidate -> projectManager.findBuildTypeByExternalId(candidate) != null);

    SBuildType buildType = createBuildType(project, externalId, fullName);
    Map<String, String> featureParams = new LinkedHashMap<>();
    featureParams.put(BridgeBuildFeatureConstants.PARAM_CONNECTION_ID, connectionId);
    featureParams.put(BridgeBuildFeatureConstants.PARAM_JENKINS_JOB, fullName);
    featureParams.put(BridgeBuildFeatureConstants.PARAM_JENKINS_URL, jenkinsClient.jobUrl(fullName));
    if (!jenkinsType.isEmpty()) {
      featureParams.put(BridgeBuildFeatureConstants.PARAM_JENKINS_TYPE, jenkinsType);
    }
    buildType.addBuildFeature(BridgeBuildFeatureConstants.TYPE, featureParams);
    buildType.addConfigParameter(parameterFactory.createTypedParameter(AGENTLESS_PARAM, "true", HIDDEN_SPEC));
    buildType.addConfigParameter(parameterFactory.createSimpleParameter(
        BridgeBuildFeatureConstants.INTERNAL_MULTIBRANCH_PARAM, String.valueOf(isMultibranch)));
    importJenkinsParameters(jenkinsClient, buildType, fullName);
    buildType.setOption(BuildTypeOptions.BT_FAIL_IF_TESTS_FAIL, false); // Let Jenkins decide if failing tests fail the build. Not the case for "unstable" builds.
    buildType.schedulePersisting("Jenkins Bridge: persist imported Jenkins job build configuration").awaitUninterruptibly();
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

  /** Jenkins job classes persisted on imported bridge features, keyed by Jenkins full name. */
  public Map<String, String> alreadyMirroredJobTypes(String projectExternalId) {
    SProject project = projectManager.findProjectByExternalId(projectExternalId);
    if (project == null) {
      return Collections.emptyMap();
    }
    Map<String, String> types = new LinkedHashMap<>();
    for (SBuildFeatureDescriptor descriptor : allBridgeFeatures(project)) {
      Map<String, String> params = descriptor.getParameters();
      String job = params.get(BridgeBuildFeatureConstants.PARAM_JENKINS_JOB);
      if (job != null && !job.trim().isEmpty()) {
        types.put(job.trim(), params.get(BridgeBuildFeatureConstants.PARAM_JENKINS_TYPE));
      }
    }
    return types;
  }

  /** Jenkins connection ids persisted on imported bridge features, keyed by Jenkins full name. */
  public Map<String, String> alreadyMirroredJobConnections(String projectExternalId) {
    SProject project = projectManager.findProjectByExternalId(projectExternalId);
    if (project == null) {
      return Collections.emptyMap();
    }
    Map<String, String> connections = new LinkedHashMap<>();
    for (SBuildFeatureDescriptor descriptor : allBridgeFeatures(project)) {
      Map<String, String> params = descriptor.getParameters();
      String job = params.get(BridgeBuildFeatureConstants.PARAM_JENKINS_JOB);
      if (job != null && !job.trim().isEmpty()) {
        connections.put(job.trim(), params.get(BridgeBuildFeatureConstants.PARAM_CONNECTION_ID));
      }
    }
    return connections;
  }

  /** Jenkins job URLs persisted on imported bridge features, keyed by Jenkins full name. */
  public Map<String, String> alreadyMirroredJobUrls(String projectExternalId) {
    SProject project = projectManager.findProjectByExternalId(projectExternalId);
    if (project == null) {
      return Collections.emptyMap();
    }
    Map<String, String> urls = new LinkedHashMap<>();
    for (SBuildFeatureDescriptor descriptor : allBridgeFeatures(project)) {
      Map<String, String> params = descriptor.getParameters();
      String job = params.get(BridgeBuildFeatureConstants.PARAM_JENKINS_JOB);
      if (job != null && !job.trim().isEmpty()) {
        String url = params.get(BridgeBuildFeatureConstants.PARAM_JENKINS_URL);
        if (url != null && !url.trim().isEmpty()) {
          urls.put(job.trim(), url.trim());
        }
      }
    }
    return urls;
  }

  private Set<String> collectMirroredJobs(SProject project) {
    Set<String> jobs = new HashSet<>();
    for (SBuildType buildType : project.getBuildTypes()) {
      for (SBuildFeatureDescriptor descriptor
          : buildType.getBuildFeaturesOfType(BridgeBuildFeatureConstants.TYPE)) {
        String job = descriptor.getParameters().get(BridgeBuildFeatureConstants.PARAM_JENKINS_JOB);
        if (job != null && !job.trim().isEmpty()) {
          String normalizedJob = job.trim();
          jobs.add(normalizedJob);
        }
      }
    }
    return jobs;
  }

  private List<SBuildFeatureDescriptor> allBridgeFeatures(SProject project) {
    List<SBuildFeatureDescriptor> features = new java.util.ArrayList<>();
    for (SBuildType buildType : project.getBuildTypes()) {
      features.addAll(buildType.getBuildFeaturesOfType(BridgeBuildFeatureConstants.TYPE));
    }
    return features;
  }

  private int importJenkinsParameters(JenkinsClient jenkinsClient, SBuildType buildType, String fullName)
      throws BridgeHttpException, JenkinsDataException {
    JenkinsJobParameters parameters = jenkinsClient.getJobParameters(fullName);
    int imported = 0;
    for (JenkinsParameterDefinition definition : parameters.getParameters()) {
      String name = definition.getName();
      if (!JenkinsTeamCityRunParameterFactory.canImport(definition)) {
        continue;
      }
      if (buildType.getParametersProvider().get(name) != null) {
        buildType.removeParameter(name);
      }
      buildType.addParameter(JenkinsTeamCityRunParameterFactory.create(parameterFactory, definition));
      imported++;
    }
    return imported;
  }
  private static String leafName(String fullName) {
    int slash = fullName.lastIndexOf('/');
    return slash >= 0 && slash < fullName.length() - 1 ? fullName.substring(slash + 1) : fullName;
  }
}
