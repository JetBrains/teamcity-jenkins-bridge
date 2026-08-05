package com.jetbrains.teamcity.jenkinsbridge.connection;

/**
 * Parameter names and identifiers of the "Jenkins" project connection.
 *
 * <p>The token is stored under a {@code secure:} prefixed name so TeamCity encrypts it.
 */
public final class JenkinsConnectionConstants {
  /** Connection provider type persisted in the project feature. Do not change. */
  public static final String PROVIDER_TYPE = "JenkinsBridge";

  /** Name shown in the "Add Connection" dialog. */
  public static final String DISPLAY_NAME = "Jenkins";

  /** Jenkins server root URL, for example {@code http://localhost:8080}. */
  public static final String PARAM_URL = "jenkinsUrl";

  /** Jenkins username the API token belongs to. */
  public static final String PARAM_USER = "jenkinsUser";

  /** Jenkins API token. Encrypted thanks to the {@code secure:} prefix. */
  public static final String PARAM_TOKEN = "secure:jenkinsToken";

  /** Controller path backing the "Test connection" button of this connection type. */
  public static final String TEST_CONNECTION_PATH = "/admin/jenkinsBridgeTestConnection.html";

  /**
   * Build parameter recording which connection a mirrored build came from. Set when the mirror build
   * is queued, so later work on that build (artifact downloads in particular) knows which Jenkins
   * server to fetch from even if the build feature has been changed since.
   */
  public static final String BUILD_PARAM_CONNECTION_ID = "jenkins.connection.id";

  private JenkinsConnectionConstants() {
  }
}
