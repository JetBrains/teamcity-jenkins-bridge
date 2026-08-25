package com.jetbrains.teamcity.jenkinsbridge.integration;

import com.google.gson.JsonObject;
import com.jetbrains.teamcity.jenkinsbridge.model.JenkinsBuildInfo;
import com.jetbrains.teamcity.jenkinsbridge.persistence.BuildMirror;
import com.jetbrains.teamcity.jenkinsbridge.persistence.BuildMirrorStore;
import com.jetbrains.teamcity.jenkinsbridge.persistence.BuildResultMetadataCleanup;
import jetbrains.buildServer.serverSide.BuildServerListener;
import jetbrains.buildServer.serverSide.cleanup.BuildCleanupContext;
import jetbrains.buildServer.serverSide.impl.BaseServerTestCase;
import jetbrains.buildServer.util.EventDispatcher;
import org.testng.annotations.Test;

import java.util.Collections;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;

/** Verifies result metadata cleanup against TeamCity's real database-backed custom storage. */
public class TeamCityBuildResultMetadataIT extends BaseServerTestCase {
  @Test
  public void cleanupRemovesMetadataForSelectedBuildIds() throws Exception {
    BuildMirrorStore store = new BuildMirrorStore(null, null, myProjectManager);
    JsonObject json = new JsonObject();
    json.addProperty("number", 7);
    json.addProperty("timestamp", 1710000000007L);
    json.addProperty("url", "http://jenkins/job/demo/7/");
    BuildMirror mirror = BuildMirror.create("job#7", "job", JenkinsBuildInfo.fromJson(json),
        myBuildType.getExternalId(), "2026-08-24T12:00:00Z");
    mirror.setTeamCityBuildId(700L);
    store.saveResultMetadata(mirror);
    assertNotNull(store.findResultMetadataByTeamCityBuildId(700L));

    BuildCleanupContext context = org.mockito.Mockito.mock(BuildCleanupContext.class);
    when(context.getBuildIds()).thenReturn(Collections.singletonList(700L));
    EventDispatcher<BuildServerListener> dispatcher = org.mockito.Mockito.mock(EventDispatcher.class);
    BuildMirror orphan = BuildMirror.create("job#8", "job", JenkinsBuildInfo.fromJson(json),
        myBuildType.getExternalId(), "2026-08-24T12:00:00Z");
    orphan.setTeamCityBuildId(701L);
    store.saveResultMetadata(orphan);
    BuildResultMetadataCleanup cleanup = new BuildResultMetadataCleanup(
        store, dispatcher, myFixture.getBuildsManager());
    cleanup.cleanupBuildsData(context);

    assertNull(store.findResultMetadataByTeamCityBuildId(700L));
    assertNotNull(store.findResultMetadataByTeamCityBuildId(701L));
    cleanup.reconcileOrphanedMetadata();
    assertNull(store.findResultMetadataByTeamCityBuildId(701L));
    cleanup.dispose();
  }
}
