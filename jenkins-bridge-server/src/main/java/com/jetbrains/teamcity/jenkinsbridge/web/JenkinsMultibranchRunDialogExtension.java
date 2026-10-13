package com.jetbrains.teamcity.jenkinsbridge.web;

import jetbrains.buildServer.web.openapi.PagePlaces;
import jetbrains.buildServer.web.openapi.PlaceId;
import jetbrains.buildServer.web.openapi.PluginDescriptor;
import jetbrains.buildServer.web.openapi.SimplePageExtension;
import org.jetbrains.annotations.NotNull;

import javax.servlet.http.HttpServletRequest;

/** Loads the adapter globally; it activates only when TeamCity renders Run Custom Build. */
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
    // Run Custom Build is rendered as a dialog inside other pages, so the page request URI
    // usually does not identify it. The client adapter already no-ops unless that dialog exists.
    return super.isAvailable(request);
  }

  public void dispose() {
    unregister();
  }
}
