package com.jetbrains.teamcity.jenkinsbridge.feature;

import com.google.gson.Gson;
import com.jetbrains.teamcity.jenkinsbridge.http.BridgeHttpException;
import com.jetbrains.teamcity.jenkinsbridge.jenkins.JenkinsClient;
import com.jetbrains.teamcity.jenkinsbridge.jenkins.JenkinsDataException;
import com.jetbrains.teamcity.jenkinsbridge.persistence.BuildMirrorStore;
import com.jetbrains.teamcity.jenkinsbridge.persistence.JenkinsJobParameterMetadata;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.List;

/** Fetches and persists job schemas without modifying TeamCity configuration parameters. */
public final class JenkinsParameterDefinitionService {
  private static final Gson KEY_GSON = new Gson();
  private final BuildMirrorStore mirrorStore;

  public JenkinsParameterDefinitionService(@NotNull BuildMirrorStore mirrorStore) {
    this.mirrorStore = mirrorStore;
  }

  @NotNull
  public RefreshResult refresh(@NotNull JenkinsClient client, @NotNull String buildTypeExternalId,
                               @NotNull String job) throws BridgeHttpException, JenkinsDataException, IOException {
    return refreshStored(client, key(client, buildTypeExternalId, job, job), job);
  }

  private RefreshResult refreshStored(JenkinsClient client, String key, String job)
      throws BridgeHttpException, JenkinsDataException, IOException {
    JenkinsJobParameterMetadata previous = mirrorStore.getJenkinsJobParameterMetadata(key);
    // Fetch successfully before replacing the cache, including a legitimate empty schema.
    JenkinsJobParameterMetadata current = new JenkinsJobParameterMetadata(client.getJobParameters(job));
    mirrorStore.saveJenkinsJobParameterMetadata(key, current);
    return new RefreshResult(current, previous == null ? null : previous.getSnapshot());
  }

  private static String key(JenkinsClient client, String owner, String parent, String job) {
    // JSON tuple avoids ambiguity when job names contain separators or encoded branch names.
    return KEY_GSON.toJson(List.of(client.getControllerIdentity(), owner, parent, job));
  }

  public record RefreshResult(@NotNull JenkinsJobParameterMetadata metadata,
                              @Nullable String previousSnapshot) {
  }
}
