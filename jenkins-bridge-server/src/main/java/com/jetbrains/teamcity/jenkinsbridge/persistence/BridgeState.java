package com.jetbrains.teamcity.jenkinsbridge.persistence;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.google.gson.reflect.TypeToken;
import jetbrains.buildServer.serverSide.CustomDataStorage;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

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
  private static final String RESULT_METADATA_KEY_PREFIX = "result-metadata-";
  private static final String PENDING_TRIGGER_KEY_PREFIX = "pending-trigger-";
  private static final String LAST_SEEN_BUILD_NUMBER_KEY_PREFIX = "last-seen-build-number-";
  private static final String LAST_PRUNED_KEY_PREFIX = "last-pruned-";
  private static final String LAST_POLL_TIME_KEY = "last-poll-time";
  private static final String LAST_ERROR_KEY = "last-error";
  private static final String JENKINS_PARAMETER_KEY_PREFIX = "jenkins-parameter-names-";
  private static final String JENKINS_PARAMETER_SNAPSHOT_KEY_PREFIX = "jenkins-parameter-snapshot-";

  @NotNull
  private final CustomDataStorage myStorage;

  public BridgeState(@NotNull CustomDataStorage underlyingStorage) {
    myStorage = underlyingStorage;
  }

  public int getVersion() throws BridgeStateCorruptionException {
    String versionString = myStorage.getValue(VERSION_KEY);
    if (versionString == null) {
      throw new BridgeStateCorruptionException("Persisted Jenkins Bridge state has no schema version");
    }
    try {
      return Integer.parseInt(versionString);
    } catch (NumberFormatException e) {
      throw new BridgeStateCorruptionException("Persisted Jenkins Bridge state has an invalid schema version", e);
    }
  }

  public void setVersion(int version) {
    myStorage.putValue(VERSION_KEY, Integer.toString(version));
  }

  @NotNull
  public Map<String, BuildMirror> getBuilds() throws BridgeStateCorruptionException {
    Map<String, String> stateMap = myStorage.getValues();
    Map<String, BuildMirror> builds = new LinkedHashMap<String, BuildMirror>();
    if (stateMap != null) {
      for (Map.Entry<String, String> entry : stateMap.entrySet()) {
        if (!entry.getKey().startsWith(BUILD_KEY_PREFIX) || entry.getValue() == null) {
          continue;
        }
        BuildMirror mirror = parseEntry(entry.getKey(), entry.getValue(), BuildMirror.class);
        if (mirror == null) {
          throw new BridgeStateCorruptionException("Persisted Jenkins Bridge state entry "
              + entry.getKey() + " is null");
        }
        builds.put(entry.getKey().substring(BUILD_KEY_PREFIX.length()), mirror);
      }
    }
    return builds;
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
  public Map<String, BuildResultMetadata> getResultMetadata() throws BridgeStateCorruptionException {
    Map<String, BuildResultMetadata> result = new LinkedHashMap<String, BuildResultMetadata>();
    Map<String, String> values = myStorage.getValues();
    if (values != null) {
      for (Map.Entry<String, String> entry : values.entrySet()) {
        if (!entry.getKey().startsWith(RESULT_METADATA_KEY_PREFIX) || entry.getValue() == null) continue;
        BuildResultMetadata metadata = parseEntry(entry.getKey(), entry.getValue(), BuildResultMetadata.class);
        if (metadata == null) throw new BridgeStateCorruptionException("Persisted result metadata entry " + entry.getKey() + " is null");
        result.put(entry.getKey().substring(RESULT_METADATA_KEY_PREFIX.length()), metadata);
      }
    }
    return result;
  }

  public void putResultMetadata(@NotNull String buildId, @NotNull BuildResultMetadata metadata) {
    myStorage.putValue(RESULT_METADATA_KEY_PREFIX + buildId, OUR_GSON.toJson(metadata));
  }

  public void removeResultMetadata(@NotNull Collection<String> buildIds) {
    if (buildIds.isEmpty()) return;
    Set<String> keys = buildIds.stream().map(id -> RESULT_METADATA_KEY_PREFIX + id).collect(Collectors.toSet());
    myStorage.updateValues(Collections.emptyMap(), keys);
  }

  @NotNull
  public Map<String, PendingTrigger> getPendingTriggers() throws BridgeStateCorruptionException {
    Map<String, String> stateMap = myStorage.getValues();
    Map<String, PendingTrigger> pendingTriggers = new LinkedHashMap<String, PendingTrigger>();
    if (stateMap != null) {
      for (Map.Entry<String, String> entry : stateMap.entrySet()) {
        if (!entry.getKey().startsWith(PENDING_TRIGGER_KEY_PREFIX) || entry.getValue() == null) {
          continue;
        }
        PendingTrigger pendingTrigger = parseEntry(entry.getKey(), entry.getValue(), PendingTrigger.class);
        if (pendingTrigger == null) {
          throw new BridgeStateCorruptionException("Persisted Jenkins Bridge state entry "
              + entry.getKey() + " is null");
        }
        pendingTriggers.put(entry.getKey().substring(PENDING_TRIGGER_KEY_PREFIX.length()), pendingTrigger);
      }
    }
    return pendingTriggers;
  }

  public void putPendingTrigger(@NotNull String key, @NotNull PendingTrigger pendingTrigger) {
    myStorage.putValue(PENDING_TRIGGER_KEY_PREFIX + key, OUR_GSON.toJson(pendingTrigger));
  }

  public void removePendingTrigger(@NotNull String key) {
    myStorage.updateValues(
        Collections.emptyMap(),
        Collections.singleton(PENDING_TRIGGER_KEY_PREFIX + key));
  }

  @NotNull
  public Map<String, Integer> getLastSeenBuildNumbers() throws BridgeStateCorruptionException {
    Map<String, String> stateMap = myStorage.getValues();
    Map<String, Integer> buildNumbers = new LinkedHashMap<String, Integer>();
    if (stateMap != null) {
      for (Map.Entry<String, String> entry : stateMap.entrySet()) {
        if (!entry.getKey().startsWith(LAST_SEEN_BUILD_NUMBER_KEY_PREFIX) || entry.getValue() == null) {
          continue;
        }
        try {
          buildNumbers.put(entry.getKey().substring(LAST_SEEN_BUILD_NUMBER_KEY_PREFIX.length()),
              Integer.parseInt(entry.getValue()));
        } catch (NumberFormatException e) {
          throw new BridgeStateCorruptionException("Persisted Jenkins Bridge state entry "
              + entry.getKey() + " has an invalid build number", e);
        }
      }
    }
    return buildNumbers;
  }

  public void putLastSeenBuildNumber(@NotNull String jobName, int buildNumber) {
    myStorage.putValue(LAST_SEEN_BUILD_NUMBER_KEY_PREFIX + jobName, Integer.toString(buildNumber));
  }

  @Nullable
  public String getLastPruned(@NotNull String mappingKey) {
    return myStorage.getValue(LAST_PRUNED_KEY_PREFIX + mappingKey);
  }

  public void setLastPruned(@NotNull String mappingKey, @NotNull String timestamp) {
    myStorage.putValue(LAST_PRUNED_KEY_PREFIX + mappingKey, timestamp);
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

  @NotNull
  public Set<String> getImportedJenkinsParameterNames(@NotNull String buildTypeExternalId)
      throws BridgeStateCorruptionException {
    String serialized = myStorage.getValue(jenkinsParameterKey(buildTypeExternalId));
    if (serialized == null) {
      return Collections.emptySet();
    }
    try {
      java.lang.reflect.Type type = new TypeToken<Set<String>>() { }.getType();
      Set<String> names = OUR_GSON.fromJson(serialized, type);
      return names == null ? Collections.<String>emptySet() : names;
    } catch (JsonParseException e) {
      throw new BridgeStateCorruptionException("Persisted Jenkins parameter ownership for build type "
          + buildTypeExternalId + " is invalid", e);
    }
  }

  public void putImportedJenkinsParameterNames(@NotNull String buildTypeExternalId,
                                                @NotNull Set<String> names) {
    myStorage.putValue(jenkinsParameterKey(buildTypeExternalId), OUR_GSON.toJson(names));
  }

  public String getImportedJenkinsParameterSnapshot(@NotNull String buildTypeExternalId) {
    return myStorage.getValue(jenkinsParameterSnapshotKey(buildTypeExternalId));
  }

  public void putImportedJenkinsParameterSnapshot(@NotNull String buildTypeExternalId,
                                                  @NotNull String snapshot) {
    myStorage.putValue(jenkinsParameterSnapshotKey(buildTypeExternalId), snapshot);
  }

  private static String jenkinsParameterKey(String buildTypeExternalId) {
    return JENKINS_PARAMETER_KEY_PREFIX + buildTypeExternalId;
  }

  private static String jenkinsParameterSnapshotKey(String buildTypeExternalId) {
    return JENKINS_PARAMETER_SNAPSHOT_KEY_PREFIX + buildTypeExternalId;
  }

  private <T> T parseEntry(String key, String value, Class<T> type)
      throws BridgeStateCorruptionException {
    try {
      return OUR_GSON.fromJson(value, type);
    } catch (JsonParseException e) {
      throw new BridgeStateCorruptionException("Persisted Jenkins Bridge state entry "
          + key + " is invalid", e);
    }
  }
}
