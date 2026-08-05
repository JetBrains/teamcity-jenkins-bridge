package com.jetbrains.teamcity.jenkinsbridge.connection;

import com.jetbrains.teamcity.jenkinsbridge.http.BridgeHttpClient;
import com.jetbrains.teamcity.jenkinsbridge.http.BridgeHttpException;
import jetbrains.buildServer.serverSide.InvalidProperty;
import jetbrains.buildServer.serverSide.oauth.OAuthConstants;
import jetbrains.buildServer.serverSide.validation.TestConnectionResult;
import jetbrains.buildServer.web.openapi.PluginDescriptor;
import org.junit.Test;

import java.util.*;

import static org.junit.Assert.*;
import static org.mockito.Mockito.mock;

public class JenkinsConnectionProviderTest {
  private final StubHttpClient httpClient = new StubHttpClient();
  private final JenkinsConnectionProvider provider =
      new JenkinsConnectionProvider(mock(PluginDescriptor.class), httpClient);

  @Test
  public void getPropertiesProcessorAcceptsACompleteConnection() {
    assertTrue(process(validParameters()).isEmpty());
  }

  @Test
  public void getPropertiesProcessorRejectsEveryMissingField() {
    Collection<InvalidProperty> errors = process(new HashMap<>());

    assertEquals(
        Set.of(OAuthConstants.DISPLAY_NAME_PARAM,
            JenkinsConnectionConstants.PARAM_URL,
            JenkinsConnectionConstants.PARAM_USER,
            JenkinsConnectionConstants.PARAM_TOKEN),
        propertyNames(errors));
  }

  @Test
  public void getPropertiesProcessorRejectsANonHttpUrl() {
    Map<String, String> parameters = validParameters();
    parameters.put(JenkinsConnectionConstants.PARAM_URL, "ftp://jenkins");

    assertEquals(Set.of(JenkinsConnectionConstants.PARAM_URL), propertyNames(process(parameters)));
  }

  @Test
  public void getPropertiesProcessorRejectsABlankToken() {
    Map<String, String> parameters = validParameters();
    parameters.put(JenkinsConnectionConstants.PARAM_TOKEN, "   ");

    assertEquals(Set.of(JenkinsConnectionConstants.PARAM_TOKEN), propertyNames(process(parameters)));
  }

  @Test
  public void getDefaultPropertiesOnlyDeclaresTheTestConnectionEndpoint() {
    assertEquals(
        Map.of(OAuthConstants.TEST_CONNECTION_ENDPOINT_PARAM, JenkinsConnectionConstants.TEST_CONNECTION_PATH),
        provider.getDefaultProperties());
  }

  @Test
  public void describeConnectionShowsTheJenkinsUrl() {
    assertEquals("http://jenkins:8080", provider.describeConnection(validParameters()));
  }

  @Test
  public void testConnectionCallsJenkinsWithTheEnteredCredentials() {
    TestConnectionResult result = provider.testConnection("Project", validParameters());

    assertFalse(result.isFailed());
    assertEquals("http://jenkins:8080/api/json?tree=mode", httpClient.requestedUrl);
    assertEquals("ci-user", httpClient.user);
    assertEquals("11aabbcc", httpClient.password);
  }

  @Test
  public void testConnectionReportsInvalidParametersWithoutCallingJenkins() {
    TestConnectionResult result = provider.testConnection("Project", new HashMap<>());

    assertTrue(result.isFailed());
    assertNull(httpClient.requestedUrl);
  }

  @Test
  public void testConnectionReportsRejectedCredentials() {
    httpClient.failure = new BridgeHttpException("GET", "http://jenkins:8080/api/json", 401, "unauthorized");

    TestConnectionResult result = provider.testConnection("Project", validParameters());

    assertTrue(result.isFailed());
    assertTrue(firstErrorMessage(result).contains("username"));
  }

  @Test
  public void testConnectionReportsAnUnreachableServer() {
    httpClient.failure =
        new BridgeHttpException("GET", "http://jenkins:8080/api/json", new java.net.UnknownHostException("jenkins"));

    TestConnectionResult result = provider.testConnection("Project", validParameters());

    assertTrue(result.isFailed());
    assertTrue(firstErrorMessage(result).contains("Could not reach Jenkins"));
  }

  private Collection<InvalidProperty> process(Map<String, String> parameters) {
    return Objects.requireNonNull(provider.getPropertiesProcessor()).process(parameters);
  }

  private static Map<String, String> validParameters() {
    Map<String, String> parameters = new HashMap<>();
    parameters.put(OAuthConstants.DISPLAY_NAME_PARAM, "Jenkins");
    parameters.put(JenkinsConnectionConstants.PARAM_URL, "http://jenkins:8080");
    parameters.put(JenkinsConnectionConstants.PARAM_USER, "ci-user");
    parameters.put(JenkinsConnectionConstants.PARAM_TOKEN, "11aabbcc");
    return parameters;
  }

  private static Set<String> propertyNames(Collection<InvalidProperty> errors) {
    Set<String> names = new HashSet<>();
    for (InvalidProperty error : errors) {
      names.add(error.getPropertyName());
    }
    return names;
  }

  private static String firstErrorMessage(TestConnectionResult result) {
    return result.getErrors().getFirst().getMessage();
  }

  private static class StubHttpClient extends BridgeHttpClient {
    String requestedUrl;
    String user;
    String password;
    BridgeHttpException failure;

    @Override
    public String get(String url, String user, String password, String accept) throws BridgeHttpException {
      this.requestedUrl = url;
      this.user = user;
      this.password = password;
      if (failure != null) {
        throw failure;
      }
      return "{\"mode\":\"NORMAL\"}";
    }
  }
}
