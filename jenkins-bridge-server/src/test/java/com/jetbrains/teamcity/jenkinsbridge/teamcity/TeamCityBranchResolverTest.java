package com.jetbrains.teamcity.jenkinsbridge.teamcity;

import jetbrains.buildServer.ExtensionHolder;
import jetbrains.buildServer.serverSide.SBuildType;
import jetbrains.buildServer.vcs.SVcsRoot;
import jetbrains.buildServer.vcs.spec.BranchSpecsImpl;
import org.junit.Test;
import java.util.List;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class TeamCityBranchResolverTest {
  private static final String URL = "https://github.com/org/repo.git";
  private final SBuildType type = mock(SBuildType.class);
  private final TeamCityBranchResolver resolver = new TeamCityBranchResolver(new BranchSpecsImpl(null, mock(ExtensionHolder.class)));

  @Test public void configuredDefaultHeadCannotBeOverriddenByWildcardTagRoot() {
    doReturn(List.of(root(URL, "refs/heads/deb", "+:refs/heads/*", false),
        root(URL, "refs/heads/main", "+:refs/tags/*", true))).when(type).getVcsRoots();
    var decision = resolver.resolve(type, "refs/heads/deb", URL);
    assertEquals(TeamCityBranchResolver.Outcome.CONFIGURED_DEFAULT, decision.outcome());
    assertNull(decision.branchName());
  }
  @Test public void usesTeamCityLogicalCaptureNamesAndPreservesSlashes() {
    doReturn(List.of(root(URL, "refs/heads/main", "+:refs/heads/(feature/*)", false))).when(type).getVcsRoots();
    assertEquals("feature/login", resolver.resolve(type, "refs/heads/feature/login", URL).branchName());
  }
  @Test public void excludesAndUnrelatedRepositoriesDoNotSupplyMapping() {
    doReturn(List.of(root(URL, "refs/heads/main", "+:refs/heads/*\n-:refs/heads/private/*", false),
        root("https://github.com/another/repo.git", "refs/heads/main", "+:refs/heads/*", false))).when(type).getVcsRoots();
    assertEquals(TeamCityBranchResolver.Outcome.NO_MAPPING, resolver.resolve(type, "refs/heads/private/x", URL).outcome());
  }
  @Test public void tagsRequireNativeTagReporting() {
    doReturn(List.of(root(URL, "refs/heads/main", "+:refs/tags/*", false))).when(type).getVcsRoots();
    assertNull(resolver.resolve(type, "refs/tags/release/v1", URL).branchName());
    doReturn(List.of(root(URL, "refs/heads/main", "+:refs/tags/*", true))).when(type).getVcsRoots();
    assertEquals("release/v1", resolver.resolve(type, "refs/tags/release/v1", URL).branchName());
  }
  @Test public void inaccessibleRootDoesNotInventBranchOrThrow() {
    SVcsRoot root = root(URL, "refs/heads/main", "+:refs/heads/*", false);
    when(root.getProperty("branch")).thenThrow(new IllegalStateException("unavailable"));
    doReturn(List.of(root)).when(type).getVcsRoots();
    assertEquals(TeamCityBranchResolver.Outcome.NO_MAPPING, resolver.resolve(type, "refs/heads/deb", URL).outcome());
  }
  @Test public void conflictingLogicalNamesAreUnmapped() {
    doReturn(List.of(root(URL, "refs/heads/main", "+:refs/heads/(feature/*)", false),
        root(URL, "refs/heads/main", "+:refs/heads/feature/(*)", false))).when(type).getVcsRoots();
    assertEquals(TeamCityBranchResolver.Outcome.NO_MAPPING, resolver.resolve(type, "refs/heads/feature/login", URL).outcome());
  }
  @Test public void missingMandatoryDefaultMetadataLeavesBranchUnset() {
    SVcsRoot incomplete = root(URL, null, "+:refs/heads/*", false);
    doReturn(List.of(incomplete)).when(type).getVcsRoots();
    assertEquals(TeamCityBranchResolver.Outcome.NO_MAPPING, resolver.resolve(type, "refs/heads/deb", URL).outcome());
  }
  @Test public void shortConfiguredDefaultRefStaysImplicit() {
    SVcsRoot configured = root(URL, "main", "+:refs/heads/*", false);
    doReturn(List.of(configured)).when(type).getVcsRoots();
    assertEquals(TeamCityBranchResolver.Outcome.CONFIGURED_DEFAULT, resolver.resolve(type, "refs/heads/main", URL).outcome());
  }
  private SVcsRoot root(String url, String defaultRef, String spec, boolean tags) {
    SVcsRoot root = mock(SVcsRoot.class);
    when(root.getVcsName()).thenReturn("jetbrains.git");
    when(root.getProperty("url")).thenReturn(url);
    when(root.getProperty("branch")).thenReturn(defaultRef);
    when(root.getProperty("teamcity:branchSpec")).thenReturn(spec);
    when(root.getProperty("reportTagRevisions")).thenReturn(String.valueOf(tags));
    return root;
  }
}
