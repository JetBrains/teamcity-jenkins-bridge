package com.jetbrains.teamcity.jenkinsbridge.jenkins;

/**
 * Indicates that Jenkins returned a response which the bridge could not interpret.
 *
 * <p>This is separate from {@code BridgeHttpException}: the HTTP request may have succeeded while
 * the response body was malformed or had an unexpected structure.</p>
 */
public class JenkinsDataException extends Exception {
  public JenkinsDataException(String message, Throwable cause) {
    super(message, cause);
  }
}
