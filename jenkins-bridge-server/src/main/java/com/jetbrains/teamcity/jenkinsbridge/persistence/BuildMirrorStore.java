package com.jetbrains.teamcity.jenkinsbridge.persistence;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.jetbrains.teamcity.jenkinsbridge.model.JenkinsBuildInfo;
import com.jetbrains.teamcity.jenkinsbridge.settings.JenkinsBridgeSettings;
import com.jetbrains.teamcity.jenkinsbridge.settings.JenkinsBridgeSettingsProvider;
import jetbrains.buildServer.serverSide.ServerPaths;

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
import com.intellij.openapi.diagnostic.Logger;

public class BuildMirrorStore {
  private static final Logger LOG = Logger.getInstance(BuildMirrorStore.class.getName());

  private final JenkinsBridgeSettingsProvider settingsProvider;
  private final ServerPaths serverPaths;
  private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
  private Path loadedStateFile;
  private BridgeState state;

  public BuildMirrorStore(ServerPaths serverPaths, JenkinsBridgeSettingsProvider settingsProvider) {
    this.serverPaths = serverPaths;
    this.settingsProvider = settingsProvider;
  }

  public synchronized BuildMirror getOrCreateMirror(String mirrorKey, String jobName,
                                                    String teamCityBuildTypeExternalId,
                                                    JenkinsBuildInfo jenkinsInfo) throws IOException {
    ensureLoaded();

    BuildMirror mirror = state.getBuilds().get(mirrorKey);
    if (mirror == null) {
      mirror = BuildMirror.create(mirrorKey, jobName, jenkinsInfo, teamCityBuildTypeExternalId, now());
      state.getBuilds().put(mirrorKey, mirror);
      saveState();
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
    ensureLoaded();
    mirror.setUpdatedAt(now());
    state.getBuilds().put(mirror.getJenkinsBuildKey(), mirror);
    saveState();
  }

  /** Returns the mirror for the given key, or {@code null} if none exists. Does not create one. */
  public synchronized BuildMirror findMirror(String key) throws IOException {
    ensureLoaded();
    return state.getBuilds().get(key);
  }

  /** Returns mirrors for the job that have not yet reached {@code TEAMCITY_FINISHED}. */
  public synchronized List<BuildMirror> getActiveMirrors(String jobName) throws IOException {
    ensureLoaded();
    List<BuildMirror> active = new ArrayList<BuildMirror>();
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
   * {@code TEAMCITY_FINISHED}. Used by feature-derived mappings so two configs mirroring the same
   * Jenkins job are tracked independently.
   */
  public synchronized List<BuildMirror> getActiveMirrors(String teamCityBuildTypeExternalId, String jobName)
      throws IOException {
    ensureLoaded();
    List<BuildMirror> active = new ArrayList<BuildMirror>();
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
    ensureLoaded();
    Integer value = state.getLastSeenBuildNumbers().get(jobName);
    return value == null ? 0 : value;
  }

  public synchronized void setLastSeenBuildNumber(String jobName, int buildNumber) throws IOException {
    ensureLoaded();
    Integer current = state.getLastSeenBuildNumbers().get(jobName);
    if (current != null && current >= buildNumber) {
      return;
    }
    state.getLastSeenBuildNumbers().put(jobName, buildNumber);
    saveState();
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
      ensureLoaded();
      state.setLastPollTime(now());
      state.setLastError(null);
      saveState();
    } catch (IOException e) {
      LOG.warn("Failed to persist Jenkins Bridge poll status", e);
    }
  }

  public synchronized void markPollError(Exception error) {
    try {
      ensureLoaded();
      state.setLastPollTime(now());
      state.setLastError(error.getMessage());
      saveState();
    } catch (IOException e) {
      LOG.warn("Failed to persist Jenkins Bridge poll error", e);
    }
  }

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

  private void ensureLoaded() throws IOException {
    Path stateFile = getStateFile();
    if (state != null && stateFile.equals(loadedStateFile)) {
      return;
    }

    if (!Files.exists(stateFile)) {
      state = new BridgeState();
      state.setVersion(1);
      loadedStateFile = stateFile;
      return;
    }

    JsonParseException parseError = null;
    Reader reader = Files.newBufferedReader(stateFile, StandardCharsets.UTF_8);
    try {
      state = gson.fromJson(reader, BridgeState.class);
    } catch (JsonParseException e) {
      parseError = e;
    } finally {
      reader.close();
    }

    if (parseError != null) {
      // A corrupt/truncated state file must not brick the bridge (R7). Move it aside and start
      // fresh; mirrors re-bind to existing TeamCity builds via restore-by-key on the next sync.
      LOG.warn("Jenkins Bridge state file " + stateFile + " is corrupt; quarantining it and starting with empty state",
          parseError);
      quarantineCorruptStateFile(stateFile);
      state = new BridgeState();
      state.setVersion(1);
      loadedStateFile = stateFile;
      return;
    }

    if (state == null) {
      state = new BridgeState();
    }
    state.setVersion(1);
    state.getBuilds();
    loadedStateFile = stateFile;
  }

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

  private void saveState() throws IOException {
    Path stateFile = loadedStateFile == null ? getStateFile() : loadedStateFile;
    Path parent = stateFile.getParent();
    if (parent != null) {
      Files.createDirectories(parent);
    }

    Path temporaryFile = stateFile.resolveSibling(stateFile.getFileName().toString() + ".tmp");
    Writer writer = Files.newBufferedWriter(temporaryFile, StandardCharsets.UTF_8);
    try {
      gson.toJson(state, writer);
    } finally {
      writer.close();
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
