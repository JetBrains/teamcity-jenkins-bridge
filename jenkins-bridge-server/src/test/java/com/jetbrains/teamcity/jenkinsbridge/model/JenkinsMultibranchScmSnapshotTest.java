package com.jetbrains.teamcity.jenkinsbridge.model;

import com.google.gson.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class JenkinsMultibranchScmSnapshotTest {
  @Test public void readsCommonPrimaryMarkerAndActualTypedHead() {
    var snapshot = parse(child("main", "jenkins.scm.api.SCMHead", true), child("release/v1", "jenkins.plugins.git.GitTagSCMHead", false));
    assertEquals("refs/heads/main", snapshot.defaultRef("git@github.com:org/repo.git").orElseThrow());
    assertEquals("refs/tags/release/v1", snapshot.child("parent/release%2Fv1").orElseThrow().ref());
    assertTrue(snapshot.defaultRef("https://github.com/other/repo.git").isEmpty());
  }
  @Test public void missingOrConflictingPrimaryMarkersDoNotGuessDefault() {
    assertTrue(parse(child("deb", "jenkins.scm.api.SCMHead", false)).defaultRef("github.com/org/repo").isEmpty());
    assertTrue(parse(child("main", "jenkins.scm.api.SCMHead", true), child("deb", "jenkins.scm.api.SCMHead", true))
        .defaultRef("github.com/org/repo").isEmpty());
  }
  @Test public void primaryTagsAndPullRequestsCannotBecomeRepositoryDefault() {
    assertTrue(parse(child("v1", "jenkins.plugins.git.GitTagSCMHead", true), child("PR-1", "github.PullRequestSCMHead", true))
        .defaultRef("github.com/org/repo").isEmpty());
  }
  @Test public void missingHeadTypeAndMultipleRemotesAreRejected() {
    JsonObject child = child("main", "", true);
    assertTrue(parse(child).children().isEmpty());
    child = child("main", "jenkins.scm.api.SCMHead", true);
    JsonObject remote = new JsonObject(); remote.addProperty("url", "https://github.com/other/repo.git");
    child.getAsJsonArray("property").get(0).getAsJsonObject().getAsJsonObject("branch").getAsJsonObject("scm")
        .getAsJsonArray("userRemoteConfigs").add(remote);
    assertTrue(parse(child).children().isEmpty());
  }
  static JenkinsMultibranchScmSnapshot parse(JsonObject... jobs) {
    JsonObject root = new JsonObject(); JsonArray array = new JsonArray();
    for (var job : jobs) array.add(job); root.add("jobs", array);
    return JenkinsMultibranchScmSnapshot.fromJson(root);
  }
  static JsonObject child(String head, String type, boolean primary) {
    JsonObject job = new JsonObject(); job.addProperty("fullName", "parent/" + head.replace("/", "%2F"));
    JsonObject branch = new JsonObject(), h = new JsonObject(), scm = new JsonObject();
    h.addProperty("name", head); h.addProperty("_class", type); branch.add("head", h);
    scm.addProperty("_class", "hudson.plugins.git.GitSCM"); JsonArray remotes = new JsonArray();
    JsonObject remote = new JsonObject(); remote.addProperty("url", "https://github.com/org/repo.git");
    remotes.add(remote); scm.add("userRemoteConfigs", remotes); branch.add("scm", scm);
    JsonObject property = new JsonObject(); property.add("branch", branch); JsonArray properties = new JsonArray();
    properties.add(property); job.add("property", properties);
    if (primary) { JsonArray actions = new JsonArray(); JsonObject action = new JsonObject();
      action.addProperty("_class", JenkinsMultibranchScmSnapshot.PRIMARY_ACTION); actions.add(action); job.add("actions", actions); }
    return job;
  }
}
