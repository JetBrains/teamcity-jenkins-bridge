package com.jetbrains.teamcity.jenkinsbridge.web;

import jetbrains.buildServer.web.openapi.PagePlaces;
import jetbrains.buildServer.web.openapi.PlaceId;
import jetbrains.buildServer.web.openapi.PluginDescriptor;
import jetbrains.buildServer.web.openapi.SimplePageExtension;
import org.jetbrains.annotations.NotNull;

import javax.servlet.http.HttpServletRequest;

/** Loads the isolated branch-selector adapter only on TeamCity's Run Custom Build page. */
public final class JenkinsMultibranchRunDialogExtension extends SimplePageExtension {
  public JenkinsMultibranchRunDialogExtension(@NotNull PagePlaces pagePlaces,
                                              @NotNull PluginDescriptor pluginDescriptor) {
    super(pagePlaces, PlaceId.ALL_PAGES_FOOTER_PLUGIN_CONTAINER,
        "jenkinsBridgeMultibranchRunDialog",
        pluginDescriptor.getPluginResourcesPath("jenkinsMultibranchRunDialog.jsp"));
    addJsFile(pluginDescriptor.getPluginResourcesPath("jenkinsMultibranchRunDialog.js"));
    register();
  }

  @Override
  public boolean isAvailable(@NotNull HttpServletRequest request) {
    return super.isAvailable(request) && request.getRequestURI().endsWith("/runCustomBuild.html");
  }
}
