package com.jetbrains.teamcity.jenkinsbridge.jenkins;

import com.jetbrains.teamcity.jenkinsbridge.connection.JenkinsConnection;
import com.jetbrains.teamcity.jenkinsbridge.connection.JenkinsConnectionConstants;
import com.jetbrains.teamcity.jenkinsbridge.connection.JenkinsConnectionResolver;
import com.jetbrains.teamcity.jenkinsbridge.http.BridgeHttpClient;
import com.jetbrains.teamcity.jenkinsbridge.xml.JaxbUnmarshaller;
import jetbrains.buildServer.serverSide.BuildPromotion;
import jetbrains.buildServer.serverSide.SBuildType;
import jetbrains.buildServer.serverSide.SProject;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Builds a {@link JenkinsClient} for a given Jenkins connection.
 */
public class JenkinsClientFactory {
  @NotNull private final BridgeHttpClient myHttpClient;
  @NotNull private final JaxbUnmarshaller myXmlUnmarshaller;
  @NotNull private final JenkinsConnectionResolver myConnectionResolver;

  public JenkinsClientFactory(@NotNull BridgeHttpClient httpClient,
                              @NotNull JaxbUnmarshaller xmlUnmarshaller,
                              @NotNull JenkinsConnectionResolver connectionResolver) {
    myHttpClient = httpClient;
    myXmlUnmarshaller = xmlUnmarshaller;
    myConnectionResolver = connectionResolver;
  }

  /**
   * @param connection Jenkins server to talk to
   * @return a client bound to that server
   */
  @NotNull
  public JenkinsClient forConnection(@NotNull JenkinsConnection connection) {
    return new JenkinsClient(connection, myHttpClient, myXmlUnmarshaller);
  }

  /**
   * @param project      project the connection is available to
   * @param connectionId id of the Jenkins connection
   * @return a client bound to that connection
   * @throws IllegalStateException when the connection is missing
   */
  @NotNull
  public JenkinsClient forConnectionId(@NotNull SProject project, @Nullable String connectionId) {
    return forConnection(myConnectionResolver.resolve(project, connectionId));
  }

  /**
   * @param buildType build configuration carrying the Jenkins Bridge build feature
   * @return a client bound to the connection the configuration mirrors from
   * @throws IllegalStateException when the configuration has no usable connection
   */
  @NotNull
  public JenkinsClient forBuildType(@NotNull SBuildType buildType) {
    return forConnection(myConnectionResolver.resolveForBuildType(buildType));
  }

  /**
   * Resolves the Jenkins server a mirrored build came from. Prefers the connection id recorded on
   * the build and falls back to the one currently selected in the build feature.
   *
   * @param promotion promotion of a mirrored build
   * @return a client bound to that connection
   * @throws IllegalStateException when no connection can be resolved
   */
  @NotNull
  public JenkinsClient forBuildPromotion(@NotNull BuildPromotion promotion) {
    SBuildType buildType = promotion.getBuildType();
    if (buildType == null) {
      throw new IllegalStateException("Build " + promotion.getId() + " has no build configuration");
    }
    String connectionId = promotion.getParameterValue(JenkinsConnectionConstants.BUILD_PARAM_CONNECTION_ID);
    if (connectionId == null || connectionId.trim().isEmpty()) {
      return forBuildType(buildType);
    }
    return forConnectionId(buildType.getProject(), connectionId);
  }
}
