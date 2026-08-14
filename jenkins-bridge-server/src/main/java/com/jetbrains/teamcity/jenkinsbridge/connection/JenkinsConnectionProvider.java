package com.jetbrains.teamcity.jenkinsbridge.connection;

import com.jetbrains.teamcity.jenkinsbridge.http.BridgeHttpClient;
import com.jetbrains.teamcity.jenkinsbridge.http.BridgeHttpException;
import jetbrains.buildServer.serverSide.InvalidProperty;
import jetbrains.buildServer.serverSide.PropertiesProcessor;
import jetbrains.buildServer.serverSide.SProject;
import jetbrains.buildServer.serverSide.oauth.OAuthConnectionDescriptor;
import jetbrains.buildServer.serverSide.oauth.OAuthConstants;
import jetbrains.buildServer.serverSide.oauth.OAuthProvider;
import jetbrains.buildServer.serverSide.validation.TestConnectionResult;
import jetbrains.buildServer.web.openapi.PluginDescriptor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Registers the "Jenkins" connection under Project Settings > Integrations > Connections. The
 * connection holds the Jenkins server URL and the credentials the bridge authenticates with.
 */
public class JenkinsConnectionProvider extends OAuthProvider {
  /** Error id the connection dialog renders in its "Test Connection" popup. */
  static final String TEST_CONNECTION_FAILED = "testConnectionFailed";

  @NotNull private final PluginDescriptor myPluginDescriptor;
  @NotNull private final BridgeHttpClient myHttpClient;

  public JenkinsConnectionProvider(@NotNull PluginDescriptor pluginDescriptor,
                                   @NotNull BridgeHttpClient httpClient) {
    myPluginDescriptor = pluginDescriptor;
    myHttpClient = httpClient;
  }

  @NotNull
  @Override
  public String getType() {
    return JenkinsConnectionConstants.PROVIDER_TYPE;
  }

  @NotNull
  @Override
  public String getDisplayName() {
    return JenkinsConnectionConstants.DISPLAY_NAME;
  }

  @Nullable
  @Override
  public String getEditParametersUrl() {
    return myPluginDescriptor.getPluginResourcesPath("editJenkinsConnection.jsp");
  }

  /**
   * Declares the endpoint behind the standard "Test connection" button.
   */
  @Nullable
  @Override
  public Map<String, String> getDefaultProperties() {
    return Collections.singletonMap(
        OAuthConstants.TEST_CONNECTION_ENDPOINT_PARAM, JenkinsConnectionConstants.TEST_CONNECTION_PATH);
  }

  /**
   * Link to this plugin's "Jenkins Jobs Sync" project tab. The tab id is the plugin name, see
   * {@code JenkinsBridgeImportTab}.
   *
   * @param project project whose settings page to link to
   * @return a relative URL
   */
  @NotNull
  public String importJobsTabUrl(@NotNull SProject project) {
    return "/admin/editProject.html?projectId=" + project.getExternalId()
        + "&tab=" + myPluginDescriptor.getPluginName();
  }

  @NotNull
  @Override
  public String describeConnection(@NotNull OAuthConnectionDescriptor connection) {
    return describeConnection(connection.getParameters());
  }

  @NotNull
  @Override
  public String describeConnection(@NotNull Map<String, String> connectionProperties) {
    String url = connectionProperties.get(JenkinsConnectionConstants.PARAM_URL);
    return url == null || url.trim().isEmpty() ? getDisplayName() : url.trim();
  }

  @Nullable
  @Override
  public PropertiesProcessor getPropertiesProcessor() {
    return properties -> validate(properties == null ? Collections.emptyMap() : properties);
  }

  /**
   * Checks that the connection can actually reach Jenkins with the entered credentials.
   *
   * @param projectId  external id of the project the connection belongs to
   * @param parameters the parameters currently entered in the connection dialog
   * @return the validation errors, or an empty result when Jenkins provided a response
   */
  @NotNull
  @Override
  public TestConnectionResult testConnection(@NotNull String projectId, @NotNull Map<String, String> parameters) {
    TestConnectionResult result = new TestConnectionResult();
    Collection<InvalidProperty> invalidProperties = validate(parameters);
    if (!invalidProperties.isEmpty()) {
      result.addErrors(invalidProperties);
      return result;
    }

    JenkinsConnection connection = JenkinsConnection.fromParameters(parameters);
    try {
      myHttpClient.get(
          connection.getUrl() + "/api/json?tree=mode",
          connection.getUser(),
          connection.getToken(),
          "application/json");
    } catch (BridgeHttpException e) {
      result.addError(TEST_CONNECTION_FAILED, describeFailure(connection, e));
    }
    return result;
  }

  @NotNull
  private Collection<InvalidProperty> validate(@NotNull Map<String, String> properties) {
    List<InvalidProperty> errors = new ArrayList<>();
    if (isBlank(properties.get(OAuthConstants.DISPLAY_NAME_PARAM))) {
      errors.add(new InvalidProperty(OAuthConstants.DISPLAY_NAME_PARAM, "Display name must not be empty"));
    }

    String urlProblem = getServerURLProblemOrNull(properties.get(JenkinsConnectionConstants.PARAM_URL));
    if (urlProblem != null) {
      errors.add(new InvalidProperty(JenkinsConnectionConstants.PARAM_URL, urlProblem));
    }

    if (isBlank(properties.get(JenkinsConnectionConstants.PARAM_USER))) {
      errors.add(new InvalidProperty(JenkinsConnectionConstants.PARAM_USER, "Username must not be empty"));
    }
    if (isBlank(properties.get(JenkinsConnectionConstants.PARAM_TOKEN))) {
      errors.add(new InvalidProperty(JenkinsConnectionConstants.PARAM_TOKEN, "Token must not be empty"));
    }
    return errors;
  }

  @NotNull
  private static String describeFailure(@NotNull JenkinsConnection connection, @NotNull BridgeHttpException failure) {
    int status = failure.getStatusCode();
    if (status == 401 || status == 403) {
      return "Jenkins at " + connection.getUrl()
          + " rejected the credentials (HTTP " + status + "). Check the username and the API token.";
    }
    if (status > 0) {
      return "Jenkins at " + connection.getUrl() + " answered with HTTP " + status + ".";
    }
    return "Could not reach Jenkins at " + connection.getUrl() + ": " + failure.getMessage();
  }

  private static boolean isBlank(@Nullable String value) {
    return value == null || value.trim().isEmpty();
  }
}
