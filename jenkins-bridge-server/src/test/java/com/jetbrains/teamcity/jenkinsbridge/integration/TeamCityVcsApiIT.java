package com.jetbrains.teamcity.jenkinsbridge.integration;

import com.google.gson.JsonObject;
import com.jetbrains.teamcity.jenkinsbridge.jenkins.JenkinsClient;
import com.jetbrains.teamcity.jenkinsbridge.jenkins.JenkinsClientFactory;
import com.jetbrains.teamcity.jenkinsbridge.model.JenkinsBuildInfo;
import com.jetbrains.teamcity.jenkinsbridge.model.JenkinsVcsInfo;
import com.jetbrains.teamcity.jenkinsbridge.model.JenkinsVcsRepository;
import com.jetbrains.teamcity.jenkinsbridge.persistence.BuildMirror;
import com.jetbrains.teamcity.jenkinsbridge.teamcity.TeamCityVcsPublisher;
import com.jetbrains.teamcity.jenkinsbridge.vcs.VcsRefType;
import com.jetbrains.teamcity.jenkinsbridge.vcs.VcsSyncResult;
import jetbrains.buildServer.serverSide.impl.CancelableTaskHolder;
import jetbrains.buildServer.vcs.SVcsRoot;
import jetbrains.buildServer.vcs.impl.BuildChainChangesCollector;
import org.mockito.Mockito;
import org.testng.annotations.Test;

import java.util.Collections;

/** Verifies VCS-root and revision mutation against real TeamCity project/build APIs. */
public class TeamCityVcsApiIT extends TeamCityIntegrationTestBase {
  @Test
  public void createsAttachesAndReusesGitRootWithProvidedRevision() throws Exception {
    myFixture.registerVcsSupport("jetbrains.git");
    TeamCityBuildFixture build = queueAndStartBuild(
        Collections.singletonMap("jenkins.build.key", "vcs-api-1"), Collections.emptyMap());
    BuildMirror mirror = BuildMirror.create(
        "vcs-api-1", "source-job", buildInfo(12), myBuildType.getExternalId(), "it-now");
    mirror.setTeamCityBuildId(build.getPromotionId());

    JenkinsClient jenkinsClient = Mockito.mock(JenkinsClient.class);
    Mockito.when(jenkinsClient.getBranchRefType("source-job")).thenReturn(VcsRefType.HEADS);
    JenkinsClientFactory clientFactory = Mockito.mock(JenkinsClientFactory.class);
    Mockito.when(clientFactory.forBuildType(myBuildType)).thenReturn(jenkinsClient);
    BuildChainChangesCollector changesCollector = Mockito.mock(BuildChainChangesCollector.class);
    TeamCityVcsPublisher publisher = new TeamCityVcsPublisher(
        myProjectManager, build.getLocator(), changesCollector, clientFactory);

    JenkinsVcsInfo vcsInfo = new JenkinsVcsInfo(Collections.singletonList(
        new JenkinsVcsRepository(
            "hudson.plugins.git.util.BuildData",
            "https://github.com/example/repository.git",
            "abc123def456",
            "refs/remotes/origin/main")));

    VcsSyncResult first = publisher.applyVcsToBuild(mirror, vcsInfo);
    assertFalse(first.hasErrors());
    assertEquals(1, first.getNumberOfAttachedRepositories());

    SVcsRoot root = myBuildType.getProject().getVcsRoots().stream()
        .filter(candidate -> "https://github.com/example/repository.git"
            .equals(candidate.getProperty("url")))
        .findFirst()
        .orElse(null);
    assertNotNull(root);
    assertNotNull(myBuildType.getVcsRootInstanceEntryForParent(root));
    assertEquals("https://github.com/example/repository.git", root.getProperty("url"));

    VcsSyncResult second = publisher.applyVcsToBuild(mirror, vcsInfo);
    assertFalse(second.hasErrors());
    assertEquals(1, myBuildType.getProject().getVcsRoots().stream()
        .filter(candidate -> "https://github.com/example/repository.git"
            .equals(candidate.getProperty("url")))
        .count());
    Mockito.verify(changesCollector, Mockito.times(2))
        .scheduleCheckingForChangesAndWait(Mockito.any(), Mockito.any(CancelableTaskHolder.class));
  }

  @Test
  public void unsupportedVcsIsSkippedWithoutMutatingTeamCity() throws Exception {
    TeamCityBuildFixture build = queueAndStartBuild(
        Collections.singletonMap("jenkins.build.key", "vcs-api-unsupported"), Collections.emptyMap());
    BuildMirror mirror = BuildMirror.create(
        "vcs-api-unsupported", "source-job", buildInfo(13), myBuildType.getExternalId(), "it-now");
    mirror.setTeamCityBuildId(build.getPromotionId());
    TeamCityVcsPublisher publisher = new TeamCityVcsPublisher(
        myProjectManager, build.getLocator(), Mockito.mock(BuildChainChangesCollector.class),
        Mockito.mock(JenkinsClientFactory.class));

    VcsSyncResult result = publisher.applyVcsToBuild(mirror, new JenkinsVcsInfo(Collections.singletonList(
        new JenkinsVcsRepository("hudson.plugins.mercurial.MercurialTagAction",
            "", "abc", "default"))));
    assertFalse(result.hasErrors());
    assertEquals(0, result.getNumberOfAttachedRepositories());
    assertTrue(myBuildType.getProject().getVcsRoots().isEmpty());
  }

  private static JenkinsBuildInfo buildInfo(int number) {
    JsonObject json = new JsonObject();
    json.addProperty("number", number);
    json.addProperty("building", true);
    json.addProperty("url", "http://jenkins/job/source-job/" + number + "/");
    return JenkinsBuildInfo.fromJson(json);
  }
}
