package com.jetbrains.teamcity.jenkinsbridge.persistence;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.jetbrains.teamcity.jenkinsbridge.model.JenkinsBuildInfo;
import com.jetbrains.teamcity.jenkinsbridge.settings.JenkinsBridgeSettings;
import com.jetbrains.teamcity.jenkinsbridge.settings.JenkinsBridgeSettingsProvider;
import jetbrains.buildServer.serverSide.CustomDataStorage;
import jetbrains.buildServer.serverSide.ProjectManager;
import jetbrains.buildServer.serverSide.ServerPaths;

import com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class BuildMirrorStore {
  private static final Logger LOG = Logger.getInstance(BuildMirrorStore.class.getName());
  public static final String CUSTOM_DATA_STORAGE_NAME = "jenkinsBridgeStateStorage";

  private final JenkinsBridgeSettingsProvider settingsProvider;
  private final ServerPaths serverPaths;
  private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
  private final CustomDataStorage myStorage;
  @Deprecated
  private Path loadedStateFile;

  @NotNull
  private BridgeState state;

  public BuildMirrorStore(ServerPaths serverPaths, JenkinsBridgeSettingsProvider settingsProvider, ProjectManager projectManager) {
    this.serverPaths = serverPaths;
    this.settingsProvider = settingsProvider;
    myStorage = projectManager.getRootProject().getCustomDataStorage(CUSTOM_DATA_STORAGE_NAME);
    state = new BridgeState(myStorage);
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

  /**
   * Returns the mirror for the given key, or {@code null} if none exists. Does not create one.
   */
  public synchronized BuildMirror findMirror(String key) throws IOException {
    ensureStateIsLoaded();
    return state.getBuilds().get(key);
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

  /**
   * Removes the mirrors that have reached {@code TEAMCITY_FINISHED} from the store and returns them.
   *
   * @return The list of pruned mirrors.
   * @throws IOException If reading or writing the state file fails.
   */
  public synchronized List<BuildMirror> pruneFinishedMirrors() throws IOException {
    ensureStateIsLoaded();
    List<BuildMirror> pruned = new ArrayList<>();
    List<String> keysToRemove = new ArrayList<>();
    for (Map.Entry<String, BuildMirror> entry : state.getBuilds().entrySet()) {
      if (entry.getValue().getSyncState() == SyncState.TEAMCITY_FINISHED) {
        pruned.add(entry.getValue());
        keysToRemove.add(entry.getKey());
      }
    }
    state.removeBuilds(keysToRemove);
    return pruned;
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

  public synchronized void markBuildError(BuildMirror mirror, Exception error) {
    try {
      mirror.setSyncState(SyncState.FAILED_TO_SYNC);
      mirror.setLastError(error.getMessage());
      saveMirror(mirror);
    } catch (IOException saveError) {
      LOG.warn("Failed to persist Jenkins Bridge build error", saveError);
    }
  }

  public synchronized void markPollSuccess() {
    try {
      ensureStateIsLoaded();
      state.setLastPollTime(now());
      state.setLastError(null);
    } catch (IOException e) {
      LOG.warn("Failed to persist Jenkins Bridge poll status", e);
    }
  }

  public synchronized void markPollError(Exception error) {
    try {
      ensureStateIsLoaded();
      state.setLastPollTime(now());
      state.setLastError(error.getMessage());
    } catch (IOException e) {
      LOG.warn("Failed to persist Jenkins Bridge poll error", e);
    }
  }

  /**
   * Uses files in the plugin folder instead of {@link CustomDataStorage}.
   */
  @Deprecated
  public Path getStateFile() {
    JenkinsBridgeSettings settings = settings();
    if (settings.hasCustomStateFile()) {
      return settings.getCustomStateFile();
    }

    return serverPaths.getPluginDataDirectory().toPath()
        .resolve("jenkins-bridge")
        .resolve("jenkins-teamcity-mapping.json");
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

  /**
   * Uses files in the plugin folder instead of {@link CustomDataStorage}.
   */
  @Deprecated
  private void ensureLoaded() throws IOException {
    Path stateFile = getStateFile();
    if (state != null && stateFile.equals(loadedStateFile)) {
      return;
    }

    if (!Files.exists(stateFile)) {
      state = new BridgeState(myStorage);
      state.setVersion(1);
      loadedStateFile = stateFile;
      return;
    }

    JsonParseException parseError = null;
    try (Reader reader = Files.newBufferedReader(stateFile, StandardCharsets.UTF_8)) {
      state = gson.fromJson(reader, BridgeState.class);
    } catch (JsonParseException e) {
      parseError = e;
    }

    if (parseError != null) {
      // A corrupt/truncated state file must not brick the bridge (R7). Move it aside and start
      // fresh; mirrors re-bind to existing TeamCity builds via restore-by-key on the next sync.
      LOG.warn("Jenkins Bridge state file " + stateFile + " is corrupt; quarantining it and starting with empty state",
          parseError);
      quarantineCorruptStateFile(stateFile);
      state = new BridgeState(myStorage);
      state.setVersion(1);
      loadedStateFile = stateFile;
      return;
    }

    if (state == null) {
      state = new BridgeState(myStorage);
    }
    state.setVersion(1);
    state.getBuilds();
    loadedStateFile = stateFile;
  }

  private void ensureStateIsLoaded() throws IOException {
    Exception parseError = null;
    try { // Deserialize all fields from custom data storage and check if there are any errors
      state.getVersion();
      state.getBuilds();
      state.getLastSeenBuildNumbers();
      state.getLastPollTime();
      state.getLastError();
    } catch (Exception e) {
      parseError = e;
    }

    if (parseError != null) {
      // A corrupt/truncated storage entry must not brick the bridge (R7). It's better to start fresh.
      // Mirrors re-bind to existing TeamCity builds via restore-by-key on the next sync.
      // The quarantine logic was removed since the log works for observability, and polluting the TeamCity DB is a bigger problem.
      LOG.warn("Jenkins Bridge state is corrupt and will be reset. Removed state: " + myStorage.getValues(),
          parseError);
      myStorage.clear();
    }
  }

  /**
   * Uses files in the plugin folder instead of {@link CustomDataStorage}.
   */
  @Deprecated
  private void quarantineCorruptStateFile(Path stateFile) {
    Path target = stateFile.resolveSibling(
        stateFile.getFileName().toString() + ".corrupt-" + System.currentTimeMillis());
    try {
      Files.move(stateFile, target);
      LOG.warn("Moved corrupt Jenkins Bridge state file to " + target);
    } catch (IOException moveError) {
      LOG.warn("Failed to move corrupt Jenkins Bridge state file " + stateFile + " aside", moveError);
    }
  }

  /**
   * Uses files in the plugin folder instead of {@link CustomDataStorage}.
   */
  @Deprecated
  private void saveState() throws IOException {
    Path stateFile = loadedStateFile == null ? getStateFile() : loadedStateFile;
    Path parent = stateFile.getParent();
    if (parent != null) {
      Files.createDirectories(parent);
    }

    Path temporaryFile = stateFile.resolveSibling(stateFile.getFileName().toString() + ".tmp");
    try (Writer writer = Files.newBufferedWriter(temporaryFile, StandardCharsets.UTF_8)) {
      gson.toJson(state, writer);
    }

    try {
      Files.move(temporaryFile, stateFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    } catch (IOException e) {
      Files.move(temporaryFile, stateFile, StandardCopyOption.REPLACE_EXISTING);
    }
  }

  private String now() {
    return ZonedDateTime.now(settings().getZoneId()).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
  }

  private JenkinsBridgeSettings settings() {
    return settingsProvider.load();
  }

  private String nullToEmpty(String value) {
    return value == null ? "" : value;
  }
}
