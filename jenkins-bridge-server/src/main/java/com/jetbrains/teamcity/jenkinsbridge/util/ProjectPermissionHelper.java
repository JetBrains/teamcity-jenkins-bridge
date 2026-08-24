package com.jetbrains.teamcity.jenkinsbridge.util;

import jetbrains.buildServer.serverSide.SProject;
import jetbrains.buildServer.serverSide.auth.Permission;
import jetbrains.buildServer.users.SUser;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** Shared project-permission checks for Jenkins Bridge web entry points. */
public final class ProjectPermissionHelper {
  private ProjectPermissionHelper() {
  }

  public static boolean hasProjectPermission(@Nullable SUser user,
                                             @NotNull SProject project,
                                             @NotNull Permission permission) {
    return hasProjectPermission(user, project.getProjectId(), permission);
  }

  public static boolean hasProjectPermission(@Nullable SUser user,
                                             @NotNull String projectId,
                                             @NotNull Permission permission) {
    return user != null && user.isPermissionGrantedForProject(projectId, permission);
  }

  public static boolean hasAnyProjectPermission(@Nullable SUser user,
                                                @NotNull SProject project,
                                                @NotNull Permission... permissions) {
    if (permissions == null) {
      return false;
    }
    for (Permission permission : permissions) {
      if (permission != null && hasProjectPermission(user, project, permission)) {
        return true;
      }
    }
    return false;
  }
}
