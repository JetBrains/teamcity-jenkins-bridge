package com.jetbrains.teamcity.jenkinsbridge.persistence;

import com.jetbrains.teamcity.jenkinsbridge.model.JenkinsBuildInfo;
import jetbrains.buildServer.serverSide.CustomDataStorage;
import jetbrains.buildServer.serverSide.BuildsManager;
import jetbrains.buildServer.serverSide.ProjectManager;

import com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.jetbrains.teamcity.jenkinsbridge.util.Utilities.nullToEmpty;

public class BuildMirrorStore {
  private static final Logger LOG = Logger.getInstance(BuildMirrorStore.class.getName());
  public static final String CUSTOM_DATA_STORAGE_NAME = "jenkinsBridgeStateStorage";

  private final ProjectManager projectManager;
  @Nullable
  private CustomDataStorage myStorage;
  private BridgeState state;

  public BuildMirrorStore(ProjectManager projectManager) {
    this.projectManager = projectManager;
  }

  private void initializeCustomDataStorage() {
    if (myStorage == null) {
      myStorage = projectManager.getRootProject().getCustomDataStorage(CUSTOM_DATA_STORAGE_NAME);
    }
    if (state == null) {
      state = new BridgeState(myStorage);
    }
  }

  /**
   * Must be initialized lazily (not in the constructor) because the root project is not available
   * when the Spring context is initialized.
   * <p>
   * Should be used instead of the normal {@code myStorage} field.
   */
  @NotNull
  private CustomDataStorage getCustomDataStorage() {
    if (myStorage == null) {
      initializeCustomDataStorage();
    }
    return myStorage;
  }

  public synchronized BuildMirror getOrCreateMirror(String mirrorKey, String jobName,
                                                    String teamCityBuildTypeExternalId,
                                                    JenkinsBuildInfo jenkinsInfo) throws IOException {
    ensureStateIsLoaded();

    BuildMirror mirror = state.getBuilds().get(mirrorKey);
    if (mirror == null) {
      mirror = BuildMirror.create(mirrorKey, jobName, jenkinsInfo, teamCityBuildTypeExternalId, now());
      state.putBuild(mirrorKey, mirror);
      return mirror;
    }

    boolean changed = false;
    if (!nullToEmpty(jenkinsInfo.getUrl()).equals(nullToEmpty(mirror.getJenkinsBuildUrl()))) {
      mirror.setJenkinsBuildUrl(jenkinsInfo.getUrl());
      changed = true;
    }
    if (!nullToEmpty(teamCityBuildTypeExternalId).equals(nullToEmpty(mirror.getTeamCityBuildTypeId()))) {
      mirror.setTeamCityBuildTypeId(teamCityBuildTypeExternalId);
      changed = true;
    }

    if (changed) {
      saveMirror(mirror);
    }

    return mirror;
  }

  public synchronized void saveMirror(BuildMirror mirror) throws IOException {
    ensureStateIsLoaded();
    mirror.setUpdatedAt(now());
    state.putBuild(mirror.getJenkinsBuildKey(), mirror);
  }

  public synchronized void savePendingTrigger(PendingTrigger pendingTrigger) throws IOException {
    ensureStateIsLoaded();
    PendingTrigger existing = findPendingTriggerInternal(pendingTrigger.getJenkinsController(), pendingTrigger.getJenkinsQueueId());
    if (pendingTrigger.getJenkinsQueueId() >= 0 && existing != null
        && existing.getTeamCityPromotionId() != pendingTrigger.getTeamCityPromotionId()) {
      throw new IllegalStateException("Jenkins queue item " + pendingTrigger.getJenkinsQueueId()
          + " is already owned by TeamCity promotion " + existing.getTeamCityPromotionId());
    }
    state.putPendingTrigger(pendingTriggerKey(pendingTrigger.getTeamCityPromotionId()), pendingTrigger);
  }

  public synchronized PendingTrigger findPendingTrigger(String controller, long queueId) throws IOException {
    ensureStateIsLoaded();
    return findPendingTriggerInternal(controller, queueId);
  }

  @Nullable
  public synchronized PendingTrigger findPendingTriggerByCause(
      @NotNull String controller, @NotNull String job, @Nullable String cause)
      throws IOException {
    ensureStateIsLoaded();
    if (cause == null || cause.trim().isEmpty()) return null;
    for (PendingTrigger pending : state.getPendingTriggers().values()) {
      if (cause.equals(pending.getTriggerCause())
          && nullToEmpty(controller).equals(nullToEmpty(pending.getJenkinsController()))
          && nullToEmpty(job).equals(nullToEmpty(pending.getJenkinsJob()))) return pending;
    }
    return null;
  }

  private PendingTrigger findPendingTriggerInternal(String controller, long queueId)
      throws BridgeStateCorruptionException {
    if (queueId < 0) {
      return null;
    }
    for (PendingTrigger pending : state.getPendingTriggers().values()) {
      if (queueId == pending.getJenkinsQueueId()
          && nullToEmpty(controller).equals(nullToEmpty(pending.getJenkinsController()))) {
        return pending;
      }
    }
    return null;
  }

  public synchronized List<PendingTrigger> getPendingTriggers() throws IOException {
    ensureStateIsLoaded();
    return new ArrayList<PendingTrigger>(state.getPendingTriggers().values());
  }

  public synchronized void removePendingTrigger(long teamCityPromotionId) throws IOException {
    try {
      ensureStateIsLoaded();
      state.removePendingTrigger(pendingTriggerKey(teamCityPromotionId));
    } catch (IOException e) {
      LOG.error("Jenkins Bridge could not remove pending TeamCity promotion "
          + teamCityPromotionId + "; pending state may remain", e);
      throw new IOException("Could not remove pending trigger for TeamCity promotion " + teamCityPromotionId, e);
    }
  }

  /**
   * Returns the mirror for the given key, or {@code null} if none exists. Does not create one.
   */
  @Nullable
  public synchronized BuildMirror findMirror(@NotNull String key) throws IOException {
    ensureStateIsLoaded();
    return state.getBuilds().get(key);
  }

  /**
   * Returns the mirror whose {@code teamCityBuildId} equals {@code tcBuildId}, or {@code null}.
   * Linear scan — used as a fallback when the fast {@code jenkins.build.key} lookup misses (TC-first
   * builds, which never carry that parameter). {@code null} and the deferred sentinel (-1) never
   * match a real build/promotion id.
   */
  @Nullable
  public synchronized BuildMirror findMirrorByTcBuildId(long tcBuildId) throws IOException {
    ensureStateIsLoaded();
    for (BuildMirror mirror : state.getBuilds().values()) {
      Long id = mirror.getTeamCityBuildId();
      if (id != null && id == tcBuildId) {
        return mirror;
      }
    }
    return null;
  }

  @Nullable
  public synchronized BuildResultMetadata findResultMetadataByJenkinsBuildKey(@NotNull String key)
      throws IOException {
    ensureStateIsLoaded();
    for (BuildResultMetadata metadata : state.getResultMetadata().values()) {
      if (key.equals(metadata.getJenkinsBuildKey())) {
        return metadata;
      }
    }
    return null;
  }

  @Nullable
  public synchronized BuildResultMetadata findResultMetadataByTeamCityBuildId(long buildId)
      throws IOException {
    ensureStateIsLoaded();
    BuildResultMetadata metadata = state.getResultMetadata().get(Long.toString(buildId));
    return metadata;
  }

  @NotNull
  public synchronized Set<Long> getResultMetadataBuildIds() throws IOException {
    ensureStateIsLoaded();
    Set<Long> ids = new java.util.HashSet<Long>();
    for (String id : state.getResultMetadata().keySet()) {
      try {
        ids.add(Long.valueOf(id));
      } catch (NumberFormatException e) {
        LOG.error("Ignoring invalid Jenkins Bridge result metadata key " + id, e);
      }
    }
    return ids;
  }

  /** Removes metadata whose TeamCity build no longer exists. Intended for periodic poll-cycle cleanup. */
  public synchronized int removeOrphanedResultMetadata(@NotNull BuildsManager buildsManager) throws IOException {
    Set<Long> orphanedIds = new java.util.HashSet<Long>();
    for (Long buildId : getResultMetadataBuildIds()) {
      if (buildsManager.findBuildInstanceById(buildId) == null) {
        orphanedIds.add(buildId);
      }
    }
    if (orphanedIds.isEmpty()) {
      return 0;
    }
    removeResultMetadata(orphanedIds);
    return orphanedIds.size();
  }

  public synchronized void saveResultMetadata(BuildMirror mirror) throws IOException {
    ensureStateIsLoaded();
    Long buildId = mirror.getTeamCityBuildId();
    if (buildId == null) {
      LOG.warn("Jenkins Bridge cannot persist result metadata for mirror "
          + mirror.getJenkinsBuildKey() + " because it has no TeamCity build ID");
      return;
    }
    state.putResultMetadata(Long.toString(buildId), BuildResultMetadata.from(mirror));
  }

  public synchronized void removeResultMetadata(@NotNull Collection<Long> buildIds) throws IOException {
    ensureStateIsLoaded();
    List<String> ids = new ArrayList<String>();
    for (Long id : buildIds) {
      if (id != null) {
        ids.add(Long.toString(id));
      }
    }
    state.removeResultMetadata(ids);
  }

  public synchronized int getMirrorCount() throws IOException {
    ensureStateIsLoaded();
    return state.getBuilds().size();
  }

  @NotNull
  public synchronized Set<String> getFinishedMirrorMappings(@NotNull Set<String> mappingKeys)
      throws IOException {
    ensureStateIsLoaded();
    Set<String> result = new java.util.HashSet<String>();
    for (BuildMirror mirror : state.getBuilds().values()) {
      String mappingKey = mirror.getTeamCityBuildTypeId() + "::" + mirror.getJenkinsJob();
      if (mirror.getSyncState() == SyncState.TEAMCITY_FINISHED && mappingKeys.contains(mappingKey)) {
        result.add(mappingKey);
      }
    }
    return result;
  }

  public synchronized List<BuildMirror> pruneFinishedMirrors(@NotNull Set<String> mappingKeys)
      throws IOException {
    ensureStateIsLoaded();
    List<BuildMirror> pruned = new ArrayList<BuildMirror>();
    List<String> keys = new ArrayList<String>();
    for (Map.Entry<String, BuildMirror> entry : state.getBuilds().entrySet()) {
      BuildMirror mirror = entry.getValue();
      String mappingKey = mirror.getTeamCityBuildTypeId() + "::" + mirror.getJenkinsJob();
      if (mirror.getSyncState() == SyncState.TEAMCITY_FINISHED && mappingKeys.contains(mappingKey)) {
        saveResultMetadata(mirror);
        pruned.add(mirror);
        keys.add(entry.getKey());
      }
    }
    state.removeBuilds(keys);
    return pruned;
  }

  public synchronized String getLastPruned(String mappingKey) throws IOException {
    ensureStateIsLoaded();
    return state.getLastPruned(mappingKey);
  }

  public synchronized void setLastPruned(String mappingKey, String timestamp) throws IOException {
    ensureStateIsLoaded();
    state.setLastPruned(mappingKey, timestamp);
  }

  /**
   * Returns mirrors for the job that have not yet reached {@code TEAMCITY_FINISHED}.
   */
  public synchronized List<BuildMirror> getActiveMirrors(String jobName) throws IOException {
    ensureStateIsLoaded();
    List<BuildMirror> active = new ArrayList<>();
    for (BuildMirror mirror : state.getBuilds().values()) {
      if (jobName.equals(mirror.getJenkinsJob())
          && mirror.getSyncState() != SyncState.TEAMCITY_FINISHED) {
        active.add(mirror);
      }
    }
    return active;
  }

  /**
   * Returns mirrors for the (target build type, job) pair that have not yet reached
   * {@code TEAMCITY_FINISHED}. Used by feature-derived mappings, so two configs mirroring the same
   * Jenkins job are tracked independently.
   */
  public synchronized List<BuildMirror> getActiveMirrors(String teamCityBuildTypeExternalId, String jobName)
      throws IOException {
    ensureStateIsLoaded();
    List<BuildMirror> active = new ArrayList<>();
    for (BuildMirror mirror : state.getBuilds().values()) {
      if (jobName.equals(mirror.getJenkinsJob())
          && teamCityBuildTypeExternalId.equals(mirror.getTeamCityBuildTypeId())
          && mirror.getSyncState() != SyncState.TEAMCITY_FINISHED) {
        active.add(mirror);
      }
    }
    return active;
  }

  public synchronized int getLastSeenBuildNumber(String jobName) throws IOException {
    ensureStateIsLoaded();
    Integer value = state.getLastSeenBuildNumbers().get(jobName);
    return value == null ? 0 : value;
  }

  public synchronized void setLastSeenBuildNumber(String jobName, int buildNumber) throws IOException {
    ensureStateIsLoaded();
    Integer current = state.getLastSeenBuildNumbers().get(jobName);
    if (current != null && current >= buildNumber) {
      return;
    }
    state.putLastSeenBuildNumber(jobName, buildNumber);
  }

  public synchronized void markBuildError(BuildMirror mirror, Exception error) throws IOException {
    mirror.setSyncState(SyncState.FAILED_TO_SYNC);
    mirror.setLastError(error.getMessage());
    saveMirror(mirror);
  }

  public synchronized void markPollSuccess() throws IOException {
    ensureStateIsLoaded();
    state.setLastPollTime(now());
    state.setLastError(null);
  }

  public synchronized void markPollError(Exception error) throws IOException {
    ensureStateIsLoaded();
    state.setLastPollTime(now());
    state.setLastError(error.getMessage());
  }

  @NotNull
  public synchronized Set<String> getImportedJenkinsParameterNames(@NotNull String buildTypeExternalId)
      throws IOException {
    ensureStateIsLoaded();
    return state.getImportedJenkinsParameterNames(buildTypeExternalId);
  }

  public synchronized void saveImportedJenkinsParameterNames(@NotNull String buildTypeExternalId,
                                                              @NotNull Set<String> names) throws IOException {
    ensureStateIsLoaded();
    state.putImportedJenkinsParameterNames(buildTypeExternalId, names);
  }

  @Nullable
  public synchronized String getImportedJenkinsParameterSnapshot(@NotNull String buildTypeExternalId)
      throws IOException {
    ensureStateIsLoaded();
    return state.getImportedJenkinsParameterSnapshot(buildTypeExternalId);
  }

  public synchronized void saveImportedJenkinsParameterSnapshot(@NotNull String buildTypeExternalId,
                                                                 @NotNull String snapshot) throws IOException {
    ensureStateIsLoaded();
    state.putImportedJenkinsParameterSnapshot(buildTypeExternalId, snapshot);
  }

  public static String buildKey(String jobName, int buildNumber) {
    return jobName + "#" + buildNumber;
  }

  public static String buildKey(String jobName, JenkinsBuildInfo jenkinsInfo) {
    if (jenkinsInfo == null || jenkinsInfo.getTimestamp() <= 0L) {
      return buildKey(jobName, jenkinsInfo == null ? 0 : jenkinsInfo.getNumber());
    }
    return buildKey(jobName, jenkinsInfo.getNumber()) + "@" + jenkinsInfo.getTimestamp();
  }

  public static String legacyBuildKey(String buildKey) {
    if (buildKey == null) {
      return "";
    }
    int timestampSeparator = buildKey.lastIndexOf('@');
    return timestampSeparator < 0 ? buildKey : buildKey.substring(0, timestampSeparator);
  }

  private static String pendingTriggerKey(long teamCityPromotionId) {
    return Long.toString(teamCityPromotionId);
  }

  private void ensureStateIsLoaded() throws IOException {
    initializeCustomDataStorage();
    Map<String, String> storedValues = getCustomDataStorage().getValues();
    if (storedValues == null || storedValues.isEmpty()) {
      state.setVersion(2);
      return;
    }

    try {
      state.getVersion();
      state.getBuilds();
      state.getResultMetadata();
      state.getPendingTriggers();
      state.getLastSeenBuildNumbers();
      state.getLastPollTime();
      state.getLastError();
    } catch (BridgeStateCorruptionException e) {
      // Do not erase state we cannot prove is disposable. The caller receives a typed storage
      // failure and the existing state remains available for diagnosis or manual recovery.
      LOG.warn("Jenkins Bridge state is corrupt; preserving it and stopping this operation", e);
      throw e;
    }
  }

  private String now() {
    return Instant.now().toString();
  }

}
