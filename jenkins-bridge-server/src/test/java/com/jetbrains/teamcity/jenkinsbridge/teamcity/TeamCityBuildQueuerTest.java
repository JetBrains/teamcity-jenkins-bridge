package com.jetbrains.teamcity.jenkinsbridge.teamcity;

import com.google.gson.JsonParser;
import com.jetbrains.teamcity.jenkinsbridge.feature.BridgeBuildFeatureConstants;
import com.jetbrains.teamcity.jenkinsbridge.jenkins.JenkinsClient;
import com.jetbrains.teamcity.jenkinsbridge.model.JenkinsPullRequestInfo;
import com.jetbrains.teamcity.jenkinsbridge.model.JenkinsVcsInfo;
import jetbrains.buildServer.parameters.ParametersProvider;
import jetbrains.buildServer.serverSide.*;
import org.junit.Before;
import org.junit.Test;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class TeamCityBuildQueuerTest {
  private static final String BUILD_TYPE_ID = "MyProject_Mirror";

  private final ProjectManager projectManager = mock(ProjectManager.class);
  private final BuildCustomizerFactory customizerFactory = mock(BuildCustomizerFactory.class);
  private final JenkinsClient jenkinsClient = mock(JenkinsClient.class);
  private final SBuildType buildType = mock(SBuildType.class);
  private final BuildCustomizerEx customizer = mock(BuildCustomizerEx.class);

  private final BuildPromotionEx promotion = mock(BuildPromotionEx.class);
  private final TeamCityBuildQueuer queuer = new TeamCityBuildQueuer(projectManager, customizerFactory, jenkinsClient);

  @Before
  public void setUp() {
    when(projectManager.findBuildTypeByExternalId(BUILD_TYPE_ID)).thenReturn(buildType);
    ParametersProvider parametersProvider = mock(ParametersProvider.class);
    when(parametersProvider.getAll()).thenReturn(Collections.emptyMap());
    when(buildType.getParametersProvider()).thenReturn(parametersProvider);
    when(customizerFactory.createBuildCustomizer(eq(buildType), any())).thenReturn(customizer);
    when(jenkinsClient.getPullRequestInfo(anyString())).thenReturn(Optional.empty());

    when(promotion.getId()).thenReturn(55L);
    SQueuedBuild queued = mock(SQueuedBuild.class);
    when(queued.getBuildPromotion()).thenReturn(promotion);
    when(customizer.createPromotion()).thenReturn(promotion);
    when(promotion.addToQueue(anyString())).thenReturn(queued);
  }

  @Test
  public void queueAgentlessBuildPinsBranchFromJobNameForMultibranchJob() {
    withMultibranchFeature("team/my-pipeline/main");

    queuer.queueAgentlessBuild(BUILD_TYPE_ID, properties(), Collections.emptyMap(), null);

    verify(customizer).setDesiredBranchName("main", false);
  }

  @Test
  public void queueAgentlessBuildUsesSourceBranchAndPullRequestParametersForMultibranchJob() {
    withMultibranchFeature("team/my-pipeline/PR-1-merge");
    Map<String, String> properties = new LinkedHashMap<>();
    properties.put("jenkins.job", "team/my-pipeline/PR-1-merge");
    when(jenkinsClient.getPullRequestInfo("team/my-pipeline/PR-1-merge")).thenReturn(
        Optional.of(new JenkinsPullRequestInfo("1", "feature-branch", "master", "some-author", "Some title")));

    queuer.queueAgentlessBuild(BUILD_TYPE_ID, properties, Collections.emptyMap(), null);

    verify(customizer).setDesiredBranchName("feature-branch", false);
    verify(customizer).setParameters(argThat((Map<String, String> parameters) ->
        "1".equals(parameters.get(TeamCityBuildParameters.PULL_REQUEST_NUMBER))
            && "feature-branch".equals(parameters.get(TeamCityBuildParameters.PULL_REQUEST_SOURCE_BRANCH))
            && "master".equals(parameters.get(TeamCityBuildParameters.PULL_REQUEST_TARGET_BRANCH))
            && "some-author".equals(parameters.get(TeamCityBuildParameters.PULL_REQUEST_AUTHOR))
            && "Some title".equals(parameters.get(TeamCityBuildParameters.PULL_REQUEST_TITLE))));
  }

  @Test
  public void queueAgentlessBuildFallsBackToRawBranchNameWhenPullRequestInfoIsUnavailable() {
    withMultibranchFeature("team/my-pipeline/PR-1-merge");
    Map<String, String> properties = new LinkedHashMap<>();
    properties.put("jenkins.job", "team/my-pipeline/PR-1-merge");
    when(jenkinsClient.getPullRequestInfo("team/my-pipeline/PR-1-merge")).thenReturn(Optional.empty());

    queuer.queueAgentlessBuild(BUILD_TYPE_ID, properties, Collections.emptyMap(), null);

    verify(customizer).setDesiredBranchName("PR-1-merge", false);
  }

  @Test
  public void queueAgentlessBuildPinsFirstDiscoveredBranchForNormalJobWithVcsInfo() {
    when(buildType.getBuildFeaturesOfType(BridgeBuildFeatureConstants.TYPE))
        .thenReturn(Collections.emptyList());

    queuer.queueAgentlessBuild(BUILD_TYPE_ID, properties(), Collections.emptyMap(),
        gitInfo("https://github.com/org/repo.git", "abc123", "refs/remotes/origin/dev"));

    verify(customizer).setDesiredBranchName("dev", false);
  }

  @Test
  public void queueAgentlessBuildDoesNotPinBranchForNormalJobWithoutVcsInfo() {
    when(buildType.getBuildFeaturesOfType(BridgeBuildFeatureConstants.TYPE))
        .thenReturn(Collections.emptyList());

    long id = queuer.queueAgentlessBuild(BUILD_TYPE_ID, properties(),
        Collections.emptyMap(), null);

    assertEquals(55L, id);
    verify(customizer, never()).setDesiredBranchName(anyString(), org.mockito.ArgumentMatchers.anyBoolean());
  }

  private void withMultibranchFeature(String jenkinsJob) {
    Map<String, String> params = new LinkedHashMap<>();
    params.put(BridgeBuildFeatureConstants.PARAM_IN_MULTIBRANCH, "true");
    params.put(BridgeBuildFeatureConstants.PARAM_JENKINS_JOB, jenkinsJob);
    SBuildFeatureDescriptor descriptor = mock(SBuildFeatureDescriptor.class);
    when(descriptor.getParameters()).thenReturn(params);
    when(buildType.getBuildFeaturesOfType(BridgeBuildFeatureConstants.TYPE))
        .thenReturn(Collections.singletonList(descriptor));
  }

  private Map<String, String> properties() {
    Map<String, String> properties = new LinkedHashMap<>();
    properties.put("jenkins.job", "team/my-pipeline/main");
    return properties;
  }

  private JenkinsVcsInfo gitInfo(String url, String sha1, String branch) {
    String json = "{\"actions\":[{\"_class\":\"hudson.plugins.git.util.BuildData\","
        + "\"lastBuiltRevision\":{\"SHA1\":\"" + sha1 + "\",\"branch\":[{\"name\":\"" + branch + "\"}]},"
        + "\"remoteUrls\":[\"" + url + "\"]}]}";
    return JenkinsVcsInfo.fromJson(JsonParser.parseString(json).getAsJsonObject());
  }
}
