package com.jetbrains.teamcity.jenkinsbridge.teamcity;

import com.google.gson.JsonParser;
import com.jetbrains.teamcity.jenkinsbridge.jenkins.JenkinsClient;
import com.jetbrains.teamcity.jenkinsbridge.jenkins.JenkinsClientFactory;
import com.jetbrains.teamcity.jenkinsbridge.model.JenkinsVcsInfo;
import com.jetbrains.teamcity.jenkinsbridge.persistence.BuildMirror;
import com.jetbrains.teamcity.jenkinsbridge.vcs.VcsRefType;
import com.jetbrains.teamcity.jenkinsbridge.vcs.VcsSyncResult;
import jetbrains.buildServer.serverSide.BuildPromotionEx;
import jetbrains.buildServer.serverSide.ReadOnlyEntityException;
import jetbrains.buildServer.serverSide.ProjectManager;
import jetbrains.buildServer.serverSide.RepositoryVersion;
import jetbrains.buildServer.serverSide.SBuildType;
import jetbrains.buildServer.serverSide.SProject;
import jetbrains.buildServer.serverSide.impl.CancelableTaskHolder;
import jetbrains.buildServer.vcs.CheckoutRules;
import jetbrains.buildServer.vcs.SVcsRoot;
import jetbrains.buildServer.vcs.VcsRootInstance;
import jetbrains.buildServer.vcs.VcsRootInstanceEntry;
import jetbrains.buildServer.vcs.impl.BuildChainChangesCollector;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import java.util.Collections;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
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
  private final TeamCityRunningBuildLocator buildLocator = mock(TeamCityRunningBuildLocator.class);
  private final BuildChainChangesCollector changesCollector = mock(BuildChainChangesCollector.class);
  private final BuildPromotionEx promotion = mock(BuildPromotionEx.class);
  private final JenkinsClient jenkinsClient = mock(JenkinsClient.class);
  private final JenkinsClientFactory jenkinsClientFactory = mock(JenkinsClientFactory.class);

  private final TeamCityVcsPublisher publisher =
      new TeamCityVcsPublisher(projectManager, buildLocator, changesCollector, jenkinsClientFactory);

  @Before
  public void setUp() {
    when(projectManager.findBuildTypeByExternalId(BUILD_TYPE_ID)).thenReturn(buildType);
    when(buildType.getProject()).thenReturn(project);
    when(jenkinsClientFactory.forBuildType(buildType)).thenReturn(jenkinsClient);
    when(project.getVcsRoots()).thenReturn(Collections.emptyList());
    when(buildLocator.findPromotion(PROMOTION_ID)).thenReturn(promotion);
    when(jenkinsClient.getBranchRefType(any())).thenReturn(VcsRefType.HEADS);
  }

  @Test
  public void createsRootAttachesAndPinsRevisionWhenNoneExists() throws Exception {
    SVcsRoot created = gitRoot("https://github.com/org/repo.git");
    when(project.createVcsRoot(eq("jetbrains.git"), anyString(), anyMap())).thenReturn(created);
    VcsRootInstanceEntry entry = entry(11L);
    when(buildType.getVcsRootInstanceEntryForParent(created)).thenReturn(null, entry);

    VcsSyncResult result = publisher.applyVcsToBuild(
        mirror(), gitInfo("https://github.com/org/repo.git", "abc123def456", "refs/remotes/origin/main"));

    verify(project).createVcsRoot(eq("jetbrains.git"), anyString(), anyMap());
    verify(created).schedulePersisting("Jenkins Bridge: persist newly created Jenkins VCS root");
    verify(buildType).addVcsRoot(created);
    verify(buildType).setCheckoutRules(created, CheckoutRules.DEFAULT);
    verify(buildType).schedulePersisting("Jenkins Bridge: persist Jenkins VCS root attachment changes");
    verify(promotion).resetBuildRevisions();
    verify(promotion).setProvidedUpperLimitRevisions(anyMap());
    verify(changesCollector).scheduleCheckingForChangesAndWait(eq(promotion), any(CancelableTaskHolder.class));
    assertEquals(1, result.getNumberOfAttachedRepositories());
    assertFalse(result.hasErrors());
  }

  @Test
  public void pinsRevisionsForRepositoryUsingCapturedRevisionMap() throws Exception {
    SVcsRoot created = gitRoot("https://github.com/org/repo.git");
    when(project.createVcsRoot(eq("jetbrains.git"), anyString(), anyMap())).thenReturn(created);
    VcsRootInstanceEntry entry = entry(11L);
    when(buildType.getVcsRootInstanceEntryForParent(created)).thenReturn(null, entry);

    ArgumentCaptor<Map<Long, RepositoryVersion>> captor = ArgumentCaptor.captor();

    publisher.applyVcsToBuild(
        mirror(), gitInfo("https://github.com/org/repo.git", "abc123def456", "refs/remotes/origin/main"));

    verify(promotion).setProvidedUpperLimitRevisions(captor.capture());
    Map<Long, RepositoryVersion> revisions = captor.getValue();
    assertEquals(1, revisions.size());
    assertEquals("abc123def456", revisions.get(11L).getVersion());
  }

  @Test
  public void reusesExistingRootAndDoesNotCreateOrReattach() throws Exception {
    SVcsRoot existing = gitRoot("https://github.com/org/repo.git");
    when(project.getVcsRoots()).thenReturn(Collections.singletonList(existing));
    VcsRootInstanceEntry entry = entry(12L);
    when(buildType.getVcsRootInstanceEntryForParent(existing)).thenReturn(entry);

    VcsSyncResult result = publisher.applyVcsToBuild(
        mirror(), gitInfo("https://github.com/org/repo.git", "abc123", "refs/remotes/origin/main"));

    verify(project, never()).createVcsRoot(anyString(), anyString(), anyMap());
    verify(buildType, never()).addVcsRoot(any(SVcsRoot.class));
    verify(buildType, never()).schedulePersisting(anyString());
    verify(promotion).setProvidedUpperLimitRevisions(anyMap());
    assertEquals(1, result.getNumberOfAttachedRepositories());
  }

  @Test
  public void matchesExistingRootAcrossUrlShapes() throws Exception {
    SVcsRoot existing = gitRoot("git@github.com:org/repo.git");
    VcsRootInstanceEntry entry = entry(13L);
    when(project.getVcsRoots()).thenReturn(Collections.singletonList(existing));
    when(buildType.getVcsRootInstanceEntryForParent(existing)).thenReturn(entry);

    publisher.applyVcsToBuild(
        mirror(), gitInfo("https://github.com/org/repo.git", "abc123", "refs/remotes/origin/main"));

    verify(project, never()).createVcsRoot(anyString(), anyString(), anyMap());
  }

  @Test
  public void pinsOneRevisionPerRepositoryForMultipleRepositories() throws Exception {
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

    VcsSyncResult result = publisher.applyVcsToBuild(mirror(), vcsInfo(json));

    assertEquals(2, result.getNumberOfAttachedRepositories());
  }

  @Test
  public void applyVcsToBuildCreatesTagsSuffixedRootWithTagsBranchSpecForTagBuild() throws Exception {
    when(jenkinsClient.getBranchRefType(any())).thenReturn(VcsRefType.TAGS);
    SVcsRoot created = gitRoot("https://github.com/org/repo.git");
    ArgumentCaptor<String> nameCaptor = ArgumentCaptor.captor();
    ArgumentCaptor<Map<String, String>> paramsCaptor = ArgumentCaptor.captor();
    when(project.createVcsRoot(eq("jetbrains.git"), nameCaptor.capture(), paramsCaptor.capture()))
        .thenReturn(created);
    VcsRootInstanceEntry entry = entry(11L);
    when(buildType.getVcsRootInstanceEntryForParent(created)).thenReturn(null, entry);

    VcsSyncResult result = publisher.applyVcsToBuild(
        mirror(), gitInfo("https://github.com/org/repo.git", "abc123", "refs/remotes/origin/v1.0"));

    assertTrue(nameCaptor.getValue().endsWith("/tags"));
    assertEquals("+:refs/tags/*", paramsCaptor.getValue().get("teamcity:branchSpec"));
    assertEquals("refs/tags/v1.0", paramsCaptor.getValue().get("branch"));
    assertFalse(result.hasErrors());
  }

  @Test
  public void applyVcsToBuildDoesNotReuseHeadsRootForTagBuildWithSameUrl() throws Exception {
    when(jenkinsClient.getBranchRefType(any())).thenReturn(VcsRefType.TAGS);
    SVcsRoot headsRoot = gitRoot("https://github.com/org/repo.git");
    when(headsRoot.getName()).thenReturn("https://github.com/org/repo.git");
    when(project.getVcsRoots()).thenReturn(Collections.singletonList(headsRoot));
    SVcsRoot tagsRoot = gitRoot("https://github.com/org/repo.git");
    when(tagsRoot.getName()).thenReturn("https://github.com/org/repo.git/tags");
    when(project.createVcsRoot(eq("jetbrains.git"), anyString(), anyMap())).thenReturn(tagsRoot);
    VcsRootInstanceEntry entry = entry(14L);
    when(buildType.getVcsRootInstanceEntryForParent(tagsRoot)).thenReturn(null, entry);

    VcsSyncResult result = publisher.applyVcsToBuild(
        mirror(), gitInfo("https://github.com/org/repo.git", "abc123", "refs/remotes/origin/v1.0"));

    verify(project).createVcsRoot(eq("jetbrains.git"), anyString(), anyMap());
    assertEquals(1, result.getNumberOfAttachedRepositories());
  }

  @Test
  public void recordsErrorWhenBuildTypeMissing() throws Exception {
    when(projectManager.findBuildTypeByExternalId(BUILD_TYPE_ID)).thenReturn(null);
    when(projectManager.findBuildTypeById(BUILD_TYPE_ID)).thenReturn(null);

    VcsSyncResult result = publisher.applyVcsToBuild(
        mirror(), gitInfo("https://github.com/org/repo.git", "abc123", "refs/remotes/origin/main"));

    assertTrue(result.hasErrors());
  }

  @Test
  public void leavesCreatedRootAndRecordsErrorWhenAttachFails() throws Exception {
    SVcsRoot created = gitRoot("https://github.com/org/repo.git");
    when(project.createVcsRoot(eq("jetbrains.git"), anyString(), anyMap())).thenReturn(created);
    when(buildType.getVcsRootInstanceEntryForParent(created)).thenReturn(null);
    when(buildType.addVcsRoot(created)).thenThrow(new ReadOnlyEntityException("read only"));

    VcsSyncResult result = publisher.applyVcsToBuild(
        mirror(), gitInfo("https://github.com/org/repo.git", "abc123", "refs/remotes/origin/main"));

    verify(created).schedulePersisting("Jenkins Bridge: persist newly created Jenkins VCS root");
    assertTrue(result.hasErrors());
    assertEquals(0, result.getNumberOfAttachedRepositories());
  }

  @Test
  public void nullPointerDuringVcsAttachmentPropagates() throws Exception {
    SVcsRoot created = gitRoot("https://github.com/org/repo.git");
    when(project.createVcsRoot(eq("jetbrains.git"), anyString(), anyMap())).thenReturn(created);
    when(buildType.getVcsRootInstanceEntryForParent(created)).thenReturn(null);
    when(buildType.addVcsRoot(created)).thenThrow(new NullPointerException("VCS bridge bug"));

    try {
      publisher.applyVcsToBuild(
          mirror(), gitInfo("https://github.com/org/repo.git", "abc123", "refs/remotes/origin/main"));
      fail("Expected NullPointerException");
    } catch (NullPointerException expected) {
      assertEquals("VCS bridge bug", expected.getMessage());
    }
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
