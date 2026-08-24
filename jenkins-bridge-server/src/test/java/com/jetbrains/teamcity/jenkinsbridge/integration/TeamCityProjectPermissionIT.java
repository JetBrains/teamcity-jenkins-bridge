package com.jetbrains.teamcity.jenkinsbridge.integration;

import com.jetbrains.teamcity.jenkinsbridge.util.ProjectPermissionHelper;
import jetbrains.buildServer.serverSide.SProject;
import jetbrains.buildServer.serverSide.auth.Permission;
import jetbrains.buildServer.serverSide.impl.BaseServerTestCase;
import jetbrains.buildServer.users.UserModel;
import org.testng.annotations.Test;

import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

/** Verifies project permissions using TeamCity's real user and project services. */
public class TeamCityProjectPermissionIT extends BaseServerTestCase {
  @Test
  public void superUserCanEditProjectAndGuestCannot() {
    UserModel userModel = myFixture.getSingletonService(UserModel.class);
    SProject project = myProject;

    assertTrue(ProjectPermissionHelper.hasProjectPermission(
        userModel.getSuperUser(), project, Permission.EDIT_PROJECT));
    assertFalse(ProjectPermissionHelper.hasProjectPermission(
        userModel.getGuestUser(), project, Permission.EDIT_PROJECT));
  }
}
