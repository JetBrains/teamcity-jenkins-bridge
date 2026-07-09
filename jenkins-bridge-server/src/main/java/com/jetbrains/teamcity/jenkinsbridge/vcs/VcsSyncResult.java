package com.jetbrains.teamcity.jenkinsbridge.vcs;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Outcome of publishing VCS information for one mirrored build.
 */
public class VcsSyncResult {
  private int numberOfAttachedRepositories;
  private final List<String> errors = new ArrayList<>();

  public void incrementAttached() {
    numberOfAttachedRepositories++;
  }

  public int getNumberOfAttachedRepositories() {
    return numberOfAttachedRepositories;
  }

  public void addError(@NotNull String error) {
    if (!error.isEmpty()) {
      errors.add(error);
    }
  }

  public List<String> getErrors() {
    return Collections.unmodifiableList(errors);
  }

  public boolean hasErrors() {
    return !errors.isEmpty();
  }
}
