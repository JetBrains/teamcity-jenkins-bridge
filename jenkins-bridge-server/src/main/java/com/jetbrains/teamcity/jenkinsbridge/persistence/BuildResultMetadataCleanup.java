package com.jetbrains.teamcity.jenkinsbridge.persistence;

import com.intellij.openapi.diagnostic.Logger;
import jetbrains.buildServer.serverSide.BuildServerAdapter;
import jetbrains.buildServer.serverSide.BuildServerListener;
import jetbrains.buildServer.serverSide.SFinishedBuild;
import jetbrains.buildServer.serverSide.cleanup.BuildCleanupContext;
import jetbrains.buildServer.serverSide.cleanup.CleanupExtensionAdapter;
import jetbrains.buildServer.util.EventDispatcher;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.jetbrains.annotations.NotNull;

/** Removes result metadata when TeamCity removes the corresponding finished build. */
public class BuildResultMetadataCleanup extends CleanupExtensionAdapter {
  private static final Logger LOG = Logger.getInstance(BuildResultMetadataCleanup.class.getName());
  private final BuildMirrorStore mirrorStore;
  private final EventDispatcher<BuildServerListener> eventDispatcher;
  private final BuildServerListener listener = new BuildServerAdapter() {
    @Override
    public void entryDeleted(SFinishedBuild build) {
      remove(build == null ? null : build.getBuildId());
    }

    @Override
    public void entriesDeleted(Collection<SFinishedBuild> builds) {
      List<Long> ids = new ArrayList<Long>();
      if (builds != null) {
        for (SFinishedBuild build : builds) {
          if (build != null) {
            ids.add(build.getBuildId());
          }
        }
      }
      remove(ids);
    }
  };

  public BuildResultMetadataCleanup(BuildMirrorStore mirrorStore,
                                    EventDispatcher<BuildServerListener> eventDispatcher) {
    this.mirrorStore = mirrorStore;
    this.eventDispatcher = eventDispatcher;
    eventDispatcher.addListener(listener);
  }

  @Override
  public void cleanupBuildsData(@NotNull BuildCleanupContext context) throws Exception {
    mirrorStore.removeResultMetadata(context.getBuildIds());
  }

  public void dispose() {
    eventDispatcher.removeListener(listener);
  }

  private void remove(Long id) {
    if (id == null) {
      return;
    }
    List<Long> ids = new ArrayList<Long>();
    ids.add(id);
    remove(ids);
  }

  private void remove(Collection<Long> ids) {
    try {
      mirrorStore.removeResultMetadata(ids);
    } catch (IOException e) {
      LOG.error("Could not remove Jenkins Bridge result metadata", e);
    }
  }
}
