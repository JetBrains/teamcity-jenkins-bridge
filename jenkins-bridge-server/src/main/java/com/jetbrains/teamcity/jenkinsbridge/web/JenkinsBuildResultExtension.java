package com.jetbrains.teamcity.jenkinsbridge.web;

import com.jetbrains.teamcity.jenkinsbridge.feature.BridgeBuildFeatureConstants;
import com.jetbrains.teamcity.jenkinsbridge.persistence.BuildMirror;
import com.jetbrains.teamcity.jenkinsbridge.persistence.BuildMirrorResolver;
import jetbrains.buildServer.controllers.BuildNotFoundException;
import jetbrains.buildServer.serverSide.SBuild;
import jetbrains.buildServer.serverSide.SBuildType;
import jetbrains.buildServer.util.StringUtil;
import jetbrains.buildServer.web.openapi.*;
import jetbrains.buildServer.web.util.BuildLookupService;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Map;

public class JenkinsBuildResultExtension extends SimplePageExtension {
  private static final String PARAM_JENKINS_BUILD_URL = "jenkins.build.url";

  private final BuildLookupService buildLookupService;
  private final BuildMirrorResolver mirrorResolver;

  public JenkinsBuildResultExtension(
      @NotNull PagePlaces pagePlaces,
      @NotNull PluginDescriptor pluginDescriptor,
      @NotNull BuildLookupService buildLookupService,
      @NotNull BuildMirrorResolver mirrorResolver
  ) {
    super(pagePlaces);
    this.buildLookupService = buildLookupService;
    this.mirrorResolver = mirrorResolver;
    setPluginName(pluginDescriptor.getPluginName());
    setPlaceId(PlaceId.BUILD_RESULTS_FRAGMENT);
    setIncludeUrl(pluginDescriptor.getPluginResourcesPath("jenkinsBuildResult.jsp"));
    register();
  }

  @Override
  public String getDisplayName() {
    return "View in Jenkins";
  }

  @Override
  public boolean isAvailable(@NotNull HttpServletRequest request) {
    SBuild build = findBuild(request);
    if (build == null) return false;
    SBuildType buildType = build.getBuildType();
    if (buildType == null) return false;
    if (buildType.getBuildFeaturesOfType(BridgeBuildFeatureConstants.TYPE).isEmpty()) return false;
    try {
      BuildMirror mirror = mirrorResolver.resolve(build);
      return mirror != null && !StringUtil.isEmpty(mirror.getJenkinsBuildUrl());
    } catch (IOException e) {
      return false;
    }
  }

  @Override
  public void fillModel(@NotNull Map<String, Object> model, @NotNull HttpServletRequest request) {
    super.fillModel(model, request);
    SBuild build = findBuild(request);
    if (build == null) return;
    String url = null;
    try {
      BuildMirror mirror = mirrorResolver.resolve(build);
      if (mirror != null) url = mirror.getJenkinsBuildUrl();
    } catch (IOException ignored) {
      // Result-page decorations must remain best effort.
    }
    if (url != null && (url.startsWith("http://") || url.startsWith("https://"))) {
      model.put("jenkinsUrl", url);
    }
  }

  public void dispose() {
    unregister();
  }

  @Nullable
  private SBuild findBuild(@NotNull HttpServletRequest request) {
    try {
      return this.buildLookupService.findBuild(request);
    } catch (BuildNotFoundException e) {
      return null;
    }
  }
}
