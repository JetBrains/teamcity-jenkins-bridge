package com.jetbrains.teamcity.jenkinsbridge.util;

import com.google.gson.JsonObject;
import jetbrains.buildServer.serverSide.ProjectManager;
import jetbrains.buildServer.serverSide.SBuildType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public final class Utilities {

  private Utilities() {
  }

  @NotNull
  public static String nullToEmpty(@Nullable String value) {
    return value == null ? "" : value;
  }

  @Nullable
  public static SBuildType findBuildType(@Nullable String buildTypeId, @NotNull ProjectManager projectManager) {
    if (buildTypeId == null || buildTypeId.trim().isEmpty()) {
      return null;
    }
    SBuildType buildType = projectManager.findBuildTypeByExternalId(buildTypeId);
    if (buildType != null) {
      return buildType;
    }
    return projectManager.findBuildTypeById(buildTypeId);
  }

  @NotNull
  public static String stringValue(@Nullable JsonObject object, @Nullable String key) {
    if (object == null || object.get(key) == null || object.get(key).isJsonNull()) {
      return "";
    }
    return object.get(key).getAsString();
  }

  @NotNull
  public static String describeException(@NotNull Exception e) {
    return e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage());
  }
}
