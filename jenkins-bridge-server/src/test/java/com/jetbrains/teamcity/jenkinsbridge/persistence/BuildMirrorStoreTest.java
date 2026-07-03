package com.jetbrains.teamcity.jenkinsbridge.persistence;

import com.google.gson.JsonObject;
import com.jetbrains.teamcity.jenkinsbridge.model.JenkinsBuildInfo;
import com.jetbrains.teamcity.jenkinsbridge.model.JenkinsPipelineGraph;
import com.jetbrains.teamcity.jenkinsbridge.model.JenkinsPipelineGraphNode;
import com.jetbrains.teamcity.jenkinsbridge.model.GraphConfidence;
import com.jetbrains.teamcity.jenkinsbridge.settings.JenkinsBridgeSettings;
import com.jetbrains.teamcity.jenkinsbridge.settings.JenkinsBridgeSettingsProvider;
import jetbrains.buildServer.serverSide.CustomDataStorage;
import jetbrains.buildServer.serverSide.ProjectManager;
import jetbrains.buildServer.serverSide.SProject;
import org.junit.Test;

import java.io.File;
import java.lang.reflect.Constructor;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.*;

public class BuildMirrorStoreTest {
  @Test
  public void timestampedBuildKeyDistinguishesReusedJenkinsBuildNumbers() {
    assertEquals("job#7@1710000000007", BuildMirrorStore.buildKey("job", buildInfo(7, 1710000000007L)));
    assertEquals("job#7@1710000000999", BuildMirrorStore.buildKey("job", buildInfo(7, 1710000000999L)));
    assertEquals("job#7", BuildMirrorStore.legacyBuildKey("job#7@1710000000007"));
  }

  @Test
  public void buildKeyFallsBackToLegacyWhenJenkinsTimestampIsMissing() {
    assertEquals("job#7", BuildMirrorStore.buildKey("job", buildInfo(7, 0L)));
  }

  @Test
  public void lastSeenBuildNumberPersistsAndIsMonotonic() throws Exception {
    JenkinsBridgeSettingsProvider provider = providerWithTempStateFile();
    ProjectManager projectManager = buildMockProjectManager();
    BuildMirrorStore store = new BuildMirrorStore(null, provider, projectManager);

    assertEquals(0, store.getLastSeenBuildNumber("job"));
    store.setLastSeenBuildNumber("job", 42);
    assertEquals(42, store.getLastSeenBuildNumber("job"));

    // A fresh store instance must read the watermark back from the shared custom data storage.
    BuildMirrorStore reloaded = new BuildMirrorStore(null, provider, projectManager);
    assertEquals(42, reloaded.getLastSeenBuildNumber("job"));

    // Lower values are ignored (watermark only moves forward).
    reloaded.setLastSeenBuildNumber("job", 10);
    assertEquals(42, reloaded.getLastSeenBuildNumber("job"));
  }

  @Test
  public void getActiveMirrorsExcludesFinishedBuilds() throws Exception {
    BuildMirrorStore store = new BuildMirrorStore(null, providerWithTempStateFile(), buildMockProjectManager());

    store.getOrCreateMirror(BuildMirrorStore.buildKey("job", 1), "job", "buildType", buildInfo(1));
    BuildMirror finished = store.getOrCreateMirror(BuildMirrorStore.buildKey("job", 2), "job", "buildType", buildInfo(2));
    finished.setSyncState(SyncState.TEAMCITY_FINISHED);
    store.saveMirror(finished);

    List<BuildMirror> active = store.getActiveMirrors("job");
    assertEquals(1, active.size());
    assertEquals(1, active.get(0).getJenkinsBuildNumber());
  }

  @Test
  public void findMirrorReturnsNullWhenAbsent() throws Exception {
    BuildMirrorStore store = new BuildMirrorStore(null, providerWithTempStateFile(), buildMockProjectManager());
    store.getOrCreateMirror(BuildMirrorStore.buildKey("job", 1), "job", "buildType", buildInfo(1));

    assertNotNull(store.findMirror(BuildMirrorStore.buildKey("job", 1)));
    assertNull(store.findMirror(BuildMirrorStore.buildKey("job", 999)));
  }

  @Test
  public void corruptStateFileIsQuarantinedAndBridgeStartsFresh() throws Exception {
    Map<String, String> corruptValues = new LinkedHashMap<String, String>();
    corruptValues.put("STATE", "@@@ definitely not json @@@");

    CustomDataStorage storage = mock(CustomDataStorage.class);
    when(storage.getValues()).thenReturn(corruptValues);

    CustomDataStorage quarantineStorage = mock(CustomDataStorage.class);

    SProject rootProject = mock(SProject.class);
    when(rootProject.getCustomDataStorage(BuildMirrorStore.CUSTOM_DATA_STORAGE_NAME)).thenReturn(storage);
    when(rootProject.getCustomDataStorage(startsWith(BuildMirrorStore.CUSTOM_DATA_STORAGE_NAME + "-corrupt-")))
        .thenReturn(quarantineStorage);

    ProjectManager projectManager = mock(ProjectManager.class);
    when(projectManager.getRootProject()).thenReturn(rootProject);

    BuildMirrorStore store = new BuildMirrorStore(null, providerWithTempStateFile(), projectManager);

    // Must not throw, and must start from empty state.
    assertEquals(0, store.getLastSeenBuildNumber("job"));
    assertNull(store.findMirror(BuildMirrorStore.buildKey("job", 1)));

    // The corrupt values are moved aside to a "-corrupt-*" storage rather than left to brick every poll.
    verify(quarantineStorage, atLeastOnce()).putValues(corruptValues);
  }

  @Test
  public void pipelineGraphSnapshotPersistsAcrossReload() throws Exception {
    JenkinsBridgeSettingsProvider provider = providerWithTempStateFile();
    ProjectManager projectManager = buildMockProjectManager();
    BuildMirrorStore store = new BuildMirrorStore(null, provider, projectManager);
    BuildMirror mirror = store.getOrCreateMirror(BuildMirrorStore.buildKey("job", 7), "job", "buildType", buildInfo(7));

    mirror.setPipelineGraph(graph("hash-a", "SUCCESS", Collections.<String>emptyList()));
    store.saveMirror(mirror);

    BuildMirrorStore reloaded = new BuildMirrorStore(null, provider, projectManager);
    BuildMirror restored = reloaded.findMirror(BuildMirrorStore.buildKey("job", 7));

    assertNotNull(restored);
    assertNotNull(restored.getPipelineGraph());
    assertEquals("hash-a", restored.getPipelineGraph().getTopologyHash());
    assertEquals(GraphConfidence.EXPLICIT, restored.getPipelineGraph().getConfidence());
    assertEquals("job#7:1", restored.getPipelineGraph().getNodes().get(0).getFlowId());
  }

  @Test
  public void jenkinsBuildParametersPersistAcrossReload() throws Exception {
    JenkinsBridgeSettingsProvider provider = providerWithTempStateFile();
    ProjectManager projectManager = buildMockProjectManager();
    BuildMirrorStore store = new BuildMirrorStore(null, provider, projectManager);
    BuildMirror mirror = store.getOrCreateMirror(BuildMirrorStore.buildKey("job", 9), "job", "buildType", buildInfo(9));

    Map<String, String> parameters = new LinkedHashMap<String, String>();
    parameters.put("BRANCH", "feature/x");
    parameters.put("RUN_TESTS", "true");
    mirror.setJenkinsBuildParameters(parameters);
    store.saveMirror(mirror);

    BuildMirrorStore reloaded = new BuildMirrorStore(null, provider, projectManager);
    BuildMirror restored = reloaded.findMirror(BuildMirrorStore.buildKey("job", 9));

    assertNotNull(restored);
    assertTrue(restored.isJenkinsBuildParametersLoaded());
    assertEquals("feature/x", restored.getJenkinsBuildParameters().get("BRANCH"));
    assertEquals("true", restored.getJenkinsBuildParameters().get("RUN_TESTS"));
  }

  @Test
  public void pipelineChainSnapshotPersistsAcrossReload() throws Exception {
    JenkinsBridgeSettingsProvider provider = providerWithTempStateFile();
    ProjectManager projectManager = buildMockProjectManager();
    BuildMirrorStore store = new BuildMirrorStore(null, provider, projectManager);
    BuildMirror mirror = store.getOrCreateMirror(BuildMirrorStore.buildKey("job", 8), "job", "buildType", buildInfo(8));

    Map<String, PipelineChainNodeMirror> nodes = new LinkedHashMap<String, PipelineChainNodeMirror>();
    nodes.put("1", new PipelineChainNodeMirror("1", "job#8:1", "BuildType_JenkinsFlow_hash_1", 101L));
    mirror.setPipelineChain(new PipelineChainMirror(
        "hash-a",
        "EXPLICIT",
        "BuildType_JenkinsFlow_hash_Top",
        200L,
        nodes,
        Arrays.asList("1"),
        Arrays.asList(101L),
        true));
    store.saveMirror(mirror);

    BuildMirrorStore reloaded = new BuildMirrorStore(null, provider, projectManager);
    BuildMirror restored = reloaded.findMirror(BuildMirrorStore.buildKey("job", 8));

    assertNotNull(restored);
    assertNotNull(restored.getPipelineChain());
    assertEquals("hash-a", restored.getPipelineChain().getTopologyHash());
    assertEquals(Long.valueOf(200L), restored.getPipelineChain().getTopPromotionId());
    assertTrue(restored.getPipelineChain().matchesQueuedTopology("hash-a"));
    assertEquals(Long.valueOf(101L), restored.getPipelineChain().getNode("1").getPromotionId());
  }

  @Test
  public void graphTopologyHashCanStayStableWhileSnapshotStatusChanges() throws Exception {
    JenkinsPipelineGraph success = graph("same-hash", "SUCCESS", Collections.<String>emptyList());
    JenkinsPipelineGraph failed = graph("same-hash", "FAILURE", Collections.<String>emptyList());

    assertEquals(success.getTopologyHash(), failed.getTopologyHash());
    assertEquals("SUCCESS", success.getNodes().get(0).getStatus());
    assertEquals("FAILURE", failed.getNodes().get(0).getStatus());
  }

  @Test
  public void graphTopologyHashChangesWhenEdgesChange() {
    JenkinsPipelineGraph noParent = graph("hash-a", "SUCCESS", Collections.<String>emptyList());
    JenkinsPipelineGraph withParent = graph("hash-b", "SUCCESS", Arrays.asList("0"));

    assertTrue(!noParent.getTopologyHash().equals(withParent.getTopologyHash()));
  }

  private static JenkinsBuildInfo buildInfo(int number) {
    return buildInfo(number, 0L);
  }

  private static JenkinsBuildInfo buildInfo(int number, long timestamp) {
    JsonObject json = new JsonObject();
    json.addProperty("number", number);
    json.addProperty("timestamp", timestamp);
    json.addProperty("building", true);
    return JenkinsBuildInfo.fromJson(json);
  }

  private static JenkinsPipelineGraph graph(String hash, String status, List<String> parents) {
    return new JenkinsPipelineGraph(
        true,
        JenkinsPipelineGraph.SOURCE_WFAPI,
        Arrays.asList(new JenkinsPipelineGraphNode(
            "1", "job#7:1", "Build", status, 1000L, 10L,
            parents, Collections.<String>emptyList(), Collections.<String>emptyList())),
        hash,
        GraphConfidence.EXPLICIT,
        Collections.<String>emptyList());
  }

  private static JenkinsBridgeSettingsProvider providerWithTempStateFile() throws Exception {
    File stateFile = File.createTempFile("jenkins-bridge-store-test", ".json");
    stateFile.delete();
    stateFile.deleteOnExit();
    return providerForStateFile(stateFile.getAbsolutePath());
  }

  private static JenkinsBridgeSettingsProvider providerForStateFile(final String path) {
    return new JenkinsBridgeSettingsProvider(null) {
      @Override
      public JenkinsBridgeSettings load() {
        try {
          Constructor<JenkinsBridgeSettings> constructor = JenkinsBridgeSettings.class.getDeclaredConstructor(
              boolean.class, String.class, String.class, String.class, String.class,
              String.class, String.class, String.class, String.class,
              int.class, int.class, String.class, String.class);
          constructor.setAccessible(true);
          return constructor.newInstance(
              true, "http://jenkins", "user", "token", "job",
              "http://teamcity", "tc-user", "tc-pass", "buildType",
              10, 1, "Europe/Berlin", path);
        } catch (Exception e) {
          throw new AssertionError(e);
        }
      }
    };
  }

  /**
   * Uses an implementation of {@link CustomDataStorage} with an in-memory map so multiple {@link BuildMirrorStore}
   * instances sharing this {@link ProjectManager} observe each other's writes, like reloading from disk.
   */
  public static ProjectManager buildMockProjectManager() {
    final Map<String, String> backingValues = new LinkedHashMap<>();

    CustomDataStorage storage = mock(CustomDataStorage.class);
    when(storage.getValues()).thenAnswer(invocation -> new LinkedHashMap<>(backingValues));
    doAnswer(invocation -> {
      backingValues.clear();
      return null;
    }).when(storage).clear();
    doAnswer(invocation -> {
      backingValues.put(invocation.getArgument(0), invocation.getArgument(1));
      return null;
    }).when(storage).putValue(anyString(), anyString());

    SProject rootProject = mock(SProject.class);
    when(rootProject.getCustomDataStorage(BuildMirrorStore.CUSTOM_DATA_STORAGE_NAME)).thenReturn(storage);
    when(rootProject.getCustomDataStorage(startsWith(BuildMirrorStore.CUSTOM_DATA_STORAGE_NAME + "-corrupt-")))
        .thenAnswer(invocation -> mock(CustomDataStorage.class));

    ProjectManager projectManager = mock(ProjectManager.class);
    when(projectManager.getRootProject()).thenReturn(rootProject);
    return projectManager;
  }
}
