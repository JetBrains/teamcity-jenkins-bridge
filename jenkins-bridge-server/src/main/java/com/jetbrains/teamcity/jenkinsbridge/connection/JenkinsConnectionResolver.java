package com.jetbrains.teamcity.jenkinsbridge.connection;

import com.google.gson.Gson;
import com.jetbrains.teamcity.jenkinsbridge.feature.BridgeBuildFeatureConstants;
import jetbrains.buildServer.serverSide.SBuildFeatureDescriptor;
import jetbrains.buildServer.serverSide.SBuildType;
import jetbrains.buildServer.serverSide.SProject;
import jetbrains.buildServer.serverSide.connections.ConnectionDescriptor;
import jetbrains.buildServer.serverSide.connections.ProjectConnectionsManager;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Looks up the "Jenkins" connections a project can use and turns them into {@link JenkinsConnection}.
 */
public class JenkinsConnectionResolver {
  @NotNull private final ProjectConnectionsManager myConnectionsManager;

  public JenkinsConnectionResolver(@NotNull ProjectConnectionsManager connectionsManager) {
    myConnectionsManager = connectionsManager;
  }

  /**
   * @param project project to look in, including inherited connections
   * @return the Jenkins connections available to the project, in display order
   */
  @NotNull
  public List<ConnectionDescriptor> availableConnections(@NotNull SProject project) {
    return myConnectionsManager.getAvailableConnectionsOfType(project, JenkinsConnectionConstants.PROVIDER_TYPE);
  }

  /**
   * Mapping of connection ids to Jenkins server URLs.
   *
   * @param project project to look into
   * @return the URL of every Jenkins connection available to the project
   */
  @NotNull
  public Map<String, String> connectionUrls(@NotNull SProject project) {
    Map<String, String> urls = new LinkedHashMap<>();
    for (ConnectionDescriptor connection : availableConnections(project)) {
      urls.put(connection.getId(), JenkinsConnection.fromParameters(connection.getParameters()).getUrl());
    }
    return urls;
  }

  /**
   * @param project project to look into
   * @return {@link #connectionUrls(SProject)} as a JSON object, safe to embed in a script block
   */
  @NotNull
  public String connectionUrlsAsJson(@NotNull SProject project) {
    return new Gson().toJson(connectionUrls(project));
  }

  /**
   * @param project      project the connection is available to
   * @param connectionId id of the connection, as stored in the build feature
   * @return the descriptor, or null when the connection no longer exists
   */
  @Nullable
  public ConnectionDescriptor findConnection(@NotNull SProject project, @Nullable String connectionId) {
    if (connectionId == null || connectionId.trim().isEmpty()) {
      return null;
    }
    ConnectionDescriptor connection = myConnectionsManager.findConnectionById(project, connectionId.trim());
    if (connection == null
        || !JenkinsConnectionConstants.PROVIDER_TYPE.equals(connection.getConnectionProvider().getType())) {
      return null;
    }
    return connection;
  }

  /**
   * @param project      project the connection is available to
   * @param connectionId id of the connection, as stored in the build feature
   * @return the resolved connection
   * @throws IllegalStateException when the connection is missing or is not a Jenkins connection
   */
  @NotNull
  public JenkinsConnection resolve(@NotNull SProject project, @Nullable String connectionId) {
    ConnectionDescriptor connection = findConnection(project, connectionId);
    if (connection == null) {
      throw new IllegalStateException("Jenkins connection " + connectionId
          + " was not found in project " + project.getExternalId()
          + ". Select a Jenkins connection in the Jenkins Bridge build feature.");
    }
    return JenkinsConnection.fromParameters(connection.getParameters());
  }

  /**
   * @param buildType build configuration carrying the Jenkins Bridge build feature
   * @return the connection id stored in the feature, or null when the feature is absent or unset
   */
  @Nullable
  public String connectionIdOf(@NotNull SBuildType buildType) {
    for (SBuildFeatureDescriptor descriptor
        : buildType.getBuildFeaturesOfType(BridgeBuildFeatureConstants.TYPE)) {
      String connectionId = descriptor.getParameters().get(BridgeBuildFeatureConstants.PARAM_CONNECTION_ID);
      if (connectionId != null && !connectionId.trim().isEmpty()) {
        return connectionId.trim();
      }
    }
    return null;
  }

  /**
   * @param buildType build configuration carrying the Jenkins Bridge build feature
   * @return the connection the configuration mirrors from
   * @throws IllegalStateException when no usable connection is selected
   */
  @NotNull
  public JenkinsConnection resolveForBuildType(@NotNull SBuildType buildType) {
    String connectionId = connectionIdOf(buildType);
    if (connectionId == null) {
      throw new IllegalStateException("Build configuration " + buildType.getExternalId()
          + " has no Jenkins connection selected in its Jenkins Bridge build feature");
    }
    return resolve(buildType.getProject(), connectionId);
  }

  /** Link to the project page where Jenkins connections are managed. */
  @NotNull
  public String connectionsPageUrl(@NotNull SProject project) {
    return "/admin/editProject.html?projectId=" + project.getExternalId()
        + "&tab=oauthConnections#addDialog=" + JenkinsConnectionConstants.PROVIDER_TYPE;
  }
}
