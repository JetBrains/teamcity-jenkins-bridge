package com.jetbrains.teamcity.jenkinsbridge.persistence;

import com.jetbrains.teamcity.jenkinsbridge.model.JenkinsJobParameters;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;

/** Persisted job definitions, independent of submitted values and individual build mirrors. */
public final class JenkinsJobParameterMetadata {
  private final JenkinsJobParameters definitions;
  private final String snapshot;

  public JenkinsJobParameterMetadata(@NotNull JenkinsJobParameters definitions) {
    this.definitions = Objects.requireNonNull(definitions);
    this.snapshot = definitions.snapshot();
  }

  @NotNull
  public JenkinsJobParameters getDefinitions() {
    return definitions;
  }

  @NotNull
  public String getSnapshot() {
    return snapshot;
  }
}
