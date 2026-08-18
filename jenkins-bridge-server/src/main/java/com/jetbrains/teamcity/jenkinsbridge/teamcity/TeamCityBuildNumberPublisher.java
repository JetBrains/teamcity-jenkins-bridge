package com.jetbrains.teamcity.jenkinsbridge.teamcity;

import jetbrains.buildServer.serverSide.RunningBuildEx;

public class TeamCityBuildNumberPublisher {

  private final TeamCityRunningBuildLocator myBuildLocator;

  public TeamCityBuildNumberPublisher(TeamCityRunningBuildLocator buildLocator) {
    myBuildLocator = buildLocator;
  }

  /**
   * Updates a TeamCity build with the build number from Jenkins.
   *
   * @param buildId            the ID of the build in TeamCity
   * @param jenkinsBuildNumber the Jenkins build number to assign to the TeamCity build
   * @return true if the build number was successfully published, false otherwise
   */
  public boolean publishBuildNumber(long buildId, int jenkinsBuildNumber) {
    RunningBuildEx build = myBuildLocator.findRunningBuild(buildId);
    if (build == null) {
      return false;
    }
    build.setBuildNumber(String.valueOf(jenkinsBuildNumber));
    return true;
  }
}
