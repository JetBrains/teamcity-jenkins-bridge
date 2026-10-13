package com.jetbrains.teamcity.jenkinsbridge.web;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.jetbrains.teamcity.jenkinsbridge.connection.JenkinsConnectionResolver;
import com.jetbrains.teamcity.jenkinsbridge.feature.JenkinsJobImporter;
import com.jetbrains.teamcity.jenkinsbridge.http.BridgeHttpException;
import com.jetbrains.teamcity.jenkinsbridge.jenkins.JenkinsClient;
import com.jetbrains.teamcity.jenkinsbridge.jenkins.JenkinsClientFactory;
import jetbrains.buildServer.serverSide.ProjectManager;
import jetbrains.buildServer.serverSide.SProject;
import jetbrains.buildServer.serverSide.auth.Permission;
import jetbrains.buildServer.users.SUser;
import jetbrains.buildServer.web.openapi.WebControllerManager;
import jetbrains.buildServer.web.util.SessionUser;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.springframework.web.servlet.ModelAndView;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class JenkinsBridgeImportControllerTest {
  private static final String PROJECT_EXTERNAL_ID = "project";
  private static final String FOLDER_PATH = "qui";

  @Test
  public void invalidFolderPathReturnsInformationalMessageWithoutJenkinsHtml() throws Exception {
    Fixture fixture = new Fixture();
    String jenkinsHtml = "<html><body>Not Found: private Jenkins detail</body></html>";
    when(fixture.jenkinsClient.listJobs(FOLDER_PATH, 0, 100, ""))
        .thenThrow(new BridgeHttpException("GET", "http://jenkins/job/qui/api/json", 404, jenkinsHtml));

    try (MockedStatic<SessionUser> sessionUsers = mockStatic(SessionUser.class)) {
      fixture.allowProjectEditor(sessionUsers);

      ModelAndView result = fixture.controller.doHandle(fixture.request, fixture.response);

      assertNull(result);
      verify(fixture.response).setStatus(HttpServletResponse.SC_OK);
      JsonObject body = fixture.responseJson();
      assertEquals("Jenkins folder path 'qui' was not found (HTTP 404).", body.get("message").getAsString());
      assertFalse(body.has("error"));
      assertFalse(fixture.responseBody.toString().contains(jenkinsHtml));
    }
  }

  @Test
  public void non404JenkinsFailureKeepsGatewayErrorBehavior() throws Exception {
    Fixture fixture = new Fixture();
    when(fixture.jenkinsClient.listJobs(FOLDER_PATH, 0, 100, ""))
        .thenThrow(new BridgeHttpException("GET", "http://jenkins/job/qui/api/json", 503, "unavailable"));

    try (MockedStatic<SessionUser> sessionUsers = mockStatic(SessionUser.class)) {
      fixture.allowProjectEditor(sessionUsers);

      fixture.controller.doHandle(fixture.request, fixture.response);

      verify(fixture.response).setStatus(502);
      assertTrue(fixture.responseJson().get("error").getAsString().contains("HTTP 503"));
    }
  }

  @Test
  public void successfulListingKeepsItsExistingResponseShape() throws Exception {
    Fixture fixture = new Fixture();
    when(fixture.jenkinsClient.listJobs(FOLDER_PATH, 0, 100, ""))
        .thenReturn(Collections.emptyList());
    when(fixture.importer.alreadyMirroredJobTypes(PROJECT_EXTERNAL_ID))
        .thenReturn(Collections.emptyMap());
    when(fixture.importer.alreadyMirroredJobConnections(PROJECT_EXTERNAL_ID))
        .thenReturn(Collections.emptyMap());
    when(fixture.importer.alreadyMirroredJobUrls(PROJECT_EXTERNAL_ID))
        .thenReturn(Collections.emptyMap());

    try (MockedStatic<SessionUser> sessionUsers = mockStatic(SessionUser.class)) {
      fixture.allowProjectEditor(sessionUsers);

      fixture.controller.doHandle(fixture.request, fixture.response);

      JsonObject body = fixture.responseJson();
      assertTrue(body.has("jobs"));
      assertFalse(body.has("message"));
      verify(fixture.response, never()).setStatus(anyInt());
    }
  }

  private static final class Fixture {
    final ProjectManager projectManager = mock(ProjectManager.class);
    final JenkinsClientFactory jenkinsClientFactory = mock(JenkinsClientFactory.class);
    final JenkinsJobImporter importer = mock(JenkinsJobImporter.class);
    final JenkinsConnectionResolver connectionResolver = mock(JenkinsConnectionResolver.class);
    final SProject project = mock(SProject.class);
    final SUser user = mock(SUser.class);
    final JenkinsClient jenkinsClient = mock(JenkinsClient.class);
    final HttpServletRequest request = mock(HttpServletRequest.class);
    final HttpServletResponse response = mock(HttpServletResponse.class);
    final StringWriter responseBody = new StringWriter();
    final JenkinsBridgeImportController controller;

    Fixture() throws Exception {
      when(request.getParameter("projectExternalId")).thenReturn(PROJECT_EXTERNAL_ID);
      when(request.getParameter("action")).thenReturn("list");
      when(request.getParameter("connectionId")).thenReturn("jenkins-connection");
      when(request.getParameter("folderPath")).thenReturn(FOLDER_PATH);
      when(request.getParameter("search")).thenReturn("");
      when(projectManager.findProjectByExternalId(PROJECT_EXTERNAL_ID)).thenReturn(project);
      when(project.getProjectId()).thenReturn("project-id");
      when(user.isPermissionGrantedForProject("project-id", Permission.EDIT_PROJECT)).thenReturn(true);
      when(importer.alreadyMirroredJobs(PROJECT_EXTERNAL_ID)).thenReturn(Collections.emptySet());
      when(jenkinsClientFactory.forConnectionId(project, "jenkins-connection")).thenReturn(jenkinsClient);
      when(response.getWriter()).thenReturn(new PrintWriter(responseBody));
      controller = new JenkinsBridgeImportController(
          mock(WebControllerManager.class), projectManager, jenkinsClientFactory, importer, connectionResolver);
    }

    void allowProjectEditor(MockedStatic<SessionUser> sessionUsers) {
      sessionUsers.when(() -> SessionUser.getUser(request)).thenReturn(user);
    }

    JsonObject responseJson() {
      return new JsonParser().parse(responseBody.toString()).getAsJsonObject();
    }
  }
}
