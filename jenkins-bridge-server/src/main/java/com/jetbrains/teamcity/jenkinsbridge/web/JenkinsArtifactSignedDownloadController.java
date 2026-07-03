package com.jetbrains.teamcity.jenkinsbridge.web;

import com.jetbrains.teamcity.jenkinsbridge.artifactstorage.JenkinsArtifactContentProvider;
import com.jetbrains.teamcity.jenkinsbridge.artifactstorage.JenkinsArtifactDownloadSigner;
import com.jetbrains.teamcity.jenkinsbridge.http.BridgeHttpException;
import jetbrains.buildServer.controllers.BaseController;
import jetbrains.buildServer.web.openapi.WebControllerManager;
import org.jetbrains.annotations.NotNull;
import org.springframework.web.servlet.ModelAndView;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.OutputStream;

/**
 * Streams a Jenkins build artifact using the bridge server's own stored Jenkins credentials.
 * Callers must have a short-lived HMAC-signed token from {@link JenkinsArtifactDownloadSigner}.
 */
public class JenkinsArtifactSignedDownloadController extends BaseController {
  public static final String PATH = "/jenkinsBridgeArtifactDownload.html";

  private final JenkinsArtifactDownloadSigner mySigner;
  private final JenkinsArtifactContentProvider myContentProvider;

  public JenkinsArtifactSignedDownloadController(
      WebControllerManager webControllerManager,
      JenkinsArtifactContentProvider contentProvider,
      JenkinsArtifactDownloadSigner signer
  ) {
    mySigner = signer;
    myContentProvider = contentProvider;
    webControllerManager.registerController(PATH, this);
  }

  @Override
  protected ModelAndView doHandle(HttpServletRequest request, @NotNull HttpServletResponse response) throws Exception {
    String job = request.getParameter("job");
    String buildNumberParam = request.getParameter("build");
    String relativePath = request.getParameter("path");
    String expiryParam = request.getParameter("expires");
    String signature = request.getParameter("signature");

    if (job == null || relativePath == null || buildNumberParam == null || expiryParam == null || signature == null) {
      return error(response, 400, "Missing required parameters");
    }

    int buildNumber;
    long expiry;
    try {
      buildNumber = Integer.parseInt(buildNumberParam);
      expiry = Long.parseLong(expiryParam);
    } catch (NumberFormatException e) {
      return error(response, 400, "Malformed build number or expiry");
    }

    if (!mySigner.isValid(job, buildNumber, relativePath, expiry, signature)) {
      return error(response, 403, "Invalid or expired download token");
    }

    try {
      response.setContentType("application/octet-stream");
      OutputStream out = response.getOutputStream();
      myContentProvider.handleArtifactStream(job, buildNumber, relativePath, out);
      out.flush();
    } catch (BridgeHttpException e) {
      return error(response, 502, "Failed to fetch Jenkins artifact " + relativePath + ": " + e.getMessage());
    }
    return null;
  }

  private ModelAndView error(HttpServletResponse response, int status, String message) throws IOException {
    response.setStatus(status);
    response.setContentType("text/plain");
    response.getWriter().write(message);
    return null;
  }
}
