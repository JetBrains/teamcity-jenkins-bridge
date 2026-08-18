package com.jetbrains.teamcity.jenkinsbridge.teamcity;

import com.google.gson.JsonParser;
import com.jetbrains.teamcity.jenkinsbridge.model.JenkinsTestReport;
import jetbrains.buildServer.messages.BlockData;
import jetbrains.buildServer.messages.BuildMessage1;
import jetbrains.buildServer.messages.DefaultMessagesInfo;
import jetbrains.buildServer.messages.ErrorData;
import jetbrains.buildServer.messages.IgnoredTestData;
import jetbrains.buildServer.messages.TestFinishBlockData;
import jetbrains.buildServer.messages.TestOutputData;
import jetbrains.buildServer.messages.TestProblemData;
import jetbrains.buildServer.serverSide.RunningBuildEx;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class TeamCityTestReporterTest {
  private final JsonParser parser = new JsonParser();

  @Test
  public void createsTeamCityTestMessagesFromJenkinsReport() {
    JenkinsTestReport report = JenkinsTestReport.fromJson(parser.parse("{"
        + "\"suites\":[{"
        + "\"name\":\"suite.one\","
        + "\"cases\":["
        + "{\"className\":\"com.acme.FastTest\",\"name\":\"passes\",\"status\":\"PASSED\",\"duration\":1.2},"
        + "{\"className\":\"com.acme.FastTest\",\"name\":\"fails\",\"status\":\"FAILED\",\"duration\":0.5,"
        + "\"errorDetails\":\"expected true\",\"errorStackTrace\":\"stack\","
        + "\"stdout\":\"hello\",\"stderr\":\"warn\"},"
        + "{\"name\":\"is skipped\",\"status\":\"SKIPPED\",\"skippedMessage\":\"disabled\"}"
        + "]"
        + "}]"
        + "}").getAsJsonObject());

    TeamCityTestReporter reporter = new TeamCityTestReporter(null, null);
    List<BuildMessage1> messages = reporter.createTestMessages(report);

    assertEquals(12, messages.size());
    assertMessage(messages.get(0), DefaultMessagesInfo.MSG_BLOCK_START, "suite.one");
    assertMessage(messages.get(1), DefaultMessagesInfo.MSG_BLOCK_START, "com.acme.FastTest.passes");
    assertMessage(messages.get(2), DefaultMessagesInfo.MSG_BLOCK_END, "com.acme.FastTest.passes");
    assertEquals(Integer.valueOf(1200), ((TestFinishBlockData)messages.get(2).getValue()).getDuration());

    assertMessage(messages.get(3), DefaultMessagesInfo.MSG_BLOCK_START, "com.acme.FastTest.fails");
    assertMessage(messages.get(4), DefaultMessagesInfo.MSG_TEST_OUTPUT, "com.acme.FastTest.fails");
    TestOutputData stdout = (TestOutputData)messages.get(4).getValue();
    assertTrue(stdout.isStdOut);
    assertEquals("hello", stdout.text);

    assertMessage(messages.get(5), DefaultMessagesInfo.MSG_TEST_OUTPUT, "com.acme.FastTest.fails");
    TestOutputData stderr = (TestOutputData)messages.get(5).getValue();
    assertTrue(!stderr.isStdOut);
    assertEquals("warn", stderr.text);

    assertMessage(messages.get(6), DefaultMessagesInfo.MSG_TEST_FAILURE, "com.acme.FastTest.fails");
    TestProblemData failure = (TestProblemData)messages.get(6).getValue();
    assertEquals("com.acme.FastTest.fails", failure.testName);
    assertEquals("expected true", ((ErrorData)failure).localizedMessage);
    assertEquals("stack", ((ErrorData)failure).stackTrace);
    assertMessage(messages.get(7), DefaultMessagesInfo.MSG_BLOCK_END, "com.acme.FastTest.fails");
    assertEquals(Integer.valueOf(500), ((TestFinishBlockData)messages.get(7).getValue()).getDuration());

    assertMessage(messages.get(8), DefaultMessagesInfo.MSG_BLOCK_START, "is skipped");
    assertMessage(messages.get(9), DefaultMessagesInfo.MSG_TEST_IGNORED, "is skipped");
    IgnoredTestData ignored = (IgnoredTestData)messages.get(9).getValue();
    assertEquals("disabled", ignored.ignoreReason);
    assertMessage(messages.get(10), DefaultMessagesInfo.MSG_BLOCK_END, "is skipped");
    assertMessage(messages.get(11), DefaultMessagesInfo.MSG_BLOCK_END, "suite.one");

    for (BuildMessage1 message : messages) {
      assertTrue(message.hasTag(DefaultMessagesInfo.TAG_SERVER));
    }
  }

  @Test(expected = TeamCityRunningBuildNotFoundException.class)
  public void failsWhenTeamCityBuildIsNotRunning() {
    TeamCityRunningBuildLocator locator = new TeamCityRunningBuildLocator(null, null, null) {
      @Override
      public RunningBuildEx findRunningBuild(long id) {
        return null;
      }
    };
    TeamCityTestReporter reporter = new TeamCityTestReporter(locator, null);
    JenkinsTestReport report = JenkinsTestReport.fromJson(parser.parse(
        "{\"suites\":[{\"name\":\"suite\",\"cases\":[{\"name\":\"test\",\"status\":\"PASSED\"}]}]}"
    ).getAsJsonObject());

    reporter.reportTests(42L, report);
  }

  private void assertMessage(BuildMessage1 message, String type, String name) {
    assertEquals(type, message.getTypeId());
    Object value = message.getValue();
    if (value instanceof BlockData) {
      assertEquals(name, ((BlockData)value).blockName);
    } else if (value instanceof TestProblemData) {
      assertEquals(name, ((TestProblemData)value).testName);
    } else if (value instanceof IgnoredTestData) {
      assertEquals(name, ((IgnoredTestData)value).testName);
    } else if (value instanceof TestOutputData) {
      assertEquals(name, ((TestOutputData)value).testName);
    }
  }
}
