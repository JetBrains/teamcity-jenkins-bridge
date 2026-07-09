package com.jetbrains.teamcity.jenkinsbridge.teamcity;

import com.google.gson.JsonParser;
import com.jetbrains.teamcity.jenkinsbridge.model.JenkinsVcsInfo;
import com.jetbrains.teamcity.jenkinsbridge.persistence.BuildMirror;
import com.jetbrains.teamcity.jenkinsbridge.vcs.VcsBuildCustomization;
import com.jetbrains.teamcity.jenkinsbridge.vcs.VcsNormalizer;
import com.jetbrains.teamcity.jenkinsbridge.vcs.VcsSyncResult;
import jetbrains.buildServer.serverSide.ProjectManager;
import jetbrains.buildServer.serverSide.SBuildType;
import jetbrains.buildServer.serverSide.SProject;
import jetbrains.buildServer.vcs.CheckoutRules;
import jetbrains.buildServer.vcs.SVcsRoot;
import jetbrains.buildServer.vcs.VcsRootInstance;
import jetbrains.buildServer.vcs.VcsRootInstanceEntry;
import org.junit.Before;
import org.junit.Test;

import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class TeamCityVcsPublisherTest {
  private static final String BUILD_TYPE_ID = "MyProject_Mirror";
  private static final long PROMOTION_ID = 55L;

  private final ProjectManager projectManager = mock(ProjectManager.class);
  private final SProject project = mock(SProject.class);
  private final SBuildType buildType = mock(SBuildType.class);

  private final TeamCityVcsPublisher publisher = new TeamCityVcsPublisher(projectManager, new VcsNormalizer());

  @Before
  public void setUp() {
    when(projectManager.findBuildTypeByExternalId(BUILD_TYPE_ID)).thenReturn(buildType);
    when(buildType.getProject()).thenReturn(project);
    when(project.getVcsRoots()).thenReturn(Collections.emptyList());
  }

  @Test
  public void createsRootAttachesAndPinsRevisionWhenNoneExists() {
    SVcsRoot created = gitRoot("https://github.com/org/repo.git");
    when(project.createVcsRoot(eq("jetbrains.git"), anyString(), anyMap())).thenReturn(created);
    VcsRootInstanceEntry entry = entry(11L);
    when(buildType.getVcsRootInstanceEntryForParent(created)).thenReturn(null, entry);

    VcsBuildCustomization customization =
        publisher.prepareVcs(mirror(), gitInfo("https://github.com/org/repo.git", "abc123def456", "refs/remotes/origin/main"));
    VcsSyncResult result = customization.result();

    verify(project).createVcsRoot(eq("jetbrains.git"), anyString(), anyMap());
    verify(created).persist();
    verify(buildType).addVcsRoot(created);
    verify(buildType).setCheckoutRules(created, CheckoutRules.DEFAULT);
    verify(buildType).persist();
    assertEquals("main", customization.desiredBranchName());
    assertEquals(1, customization.upperLimitRevisions().size());
    assertEquals("abc123def456", customization.upperLimitRevisions().get(11L).getVersion());
    assertEquals(1, result.getNumberOfAttachedRepositories());
    assertFalse(result.hasErrors());
  }

  @Test
  public void reusesExistingRootAndDoesNotCreateOrReattach() {
    SVcsRoot existing = gitRoot("https://github.com/org/repo.git");
    when(project.getVcsRoots()).thenReturn(Collections.singletonList(existing));
    VcsRootInstanceEntry entry = entry(12L);
    when(buildType.getVcsRootInstanceEntryForParent(existing)).thenReturn(entry);

    VcsBuildCustomization customization =
        publisher.prepareVcs(mirror(), gitInfo("https://github.com/org/repo.git", "abc123", "refs/remotes/origin/main"));
    VcsSyncResult result = customization.result();

    verify(project, never()).createVcsRoot(anyString(), anyString(), anyMap());
    verify(buildType, never()).addVcsRoot(any(SVcsRoot.class));
    verify(buildType, never()).persist();
    assertEquals(1, customization.upperLimitRevisions().size());
    assertEquals(1, result.getNumberOfAttachedRepositories());
  }

  @Test
  public void matchesExistingRootAcrossUrlShapes() {
    SVcsRoot existing = gitRoot("git@github.com:org/repo.git");
    VcsRootInstanceEntry entry = entry(13L);
    when(project.getVcsRoots()).thenReturn(Collections.singletonList(existing));
    when(buildType.getVcsRootInstanceEntryForParent(existing)).thenReturn(entry);

    publisher.publishVcs(mirror(), gitInfo("https://github.com/org/repo.git", "abc123", "refs/remotes/origin/main"));

    verify(project, never()).createVcsRoot(anyString(), anyString(), anyMap());
  }

  @Test
  public void pinsOneRevisionPerRepositoryForMultipleRepositories() {
    SVcsRoot first = gitRoot("git@github.com:org/first.git");
    SVcsRoot second = gitRoot("https://github.com/org/second.git");
    VcsRootInstanceEntry firstEntry = entry(21L);
    VcsRootInstanceEntry secondEntry = entry(22L);
    when(project.createVcsRoot(eq("jetbrains.git"), anyString(), anyMap())).thenReturn(first, second);
    when(buildType.getVcsRootInstanceEntryForParent(first)).thenReturn(null, firstEntry);
    when(buildType.getVcsRootInstanceEntryForParent(second)).thenReturn(null, secondEntry);

    String json = "{\"actions\":["
        + "{\"_class\":\"hudson.plugins.git.util.BuildData\","
        + "\"lastBuiltRevision\":{\"SHA1\":\"aaa\",\"branch\":[{\"name\":\"refs/remotes/origin/main\"}]},"
        + "\"remoteUrls\":[\"git@github.com:org/first.git\"]},"
        + "{\"_class\":\"hudson.plugins.git.util.BuildData\","
        + "\"lastBuiltRevision\":{\"SHA1\":\"bbb\",\"branch\":[{\"name\":\"refs/remotes/origin/master\"}]},"
        + "\"remoteUrls\":[\"https://github.com/org/second.git\"]}"
        + "]}";

    VcsBuildCustomization customization = publisher.prepareVcs(mirror(), vcsInfo(json));
    VcsSyncResult result = customization.result();

    assertEquals(2, customization.upperLimitRevisions().size());
    assertEquals(2, result.getNumberOfAttachedRepositories());
  }

  @Test
  public void recordsErrorWhenBuildTypeMissing() {
    when(projectManager.findBuildTypeByExternalId(BUILD_TYPE_ID)).thenReturn(null);
    when(projectManager.findBuildTypeById(BUILD_TYPE_ID)).thenReturn(null);

    VcsSyncResult result =
        publisher.prepareVcs(mirror(), gitInfo("https://github.com/org/repo.git", "abc123", "refs/remotes/origin/main")).result();

    assertTrue(result.hasErrors());
  }

  @Test
  public void leavesCreatedRootAndRecordsErrorWhenAttachFails() {
    SVcsRoot created = gitRoot("https://github.com/org/repo.git");
    when(project.createVcsRoot(eq("jetbrains.git"), anyString(), anyMap())).thenReturn(created);
    when(buildType.getVcsRootInstanceEntryForParent(created)).thenReturn(null);
    when(buildType.addVcsRoot(created)).thenThrow(new RuntimeException("read only"));

    VcsSyncResult result = publisher.publishVcs(mirror(), gitInfo("https://github.com/org/repo.git", "abc123", "refs/remotes/origin/main"));

    verify(created).persist();
    assertTrue(result.hasErrors());
    assertEquals(0, result.getNumberOfAttachedRepositories());
  }

  private SVcsRoot gitRoot(String url) {
    SVcsRoot root = mock(SVcsRoot.class);
    when(root.getVcsName()).thenReturn("jetbrains.git");
    when(root.getProperty("url")).thenReturn(url);
    return root;
  }

  private VcsRootInstanceEntry entry(long id) {
    VcsRootInstance rootInstance = mock(VcsRootInstance.class);
    when(rootInstance.getId()).thenReturn(id);
    VcsRootInstanceEntry entry = mock(VcsRootInstanceEntry.class);
    when(entry.getVcsRoot()).thenReturn(rootInstance);
    return entry;
  }

  private BuildMirror mirror() {
    BuildMirror mirror = new BuildMirror();
    mirror.setTeamCityBuildTypeId(BUILD_TYPE_ID);
    mirror.setTeamCityBuildId(PROMOTION_ID);
    return mirror;
  }

  private JenkinsVcsInfo gitInfo(String url, String sha1, String branch) {
    String json = "{\"actions\":[{\"_class\":\"hudson.plugins.git.util.BuildData\","
        + "\"lastBuiltRevision\":{\"SHA1\":\"" + sha1 + "\",\"branch\":[{\"name\":\"" + branch + "\"}]},"
        + "\"remoteUrls\":[\"" + url + "\"]}]}";
    return vcsInfo(json);
  }

  private JenkinsVcsInfo vcsInfo(String json) {
    return JenkinsVcsInfo.fromJson(JsonParser.parseString(json).getAsJsonObject());
  }
}
