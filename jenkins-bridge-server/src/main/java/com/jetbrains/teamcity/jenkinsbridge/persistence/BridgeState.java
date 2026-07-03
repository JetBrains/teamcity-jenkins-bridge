package com.jetbrains.teamcity.jenkinsbridge.persistence;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import jetbrains.buildServer.serverSide.CustomDataStorage;
import org.jetbrains.annotations.NotNull;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public class BridgeState {
  private static final Gson OUR_GSON = new GsonBuilder().setPrettyPrinting().create();
  private static final String VERSION_KEY = "version";
  private static final String BUILD_KEY_PREFIX = "build-";
  private static final String LAST_SEEN_BUILD_NUMBER_KEY_PREFIX = "last-seen-build-number-";
  private static final String LAST_POLL_TIME_KEY = "last-poll-time";
  private static final String LAST_ERROR_KEY = "last-error";

  @NotNull
  private final CustomDataStorage myStorage;

  public BridgeState(@NotNull CustomDataStorage underlyingStorage) {
    myStorage = underlyingStorage;
    setVersion(2);
  }

  public int getVersion() {
    String versionString = myStorage.getValue(VERSION_KEY);
    if (versionString == null) {
      throw new IllegalStateException("Cannot read the state schema version before it is set");
    }
    return Integer.parseInt(versionString);
  }

  public void setVersion(int version) {
    myStorage.putValue(VERSION_KEY, Integer.toString(version));
  }

  @NotNull
  public Map<String, BuildMirror> getBuilds() {
    Map<String, String> stateMap = myStorage.getValues();
    if (stateMap == null) {
      return new LinkedHashMap<>();
    }
    return stateMap
        .entrySet()
        .stream()
        .filter(entry -> entry.getKey().startsWith(BUILD_KEY_PREFIX))
        .filter(entry -> entry.getValue() != null)
        .collect(Collectors.toMap(
            entry -> entry.getKey().substring(BUILD_KEY_PREFIX.length()),
            entry -> OUR_GSON.fromJson(entry.getValue(), BuildMirror.class),
            (a, b) -> b,
            LinkedHashMap::new));
  }

  public void putBuild(@NotNull String key, @NotNull BuildMirror mirror) {
    myStorage.putValue(BUILD_KEY_PREFIX + key, OUR_GSON.toJson(mirror));
  }

  public void removeBuilds(@NotNull Collection<String> keys) {
    if (keys.isEmpty()) {
      return;
    }
    Set<String> storageKeys = keys.stream()
        .map(key -> BUILD_KEY_PREFIX + key)
        .collect(Collectors.toSet());
    myStorage.updateValues(Collections.emptyMap(), storageKeys);
  }

  @NotNull
  public Map<String, Integer> getLastSeenBuildNumbers() {
    Map<String, String> stateMap = myStorage.getValues();
    if (stateMap == null) {
      return new LinkedHashMap<>();
    }
    return stateMap
        .entrySet()
        .stream()
        .filter(entry -> entry.getKey().startsWith(LAST_SEEN_BUILD_NUMBER_KEY_PREFIX))
        .filter(entry -> entry.getValue() != null)
        .collect(Collectors.toMap(
            entry -> entry.getKey().substring(LAST_SEEN_BUILD_NUMBER_KEY_PREFIX.length()),
            entry -> Integer.parseInt(entry.getValue()),
            (a, b) -> b,
            LinkedHashMap::new));
  }

  public void putLastSeenBuildNumber(@NotNull String jobName, int buildNumber) {
    myStorage.putValue(LAST_SEEN_BUILD_NUMBER_KEY_PREFIX + jobName, Integer.toString(buildNumber));
  }

  public String getLastPollTime() {
    return myStorage.getValue(LAST_POLL_TIME_KEY);
  }

  public void setLastPollTime(String lastPollTime) {
    myStorage.putValue(LAST_POLL_TIME_KEY, lastPollTime);
  }

  public String getLastError() {
    return myStorage.getValue(LAST_ERROR_KEY);
  }

  public void setLastError(String lastError) {
    myStorage.putValue(LAST_ERROR_KEY, lastError);
  }
}
