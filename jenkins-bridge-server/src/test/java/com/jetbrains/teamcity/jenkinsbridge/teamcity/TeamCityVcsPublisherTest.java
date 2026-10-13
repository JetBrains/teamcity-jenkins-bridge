package com.jetbrains.teamcity.jenkinsbridge.teamcity;

import com.google.gson.JsonParser;
import com.jetbrains.teamcity.jenkinsbridge.jenkins.JenkinsClient;
import com.jetbrains.teamcity.jenkinsbridge.jenkins.JenkinsClientFactory;
import com.jetbrains.teamcity.jenkinsbridge.model.JenkinsVcsInfo;
import com.jetbrains.teamcity.jenkinsbridge.persistence.BuildMirror;
import com.jetbrains.teamcity.jenkinsbridge.vcs.VcsRefType;
import com.jetbrains.teamcity.jenkinsbridge.vcs.VcsBuildCustomization;
import com.jetbrains.teamcity.jenkinsbridge.vcs.VcsSyncResult;
import jetbrains.buildServer.ExtensionHolder;
import jetbrains.buildServer.serverSide.BuildPromotionEx;
import jetbrains.buildServer.serverSide.PersistTask;
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
import jetbrains.buildServer.vcs.spec.BranchSpecsImpl;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import java.util.Collections;
import java.util.List;
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
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class TeamCityVcsPublisherTest {
  private static final String BUILD_TYPE_ID = "MyProject_Mirror";
  private static final long PROMOTION_ID = 55L;

  private final ProjectManager projectManager = mock(ProjectManager.class);
  private final SProject project = mock(SProject.class);
  private final SBuildType buildType = mock(SBuildType.class);
  private final PersistTask persistTask = mock(PersistTask.class);
  private final TeamCityRunningBuildLocator buildLocator = mock(TeamCityRunningBuildLocator.class);
  private final BuildChainChangesCollector changesCollector = mock(BuildChainChangesCollector.class);
  private final BuildPromotionEx promotion = mock(BuildPromotionEx.class);
  private final JenkinsClient jenkinsClient = mock(JenkinsClient.class);
  private final JenkinsClientFactory jenkinsClientFactory = mock(JenkinsClientFactory.class);

  private final TeamCityBranchResolver branchResolver = mock(TeamCityBranchResolver.class);

  private final TeamCityVcsPublisher publisher =
      new TeamCityVcsPublisher(projectManager, buildLocator, changesCollector, jenkinsClientFactory, null,
          branchResolver);

  @Before
  public void setUp() throws Exception {
    when(branchResolver.resolveRoot(any(), anyString(), anyString())).thenReturn(
        new TeamCityBranchResolver.Decision(TeamCityBranchResolver.Outcome.NAMED, "main"));
    when(projectManager.findBuildTypeByExternalId(BUILD_TYPE_ID)).thenReturn(buildType);
    when(buildType.getProject()).thenReturn(project);
    when(buildType.schedulePersisting(anyString())).thenReturn(persistTask);
    when(jenkinsClientFactory.forBuildType(buildType)).thenReturn(jenkinsClient);
    when(project.getAvailableVcsRoots()).thenReturn(Collections.emptyList());
    when(buildLocator.findPromotion(PROMOTION_ID)).thenReturn(promotion);
    when(buildType.getParameterValue(com.jetbrains.teamcity.jenkinsbridge.feature.BridgeBuildFeatureConstants.INTERNAL_MULTIBRANCH_PARAM)).thenReturn("true");
    when(jenkinsClientFactory.getMultibranchScmSnapshot(buildType)).thenReturn(java.util.Optional.of(new com.jetbrains.teamcity.jenkinsbridge.model.JenkinsMultibranchScmSnapshot(
        java.util.List.of(primary("https://github.com/org/repo.git", "main"), primary("https://github.com/org/first.git", "main"),
            primary("https://github.com/org/second.git", "master")))));
    when(jenkinsClient.getBranchScmInfo(any())).thenReturn(java.util.Optional.empty());
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
    when(project.getAvailableVcsRoots()).thenReturn(Collections.singletonList(existing));
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
    when(project.getAvailableVcsRoots()).thenReturn(Collections.singletonList(existing));
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
    when(jenkinsClient.getBranchScmInfo(any())).thenReturn(java.util.Optional.of(
        new com.jetbrains.teamcity.jenkinsbridge.model.JenkinsScmHeadInfo(VcsRefType.TAGS, "v1.0", "", "https://github.com/org/repo.git")));
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
    assertEquals("refs/heads/main", paramsCaptor.getValue().get("branch"));
    assertFalse(result.hasErrors());
  }

  @Test
  public void prepareVcsCreatesTagRootAndReturnsPinnedTagRevisionBeforeQueueing() throws Exception {
    when(jenkinsClient.getBranchScmInfo(any())).thenReturn(java.util.Optional.of(
        new com.jetbrains.teamcity.jenkinsbridge.model.JenkinsScmHeadInfo(VcsRefType.TAGS, "v1.0", "", "https://github.com/org/repo.git")));
    SVcsRoot created = gitRoot("https://github.com/org/repo.git");
    when(project.createVcsRoot(eq("jetbrains.git"), anyString(), anyMap())).thenReturn(created);
    VcsRootInstanceEntry entry = entry(15L);
    when(buildType.getVcsRootInstanceEntryForParent(created)).thenReturn(null, entry);

    VcsBuildCustomization customization = publisher.prepareVcs(
        mirror(), gitInfo("https://github.com/org/repo.git", "abc123", "refs/tags/v1.0"));

    assertFalse(customization.result().hasErrors());
    assertEquals("abc123", customization.upperLimitRevisions().get(15L).getVersion());
    assertEquals("refs/tags/v1.0", customization.upperLimitRevisions().get(15L).getVcsBranch());

  }

  @Test
  public void applyVcsToBuildDoesNotReuseHeadsRootForTagBuildWithSameUrl() throws Exception {
    when(jenkinsClient.getBranchScmInfo(any())).thenReturn(java.util.Optional.of(
        new com.jetbrains.teamcity.jenkinsbridge.model.JenkinsScmHeadInfo(VcsRefType.TAGS, "v1.0", "", "https://github.com/org/repo.git")));
    SVcsRoot headsRoot = gitRoot("https://github.com/org/repo.git");
    when(headsRoot.getName()).thenReturn("https://github.com/org/repo.git");
    when(project.getAvailableVcsRoots()).thenReturn(Collections.singletonList(headsRoot));
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
  public void nullPointerDuringVcsAttachmentIsNonfatal() throws Exception {
    SVcsRoot created = gitRoot("https://github.com/org/repo.git");
    when(project.createVcsRoot(eq("jetbrains.git"), anyString(), anyMap())).thenReturn(created);
    when(buildType.getVcsRootInstanceEntryForParent(created)).thenReturn(null);
    when(buildType.addVcsRoot(created)).thenThrow(new NullPointerException("VCS bridge bug"));

    VcsSyncResult result = publisher.applyVcsToBuild(
        mirror(), gitInfo("https://github.com/org/repo.git", "abc123", "refs/remotes/origin/main"));
    assertTrue(result.hasErrors());
    verify(promotion, never()).resetBuildRevisions();
  }

  @Test
  public void usesDiscoveredMainDefaultWhilePinningDebCommitAndRef() throws Exception {
    SVcsRoot root = gitRoot("https://github.com/org/repo.git");
    when(project.createVcsRoot(eq("jetbrains.git"), anyString(), anyMap())).thenReturn(root);
    VcsRootInstanceEntry debEntry = entry(31L);
    when(buildType.getVcsRootInstanceEntryForParent(root)).thenReturn(null, debEntry);
    VcsBuildCustomization prepared = publisher.prepareVcs(mirror(),
        gitInfo("https://github.com/org/repo.git", "deb-sha", "refs/heads/deb"));
    verify(project).createVcsRoot(eq("jetbrains.git"), anyString(), org.mockito.ArgumentMatchers.argThat(
        (Map<String, String> properties) -> "refs/heads/main".equals(properties.get("branch"))));
    assertEquals("deb-sha", prepared.upperLimitRevisions().get(31L).getVersion());
    assertEquals("refs/heads/deb", prepared.upperLimitRevisions().get(31L).getVcsBranch());
  }

  @Test
  public void unknownDefaultDefersRootCreationAndPreservesExistingRevisionPins() throws Exception {
    when(jenkinsClientFactory.getMultibranchScmSnapshot(buildType)).thenReturn(java.util.Optional.empty());
    VcsSyncResult result = publisher.applyVcsToBuild(mirror(),
        gitInfo("https://github.com/org/repo.git", "abc123", "refs/heads/deb"));
    assertTrue(result.hasErrors());
    verify(project, never()).createVcsRoot(anyString(), anyString(), anyMap());
    verify(promotion, never()).resetBuildRevisions();
    verify(promotion, never()).setProvidedUpperLimitRevisions(anyMap());
  }

  @Test
  public void unavailableRootPropertyIsNonfatalAndDoesNotResetPins() throws Exception {
    SVcsRoot root = gitRoot("https://github.com/org/repo.git");
    when(project.getAvailableVcsRoots()).thenReturn(Collections.singletonList(root));
    when(root.getProperty("url")).thenThrow(new IllegalStateException("root unavailable"));
    VcsSyncResult result = publisher.applyVcsToBuild(mirror(),
        gitInfo("https://github.com/org/repo.git", "abc123", "refs/heads/deb"));
    assertTrue(result.hasErrors());
    verify(promotion, never()).resetBuildRevisions();
  }

  @Test
  public void tagPrimaryDoesNotRetypeAdditionalCheckoutAndPreviousPinsArePreserved() throws Exception {
    when(jenkinsClient.getBranchScmInfo(any())).thenReturn(java.util.Optional.of(
        new com.jetbrains.teamcity.jenkinsbridge.model.JenkinsScmHeadInfo(VcsRefType.TAGS, "v1.0", "", "https://github.com/org/first.git")));
    SVcsRoot first = gitRoot("https://github.com/org/first.git");
    SVcsRoot second = gitRoot("https://github.com/org/second.git");
    when(project.createVcsRoot(eq("jetbrains.git"), anyString(), anyMap())).thenReturn(first, second);
    VcsRootInstanceEntry firstEntry = entry(21L);
    when(buildType.getVcsRootInstanceEntryForParent(first)).thenReturn(null, firstEntry);
    VcsRootInstanceEntry secondEntry = entry(22L);
    when(buildType.getVcsRootInstanceEntryForParent(second)).thenReturn(null, secondEntry);
    RepositoryVersion previous = new RepositoryVersion("previous", "previous", "refs/heads/other");
    when(promotion.getProvidedUpperLimitRevisions()).thenReturn(Map.of(99L, previous));
    VcsSyncResult result = publisher.applyVcsToBuild(mirror(), new JenkinsVcsInfo(java.util.List.of(
        new com.jetbrains.teamcity.jenkinsbridge.model.JenkinsVcsRepository("hudson.plugins.git.util.BuildData", "https://github.com/org/first.git", "tag-sha", "origin/v1.0"),
        new com.jetbrains.teamcity.jenkinsbridge.model.JenkinsVcsRepository("hudson.plugins.git.util.BuildData", "https://github.com/org/second.git", "second-sha", "origin/master"))));
    assertFalse(result.hasErrors());
    ArgumentCaptor<Map<Long, RepositoryVersion>> pins = ArgumentCaptor.captor();
    verify(promotion).setProvidedUpperLimitRevisions(pins.capture());
    assertEquals("refs/tags/v1.0", pins.getValue().get(21L).getVcsBranch());
    assertEquals("refs/heads/master", pins.getValue().get(22L).getVcsBranch());
    assertEquals(previous, pins.getValue().get(99L));
  }

  @Test
  public void failedRepositoryRootDoesNotDiscardSuccessfulRepository() throws Exception {
    SVcsRoot broken = gitRoot("https://github.com/org/first.git");
    when(broken.getProperty("url")).thenThrow(new IllegalStateException("broken root"));
    SVcsRoot good = gitRoot("https://github.com/org/second.git");
    when(project.getAvailableVcsRoots()).thenReturn(java.util.List.of(broken, good));
    VcsRootInstanceEntry goodEntry = entry(22L);
    when(buildType.getVcsRootInstanceEntryForParent(good)).thenReturn(goodEntry);
    when(project.createVcsRoot(anyString(), anyString(), anyMap())).thenThrow(new IllegalStateException("cannot create root"));
    VcsBuildCustomization prepared = publisher.prepareVcs(mirror(), new JenkinsVcsInfo(java.util.List.of(
        new com.jetbrains.teamcity.jenkinsbridge.model.JenkinsVcsRepository("hudson.plugins.git.util.BuildData", "https://github.com/org/first.git", "first-sha", "refs/heads/main"),
        new com.jetbrains.teamcity.jenkinsbridge.model.JenkinsVcsRepository("hudson.plugins.git.util.BuildData", "https://github.com/org/second.git", "second-sha", "refs/heads/master"))));
    assertTrue(prepared.result().hasErrors());
    assertEquals(1, prepared.upperLimitRevisions().size());
    assertEquals("second-sha", prepared.upperLimitRevisions().get(22L).getVersion());
  }

  @Test
  public void skipsAttachedRootExcludingRefAndPinsLaterCompatibleRoot() throws Exception {
    SVcsRoot excluded = debExcludedRoot();
    SVcsRoot compatible = gitRoot("https://github.com/org/repo.git");
    when(buildType.getVcsRoots()).thenReturn(List.of(excluded, compatible));
    doReturn(entry(41L)).when(buildType).getVcsRootInstanceEntryForParent(excluded);
    doReturn(entry(42L)).when(buildType).getVcsRootInstanceEntryForParent(compatible);
    TeamCityBranchResolver resolver = realBranchResolver();
    assertEquals(new TeamCityBranchResolver.Decision(TeamCityBranchResolver.Outcome.NAMED, "deb"),
        resolver.resolve(buildType, "refs/heads/deb", "https://github.com/org/repo.git"));

    assertExactPin(prepareWithResolver(resolver, "refs/heads/deb"), 42L, "refs/heads/deb");
    verify(project, never()).createVcsRoot(anyString(), anyString(), anyMap());
    verify(buildType, never()).addVcsRoot(any(SVcsRoot.class));
  }

  @Test
  public void attachesCompatibleProjectRootWhenAttachedRootExcludesRef() throws Exception {
    SVcsRoot excluded = debExcludedRoot();
    SVcsRoot compatible = gitRoot("git@github.com:org/repo.git");
    when(buildType.getVcsRoots()).thenReturn(List.of(excluded));
    when(project.getAvailableVcsRoots()).thenReturn(List.of(excluded, compatible));
    doReturn(entry(41L)).when(buildType).getVcsRootInstanceEntryForParent(excluded);
    doReturn(null, entry(42L)).when(buildType).getVcsRootInstanceEntryForParent(compatible);

    assertExactPin(prepareWithResolver(realBranchResolver(), "refs/heads/deb"), 42L, "refs/heads/deb");
    verify(buildType).addVcsRoot(compatible);
    verify(buildType).setCheckoutRules(compatible, CheckoutRules.DEFAULT);
    verify(buildType).schedulePersisting("Jenkins Bridge: persist Jenkins VCS root attachment changes");
    verify(project, never()).createVcsRoot(anyString(), anyString(), anyMap());
  }

  @Test
  public void skipsIncompatibleProjectRootAndReusesLaterCompatibleRoot() throws Exception {
    SVcsRoot excluded = debExcludedRoot();
    SVcsRoot compatible = gitRoot("https://github.com/org/repo.git");
    when(project.getAvailableVcsRoots()).thenReturn(List.of(excluded, compatible));
    doReturn(entry(41L)).when(buildType).getVcsRootInstanceEntryForParent(excluded);
    doReturn(entry(42L)).when(buildType).getVcsRootInstanceEntryForParent(compatible);

    assertExactPin(prepareWithResolver(realBranchResolver(), "refs/heads/deb"), 42L, "refs/heads/deb");
    verify(project, never()).createVcsRoot(anyString(), anyString(), anyMap());
  }

  @Test
  public void prefersCompatibleAttachedRootOverCompatibleProjectRoot() throws Exception {
    SVcsRoot attached = gitRoot("https://github.com/org/repo.git");
    SVcsRoot available = gitRoot("https://github.com/org/repo.git");
    when(buildType.getVcsRoots()).thenReturn(List.of(attached));
    when(project.getAvailableVcsRoots()).thenReturn(List.of(available, attached));
    doReturn(entry(41L)).when(buildType).getVcsRootInstanceEntryForParent(attached);

    assertExactPin(prepareWithResolver(realBranchResolver(), "refs/heads/deb"), 41L, "refs/heads/deb");
    verify(buildType, never()).addVcsRoot(any(SVcsRoot.class));
  }

  @Test
  public void acceptsConfiguredDefaultEvenWhenBranchSpecDoesNotIncludeIt() throws Exception {
    SVcsRoot excluded = debExcludedRoot();
    SVcsRoot configuredDefault = gitRoot("https://github.com/org/repo.git");
    when(configuredDefault.getProperty("branch")).thenReturn("refs/heads/deb");
    when(configuredDefault.getProperty("teamcity:branchSpec")).thenReturn("+:refs/heads/release/*");
    when(buildType.getVcsRoots()).thenReturn(List.of(excluded, configuredDefault));
    doReturn(entry(41L)).when(buildType).getVcsRootInstanceEntryForParent(excluded);
    doReturn(entry(42L)).when(buildType).getVcsRootInstanceEntryForParent(configuredDefault);

    assertExactPin(prepareWithResolver(realBranchResolver(), "refs/heads/deb"), 42L, "refs/heads/deb");
  }

  @Test
  public void skipsTagRootWithoutTagReportingAndPinsCompatibleTagRoot() throws Exception {
    SVcsRoot disabled = gitRoot("https://github.com/org/repo.git");
    SVcsRoot enabled = gitRoot("https://github.com/org/repo.git");
    for (SVcsRoot root : List.of(disabled, enabled)) {
      when(root.getProperty("teamcity:branchSpec")).thenReturn("+:refs/tags/*");
    }
    when(disabled.getProperty("reportTagRevisions")).thenReturn("false");
    when(buildType.getVcsRoots()).thenReturn(List.of(disabled, enabled));
    doReturn(entry(41L)).when(buildType).getVcsRootInstanceEntryForParent(disabled);
    doReturn(entry(42L)).when(buildType).getVcsRootInstanceEntryForParent(enabled);

    assertExactPin(prepareWithResolver(realBranchResolver(), "refs/tags/v1.0"), 42L, "refs/tags/v1.0");
    verify(project, never()).createVcsRoot(anyString(), anyString(), anyMap());
  }

  @Test
  public void noCompatibleRootWarnsWithoutCreatingRootOrReplacingPreviousPins() throws Exception {
    SVcsRoot excluded = debExcludedRoot();
    when(buildType.getVcsRoots()).thenReturn(List.of(excluded));
    doReturn(entry(41L)).when(buildType).getVcsRootInstanceEntryForParent(excluded);
    RepositoryVersion previous = new RepositoryVersion("previous", "previous", "refs/heads/main");
    when(promotion.getProvidedUpperLimitRevisions()).thenReturn(Map.of(41L, previous));
    TeamCityVcsPublisher realPublisher = new TeamCityVcsPublisher(projectManager, buildLocator,
        changesCollector, jenkinsClientFactory, null, realBranchResolver());

    VcsSyncResult result = realPublisher.applyVcsToBuild(mirror(),
        gitInfo("https://github.com/org/repo.git", "jenkins-exact-sha", "refs/heads/deb"));

    assertEquals(List.of("No reliable TeamCity VCS mapping for refs/heads/deb"), result.getErrors());
    verify(project, never()).createVcsRoot(anyString(), anyString(), anyMap());
    verify(promotion, never()).resetBuildRevisions();
    verify(promotion, never()).setProvidedUpperLimitRevisions(anyMap());
  }

  @Test
  public void retainsExistingRootRepairWhenNoRootInitiallyMapsRef() throws Exception {
    SVcsRoot root = gitRoot("https://github.com/org/repo.git");
    when(root.getProperty("teamcity:branchSpec")).thenReturn("+:refs/tags/*");
    when(root.getProperty("reportTagRevisions")).thenReturn("false");
    when(buildType.getVcsRoots()).thenReturn(List.of(root));
    doReturn(entry(41L)).when(buildType).getVcsRootInstanceEntryForParent(root);
    TeamCityVcsRootRepair repair = mock(TeamCityVcsRootRepair.class);
    doAnswer(invocation -> {
      when(root.getProperty("reportTagRevisions")).thenReturn("true");
      return true;
    }).when(repair).repair(root, "github.com/org/repo", "refs/heads/main", true);
    TeamCityVcsPublisher realPublisher = new TeamCityVcsPublisher(projectManager, buildLocator,
        changesCollector, jenkinsClientFactory, repair, realBranchResolver());

    assertExactPin(realPublisher.prepareVcs(mirror(),
        gitInfo("https://github.com/org/repo.git", "jenkins-exact-sha", "refs/tags/v1.0")),
        41L, "refs/tags/v1.0");
    verify(repair).repair(root, "github.com/org/repo", "refs/heads/main", true);
    verify(project, never()).createVcsRoot(anyString(), anyString(), anyMap());
  }

  private SVcsRoot debExcludedRoot() {
    SVcsRoot root = gitRoot("https://github.com/org/repo.git");
    when(root.getProperty("teamcity:branchSpec")).thenReturn("+:refs/heads/*\n-:refs/heads/deb");
    return root;
  }

  private TeamCityBranchResolver realBranchResolver() {
    return new TeamCityBranchResolver(new BranchSpecsImpl(null, mock(ExtensionHolder.class)));
  }

  private VcsBuildCustomization prepareWithResolver(TeamCityBranchResolver resolver, String ref) throws Exception {
    return new TeamCityVcsPublisher(projectManager, buildLocator, changesCollector, jenkinsClientFactory,
        null, resolver).prepareVcs(mirror(), gitInfo("https://github.com/org/repo.git", "jenkins-exact-sha", ref));
  }

  private void assertExactPin(VcsBuildCustomization prepared, long rootId, String ref) {
    assertEquals(Collections.emptyList(), prepared.result().getErrors());
    assertEquals(1, prepared.upperLimitRevisions().size());
    assertTrue("Expected revision on VCS root instance " + rootId, prepared.upperLimitRevisions().containsKey(rootId));
    assertEquals("jenkins-exact-sha", prepared.upperLimitRevisions().get(rootId).getVersion());
    assertEquals(ref, prepared.upperLimitRevisions().get(rootId).getVcsBranch());
  }

  private com.jetbrains.teamcity.jenkinsbridge.model.JenkinsMultibranchScmSnapshot.Child primary(String url, String head) {
    return new com.jetbrains.teamcity.jenkinsbridge.model.JenkinsMultibranchScmSnapshot.Child("parent/" + head, head, VcsRefType.HEADS, url, true, false);
  }

  private SVcsRoot gitRoot(String url) {
    SVcsRoot root = mock(SVcsRoot.class);
    when(root.getVcsName()).thenReturn("jetbrains.git");
    when(root.getProperty("url")).thenReturn(url);
    when(root.getProperty("branch")).thenReturn("refs/heads/main");
    when(root.getProperty("reportTagRevisions")).thenReturn("true");
    when(root.getProperty("teamcity:branchSpec")).thenReturn("+:refs/heads/*");

    when(root.schedulePersisting(anyString())).thenReturn(persistTask);
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
