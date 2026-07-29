package com.jetbrains.teamcity.jenkinsbridge.web;

import com.jetbrains.teamcity.jenkinsbridge.feature.BridgeBuildFeatureConstants;
import com.jetbrains.teamcity.jenkinsbridge.teamcity.TeamCityBuildParameters;
import jetbrains.buildServer.controllers.BuildNotFoundException;
import jetbrains.buildServer.parameters.ParametersProvider;
import jetbrains.buildServer.serverSide.SBuild;
import jetbrains.buildServer.serverSide.SBuildType;
import jetbrains.buildServer.util.StringUtil;
import jetbrains.buildServer.web.openapi.PagePlaces;
import jetbrains.buildServer.web.openapi.PlaceId;
import jetbrains.buildServer.web.openapi.PluginDescriptor;
import jetbrains.buildServer.web.openapi.SimplePageExtension;
import jetbrains.buildServer.web.util.BuildLookupService;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.servlet.http.HttpServletRequest;
import java.util.Map;

/**
 * Renders the Pull Request Details section on the overview tab of a mirrored build.
 */
public class JenkinsPullRequestExtension extends SimplePageExtension {
  /**
   * Must differ from the name of the plugin's other {@link PlaceId#BUILD_RESULTS_FRAGMENT}
   * extension, because the overview tab requests a section by its extension name.
   */
  private static final String EXTENSION_NAME_SUFFIX = "-pullRequest";

  @NotNull
  private final BuildLookupService myBuildLookupService;

  public JenkinsPullRequestExtension(
      @NotNull PagePlaces pagePlaces,
      @NotNull PluginDescriptor pluginDescriptor,
      @NotNull BuildLookupService buildLookupService
  ) {
    super(pagePlaces);
    myBuildLookupService = buildLookupService;
    setPluginName(pluginDescriptor.getPluginName() + EXTENSION_NAME_SUFFIX);
    setPlaceId(PlaceId.BUILD_RESULTS_FRAGMENT);
    setIncludeUrl(pluginDescriptor.getPluginResourcesPath("jenkinsPullRequest.jsp"));
    register();
  }

  @Override
  public String getDisplayName() {
    return "Pull Request Details";
  }

  @Override
  public boolean isAvailable(@NotNull HttpServletRequest request) {
    SBuild build = findBuild(request);
    if (build == null) return false;
    SBuildType buildType = build.getBuildType();
    if (buildType == null) return false;
    if (buildType.getBuildFeaturesOfType(BridgeBuildFeatureConstants.TYPE).isEmpty()) return false;
    return !StringUtil.isEmpty(build.getParametersProvider().get(TeamCityBuildParameters.PULL_REQUEST_NUMBER));
  }

  @Override
  public void fillModel(@NotNull Map<String, Object> model, @NotNull HttpServletRequest request) {
    super.fillModel(model, request);
    SBuild build = findBuild(request);
    if (build == null) return;
    ParametersProvider parameters = build.getParametersProvider();
    model.put("prNumber", nonNull(parameters.get(TeamCityBuildParameters.PULL_REQUEST_NUMBER)));
    model.put("prTitle", nonNull(parameters.get(TeamCityBuildParameters.PULL_REQUEST_TITLE)));
    model.put("prAuthor", nonNull(parameters.get(TeamCityBuildParameters.PULL_REQUEST_AUTHOR)));
    model.put("prSourceBranch", nonNull(parameters.get(TeamCityBuildParameters.PULL_REQUEST_SOURCE_BRANCH)));
    model.put("prTargetBranch", nonNull(parameters.get(TeamCityBuildParameters.PULL_REQUEST_TARGET_BRANCH)));
    model.put("prUrl", webUrl(parameters.get(TeamCityBuildParameters.PULL_REQUEST_URL)));
  }

  public void dispose() {
    unregister();
  }

  /**
   * Parses a String URL ensuring it is not null and is HTTP. Returns an empty string otherwise.
   *
   * @param url The value of the pull request URL parameter.
   * @return The URL, or an empty string when it is absent or not a web link.
   */
  @NotNull
  private static String webUrl(@Nullable String url) {
    if (url == null) return "";
    return url.startsWith("http://") || url.startsWith("https://") ? url : "";
  }

  @NotNull
  private static String nonNull(@Nullable String value) {
    return value == null ? "" : value;
  }

  @Nullable
  private SBuild findBuild(@NotNull HttpServletRequest request) {
    try {
      return myBuildLookupService.findBuild(request);
    } catch (BuildNotFoundException e) {
      return null;
    }
  }
}
