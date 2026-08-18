package com.jetbrains.teamcity.jenkinsbridge.teamcity;

import org.junit.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class TeamCityBuildNumberPublisherTest {

  @Test(expected = NullPointerException.class)
  public void doesNotHideUnexpectedNullPointer() {
    TeamCityRunningBuildLocator locator = mock(TeamCityRunningBuildLocator.class);
    when(locator.findRunningBuild(17L)).thenThrow(new NullPointerException("unexpected bridge defect"));

    new TeamCityBuildNumberPublisher(locator).publishBuildNumber(17L, 42);
  }
}
