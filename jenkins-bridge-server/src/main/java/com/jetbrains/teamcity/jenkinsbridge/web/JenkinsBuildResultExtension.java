package com.jetbrains.teamcity.jenkinsbridge.web;

import com.jetbrains.teamcity.jenkinsbridge.persistence.BuildResultMetadata;
import com.jetbrains.teamcity.jenkinsbridge.persistence.BuildResultMetadataResolver;
import jetbrains.buildServer.controllers.BuildNotFoundException;
import jetbrains.buildServer.serverSide.SBuild;
import jetbrains.buildServer.util.StringUtil;
import jetbrains.buildServer.web.openapi.*;
import jetbrains.buildServer.web.util.BuildLookupService;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Map;

public class JenkinsBuildResultExtension extends SimplePageExtension {
  private final BuildLookupService buildLookupService;
  private final BuildResultMetadataResolver resultMetadataResolver;

  public JenkinsBuildResultExtension(
      @NotNull PagePlaces pagePlaces,
      @NotNull PluginDescriptor pluginDescriptor,
      @NotNull BuildLookupService buildLookupService,
      @NotNull BuildResultMetadataResolver resultMetadataResolver
  ) {
    super(pagePlaces);
    this.buildLookupService = buildLookupService;
    this.resultMetadataResolver = resultMetadataResolver;
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
    if (build == null) {
      return false;
    }
    try {
      BuildResultMetadata metadata = resultMetadataResolver.resolve(build);
      return metadata != null && !StringUtil.isEmpty(metadata.getJenkinsBuildUrl());
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
      BuildResultMetadata metadata = resultMetadataResolver.resolve(build);
      if (metadata != null) url = metadata.getJenkinsBuildUrl();
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
