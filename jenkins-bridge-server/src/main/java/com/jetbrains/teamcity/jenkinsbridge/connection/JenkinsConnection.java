package com.jetbrains.teamcity.jenkinsbridge.connection;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;

import static com.jetbrains.teamcity.jenkinsbridge.util.Utilities.nullToEmpty;

/**
 * One Jenkins server the bridge talks to, as configured by a "Jenkins" project connection.
 */
public final class JenkinsConnection {
  @NotNull private final String myUrl;
  @NotNull private final String myUser;
  @NotNull private final String myToken;

  public JenkinsConnection(@Nullable String url, @Nullable String user, @Nullable String token) {
    myUrl = trimTrailingSlash(url);
    myUser = nullToEmpty(user).trim();
    myToken = nullToEmpty(token);
  }

  /**
   * Reads a connection from the parameters of a connection descriptor.
   *
   * @param parameters connection feature parameters, including the decrypted secure token
   * @return the connection described by the parameters
   */
  @NotNull
  public static JenkinsConnection fromParameters(@NotNull Map<String, String> parameters) {
    return new JenkinsConnection(
        parameters.get(JenkinsConnectionConstants.PARAM_URL),
        parameters.get(JenkinsConnectionConstants.PARAM_USER),
        parameters.get(JenkinsConnectionConstants.PARAM_TOKEN));
  }

  /** Jenkins server root URL without a trailing slash. */
  @NotNull
  public String getUrl() {
    return myUrl;
  }

  @NotNull
  public String getUser() {
    return myUser;
  }

  @NotNull
  public String getToken() {
    return myToken;
  }

  @NotNull
  private static String trimTrailingSlash(@Nullable String value) {
    String result = nullToEmpty(value).trim();
    while (result.endsWith("/")) {
      result = result.substring(0, result.length() - 1);
    }
    return result;
  }
}
