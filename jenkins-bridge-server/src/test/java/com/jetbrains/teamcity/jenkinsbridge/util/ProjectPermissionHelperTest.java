package com.jetbrains.teamcity.jenkinsbridge.util;

import jetbrains.buildServer.serverSide.SProject;
import jetbrains.buildServer.serverSide.auth.Permission;
import jetbrains.buildServer.users.SUser;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class ProjectPermissionHelperTest {
  @Test
  public void deniesMissingUser() {
    assertFalse(ProjectPermissionHelper.hasProjectPermission(null, mock(SProject.class), Permission.EDIT_PROJECT));
  }

  @Test
  public void delegatesPermissionCheckToUser() {
    SUser user = mock(SUser.class);
    when(user.isPermissionGrantedForProject("project", Permission.VIEW_PROJECT)).thenReturn(true);

    assertTrue(ProjectPermissionHelper.hasProjectPermission(user, "project", Permission.VIEW_PROJECT));
  }
}
