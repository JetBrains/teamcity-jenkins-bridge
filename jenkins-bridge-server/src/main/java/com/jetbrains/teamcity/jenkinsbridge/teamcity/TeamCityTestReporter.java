package com.jetbrains.teamcity.jenkinsbridge.teamcity;

import com.jetbrains.teamcity.jenkinsbridge.model.JenkinsTestCase;
import com.jetbrains.teamcity.jenkinsbridge.model.JenkinsTestReport;
import com.jetbrains.teamcity.jenkinsbridge.model.JenkinsTestSuite;
import jetbrains.buildServer.messages.BuildMessage1;
import jetbrains.buildServer.messages.DefaultMessagesInfo;
import jetbrains.buildServer.serverSide.RunningBuildEx;
import jetbrains.buildServer.serverSide.impl.BuildAgentMessagesQueue;

import java.util.ArrayList;
import java.util.List;

public class TeamCityTestReporter {
  private final TeamCityRunningBuildLocator buildLocator;
  private final BuildAgentMessagesQueue buildAgentMessagesQueue;

  public TeamCityTestReporter(
      TeamCityRunningBuildLocator buildLocator,
      BuildAgentMessagesQueue buildAgentMessagesQueue
  ) {
    this.buildLocator = buildLocator;
    this.buildAgentMessagesQueue = buildAgentMessagesQueue;
  }

  public void reportTests(long buildId, JenkinsTestReport report)
      throws TeamCityRunningBuildNotFoundException {
    if (report == null || report.isEmpty()) {
      return;
    }

    RunningBuildEx runningBuild = buildLocator.findRunningBuild(buildId);
    if (runningBuild == null) {
      throw new TeamCityRunningBuildNotFoundException(
          "TeamCity running build not found for id " + buildId);
    }

    List<BuildMessage1> messages = createTestMessages(report);
    if (messages.isEmpty()) {
      return;
    }

    try {
      buildAgentMessagesQueue.processMessages(runningBuild, messages);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted while reporting Jenkins tests to TeamCity", e);
    } catch (BuildAgentMessagesQueue.BuildMessagesQueueFullException e) {
      throw new IllegalStateException("TeamCity build messages queue is full", e);
    }
  }

  public List<BuildMessage1> createTestMessages(JenkinsTestReport report) {
    List<BuildMessage1> messages = new ArrayList<BuildMessage1>();
    if (report == null) {
      return messages;
    }

    for (JenkinsTestSuite suite : report.getSuites()) {
      if (suite.getCases().isEmpty()) {
        continue;
      }

      String suiteName = suite.getName();
      messages.add(serverMessage(DefaultMessagesInfo.createTestSuiteStart(suiteName)));
      for (JenkinsTestCase testCase : suite.getCases()) {
        String testName = testCase.getTeamCityTestName();
        messages.add(serverMessage(DefaultMessagesInfo.createTestBlockStart(testName)));
        if (testCase.hasStdout()) {
          messages.add(serverMessage(DefaultMessagesInfo.createTestStdout(testName, testCase.getStdout())));
        }
        if (testCase.hasStderr()) {
          messages.add(serverMessage(DefaultMessagesInfo.createTestStderr(testName, testCase.getStderr())));
        }
        if (testCase.isIgnored()) {
          messages.add(serverMessage(DefaultMessagesInfo.createTestIgnoreMessage(testName, testCase.getSkippedMessage())));
        }
        else if (testCase.isFailed()) {
          messages.add(serverMessage(DefaultMessagesInfo.createTestFailure(
              testName,
              testCase.getErrorDetails(),
              testCase.getErrorStackTrace()
          )));
        }
        messages.add(serverMessage(DefaultMessagesInfo.createTestBlockEnd(testName, testCase.getDurationMillis(), null)));
      }
      messages.add(serverMessage(DefaultMessagesInfo.createTestSuiteEnd(suiteName)));
    }

    return messages;
  }

  private BuildMessage1 serverMessage(BuildMessage1 message) {
    return message.updateTags(DefaultMessagesInfo.TAG_SERVER);
  }
}
