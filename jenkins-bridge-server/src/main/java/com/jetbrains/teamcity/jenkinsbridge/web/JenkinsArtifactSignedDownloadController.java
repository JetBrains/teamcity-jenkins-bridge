package com.jetbrains.teamcity.jenkinsbridge.web;

import com.jetbrains.teamcity.jenkinsbridge.artifactstorage.JenkinsArtifactContentProvider;
import com.jetbrains.teamcity.jenkinsbridge.artifactstorage.JenkinsArtifactDownloadSigner;
import com.jetbrains.teamcity.jenkinsbridge.http.BridgeHttpException;
import com.jetbrains.teamcity.jenkinsbridge.jenkins.JenkinsClient;
import com.jetbrains.teamcity.jenkinsbridge.jenkins.JenkinsClientFactory;
import jetbrains.buildServer.controllers.BaseController;
import jetbrains.buildServer.serverSide.BuildPromotion;
import jetbrains.buildServer.serverSide.BuildPromotionManager;
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
 * The token specifies the TeamCity build, which is what says with which Jenkins connection to fetch.
 */
public class JenkinsArtifactSignedDownloadController extends BaseController {
  public static final String PATH = "/jenkinsBridgeArtifactDownload.html";

  private final JenkinsArtifactDownloadSigner mySigner;
  private final JenkinsArtifactContentProvider myContentProvider;
  private final BuildPromotionManager myBuildPromotionManager;
  private final JenkinsClientFactory myJenkinsClientFactory;

  public JenkinsArtifactSignedDownloadController(
      WebControllerManager webControllerManager,
      JenkinsArtifactContentProvider contentProvider,
      JenkinsArtifactDownloadSigner signer,
      BuildPromotionManager buildPromotionManager,
      JenkinsClientFactory jenkinsClientFactory
  ) {
    mySigner = signer;
    myContentProvider = contentProvider;
    myBuildPromotionManager = buildPromotionManager;
    myJenkinsClientFactory = jenkinsClientFactory;
    webControllerManager.registerController(PATH, this);
  }

  @Override
  protected ModelAndView doHandle(HttpServletRequest request, @NotNull HttpServletResponse response) throws Exception {
    String job = request.getParameter("job");
    String buildNumberParam = request.getParameter("build");
    String relativePath = request.getParameter("path");
    String expiryParam = request.getParameter("expires");
    String signature = request.getParameter("signature");
    String teamCityBuildIdParam = request.getParameter("buildId");

    if (job == null || relativePath == null || buildNumberParam == null || expiryParam == null
        || signature == null || teamCityBuildIdParam == null) {
      return error(response, 400, "Missing required parameters");
    }

    int buildNumber;
    long expiry;
    long teamCityBuildId;
    try {
      buildNumber = Integer.parseInt(buildNumberParam);
      expiry = Long.parseLong(expiryParam);
      teamCityBuildId = Long.parseLong(teamCityBuildIdParam);
    } catch (NumberFormatException e) {
      return error(response, 400, "Malformed build number, build id or expiry");
    }

    BuildPromotion promotion = myBuildPromotionManager.findPromotionOrReplacement(teamCityBuildId);
    if (promotion == null) {
      return error(response, 404, "TeamCity build " + teamCityBuildId + " was not found");
    }

    if (!mySigner.isValid(promotion, teamCityBuildId, job, buildNumber, relativePath, expiry, signature)) {
      return error(response, 403, "Invalid or expired download token");
    }

    JenkinsClient jenkinsClient;
    try {
      jenkinsClient = myJenkinsClientFactory.forBuildPromotion(promotion);
    } catch (IllegalStateException e) {
      return error(response, 409, e.getMessage());
    }

    try {
      response.setContentType("application/octet-stream");
      OutputStream out = response.getOutputStream();
      myContentProvider.handleArtifactStream(jenkinsClient, job, buildNumber, relativePath, out);
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
