package com.jetbrains.teamcity.jenkinsbridge.feature;

import com.jetbrains.teamcity.jenkinsbridge.http.BridgeHttpException;
import com.jetbrains.teamcity.jenkinsbridge.jenkins.JenkinsClient;
import com.jetbrains.teamcity.jenkinsbridge.jenkins.JenkinsClientFactory;
import com.jetbrains.teamcity.jenkinsbridge.jenkins.JenkinsDataException;
import com.jetbrains.teamcity.jenkinsbridge.jenkins.JenkinsJob;
import com.jetbrains.teamcity.jenkinsbridge.teamcity.TeamCityBuildConfigCreator;
import com.jetbrains.teamcity.jenkinsbridge.util.TeamCityUiSettings;
import jetbrains.buildServer.serverSide.MultiNodeLocks;
import jetbrains.buildServer.serverSide.ProjectManager;
import jetbrains.buildServer.serverSide.SBuildFeatureDescriptor;
import jetbrains.buildServer.serverSide.SBuildType;
import jetbrains.buildServer.serverSide.SProject;
import jetbrains.buildServer.serverSide.ReadOnlyEntityException;
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
  private final ProjectManager projectManager;
  private final JenkinsClientFactory jenkinsClientFactory;
  private final TeamCityBuildConfigCreator buildConfigCreator;
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
    this.jenkinsClientFactory = jenkinsClientFactory;
    this.buildConfigCreator = new TeamCityBuildConfigCreator(parameterFactory);
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
    if (TeamCityUiSettings.isReadOnly(project)) {
      throw new ReadOnlyEntityException(
          "Cannot import Jenkins jobs because project settings are read-only: "
              + targetProjectExternalId);
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
    SBuildType buildType = buildConfigCreator.createMirrorBuildType(
        project, externalId, fullName, connectionId, jenkinsClient.jobUrl(fullName),
        jenkinsType, isMultibranch);
    buildType.schedulePersisting("Jenkins Bridge: persist imported Jenkins job build configuration").awaitUninterruptibly();

    return externalId;
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

}
