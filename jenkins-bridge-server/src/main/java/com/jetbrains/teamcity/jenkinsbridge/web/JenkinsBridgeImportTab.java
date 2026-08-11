package com.jetbrains.teamcity.jenkinsbridge.web;

import com.jetbrains.teamcity.jenkinsbridge.connection.JenkinsConnectionResolver;
import jetbrains.buildServer.controllers.admin.projects.EditProjectTab;
import jetbrains.buildServer.serverSide.SProject;
import jetbrains.buildServer.serverSide.auth.Permission;
import jetbrains.buildServer.users.SUser;
import jetbrains.buildServer.web.openapi.PagePlaces;
import jetbrains.buildServer.web.openapi.PluginDescriptor;
import jetbrains.buildServer.web.util.SessionUser;

import javax.servlet.http.HttpServletRequest;
import java.util.Map;

/**
 * "Jenkins Jobs Sync" tab under a project's settings. The tab's project is the
 * synchronization target; the
 * JSP makes AJAX calls to {@link JenkinsBridgeImportController}. Visible only to users who can edit
 * the project.
 */
public class JenkinsBridgeImportTab extends EditProjectTab {
  private final JenkinsConnectionResolver connectionResolver;

  public JenkinsBridgeImportTab(PagePlaces pagePlaces, PluginDescriptor pluginDescriptor,
                                JenkinsConnectionResolver connectionResolver) {
    super(pagePlaces,
        pluginDescriptor.getPluginName(),
        pluginDescriptor.getPluginResourcesPath("jenkinsBridgeImport.jsp"),
        "Jenkins Jobs Sync");
    register();
    this.connectionResolver = connectionResolver;
  }

  @Override
  public boolean isAvailable(HttpServletRequest request) {
    SProject project = getProject(request);
    SUser user = SessionUser.getUser(request);
    return project != null
        && user != null
        && user.isPermissionGrantedForProject(project.getProjectId(), Permission.EDIT_PROJECT);
  }

  @Override
  public void fillModel(Map<String, Object> model, HttpServletRequest request) {
    super.fillModel(model, request);
    // Prefix the servlet context path so the AJAX URL is correct when TeamCity runs under a context
    // (e.g. /bs) rather than at the server root.
    model.put("controllerUrl", request.getContextPath() + JenkinsBridgeImportController.PATH);
    SProject project = getProject(request);
    model.put("project", project);
    model.put("projectExternalId", project == null ? "" : project.getExternalId());
    model.put("jenkinsConnections", connectionResolver);
    if (project != null) {
      model.put("connectionsPageUrl", connectionResolver.connectionsPageUrl(project));
    }
  }
}
