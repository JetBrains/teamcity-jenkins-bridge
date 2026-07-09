package com.jetbrains.teamcity.jenkinsbridge.model;

import com.google.gson.JsonParser;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class JenkinsVcsInfoTest {
  @Test
  public void parsesMultipleGitRepositoriesInOrder() {
    JenkinsVcsInfo info = parse("{\"actions\":[{},"
        + "{\"_class\":\"hudson.plugins.git.util.BuildData\","
        + "\"lastBuiltRevision\":{\"SHA1\":\"aaa\",\"branch\":[{\"name\":\"refs/remotes/origin/main\"}]},"
        + "\"remoteUrls\":[\"git@github.com:org/first.git\"]},"
        + "{\"_class\":\"hudson.plugins.git.util.BuildData\","
        + "\"lastBuiltRevision\":{\"SHA1\":\"bbb\",\"branch\":[{\"name\":\"refs/remotes/origin/master\"}]},"
        + "\"remoteUrls\":[\"https://github.com/org/second.git\"]}"
        + "]}");

    assertEquals(2, info.size());
    assertEquals("aaa", info.getRepositories().getFirst().sha1());
    assertEquals("git@github.com:org/first.git", info.getRepositories().get(0).remoteUrl());
    assertEquals("refs/remotes/origin/main", info.getRepositories().get(0).rawBranchName());
    assertEquals("bbb", info.getRepositories().get(1).sha1());
    assertEquals("https://github.com/org/second.git", info.getRepositories().get(1).remoteUrl());
  }

  @Test
  public void ignoresEmptyAndNonGitActions() {
    JenkinsVcsInfo info = parse("{\"actions\":[{},"
        + "{\"_class\":\"hudson.model.CauseAction\"},"
        + "{\"_class\":\"hudson.tasks.junit.TestResultAction\"}"
        + "]}");

    assertTrue(info.isEmpty());
  }

  @Test
  public void skipsGitActionsMissingRevisionOrUrl() {
    JenkinsVcsInfo info = parse("{\"actions\":["
        + "{\"_class\":\"hudson.plugins.git.util.BuildData\","
        + "\"lastBuiltRevision\":{\"branch\":[{\"name\":\"refs/remotes/origin/main\"}]},"
        + "\"remoteUrls\":[\"git@github.com:org/no-sha.git\"]},"
        + "{\"_class\":\"hudson.plugins.git.util.BuildData\","
        + "\"lastBuiltRevision\":{\"SHA1\":\"ccc\",\"branch\":[]},"
        + "\"remoteUrls\":[]}"
        + "]}");

    assertTrue(info.isEmpty());
  }

  @Test
  public void deduplicatesRepositoriesByNormalizedUrl() {
    JenkinsVcsInfo info = parse("{\"actions\":["
        + "{\"_class\":\"hudson.plugins.git.util.BuildData\","
        + "\"lastBuiltRevision\":{\"SHA1\":\"aaa\",\"branch\":[{\"name\":\"refs/remotes/origin/main\"}]},"
        + "\"remoteUrls\":[\"git@github.com:org/repo.git\"]},"
        + "{\"_class\":\"hudson.plugins.git.util.BuildData\","
        + "\"lastBuiltRevision\":{\"SHA1\":\"aaa\",\"branch\":[{\"name\":\"refs/remotes/origin/main\"}]},"
        + "\"remoteUrls\":[\"https://github.com/org/repo.git\"]}"
        + "]}");

    assertEquals(1, info.size());
  }

  @Test
  public void returnsEmptyWhenActionsMissing() {
    assertTrue(parse("{}").isEmpty());
    assertTrue(parse("{\"actions\":[]}").isEmpty());
    assertTrue(JenkinsVcsInfo.empty().isEmpty());
  }

  private JenkinsVcsInfo parse(String json) {
    return JenkinsVcsInfo.fromJson(JsonParser.parseString(json).getAsJsonObject());
  }
}
