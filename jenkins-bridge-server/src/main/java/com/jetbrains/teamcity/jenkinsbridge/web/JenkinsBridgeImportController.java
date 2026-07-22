package com.jetbrains.teamcity.jenkinsbridge.web;

import com.google.gson.Gson;
import com.jetbrains.teamcity.jenkinsbridge.feature.ImportResult;
import com.jetbrains.teamcity.jenkinsbridge.feature.JenkinsJobImporter;
import com.jetbrains.teamcity.jenkinsbridge.jenkins.JenkinsClient;
import com.jetbrains.teamcity.jenkinsbridge.jenkins.JenkinsJob;
import jetbrains.buildServer.controllers.BaseController;
import jetbrains.buildServer.serverSide.ProjectManager;
import jetbrains.buildServer.serverSide.SProject;
import jetbrains.buildServer.serverSide.auth.Permission;
import jetbrains.buildServer.users.SUser;
import jetbrains.buildServer.web.openapi.WebControllerManager;
import jetbrains.buildServer.web.util.SessionUser;
import org.springframework.web.servlet.ModelAndView;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * AJAX endpoint backing the "Import Jenkins Jobs" project tab. Two actions:
 * <ul>
 *   <li>{@code action=list}: list top-level Jenkins jobs at a folder path, flagged importable /
 *       already-imported (JSON).</li>
 *   <li>{@code action=import}: create build configs for the selected jobs (JSON {@link ImportResult}).</li>
 * </ul>
 * Runs on the request thread under the logged-in user; both actions require EDIT_PROJECT on the
 * target project.
 */
public class JenkinsBridgeImportController extends BaseController {
  static final String PATH = "/admin/jenkinsBridgeImport.html";
  private static final Gson GSON = new Gson();

  private final ProjectManager projectManager;
  private final JenkinsClient jenkinsClient;
  private final JenkinsJobImporter importer;

  public JenkinsBridgeImportController(
      WebControllerManager webControllerManager,
      ProjectManager projectManager,
      JenkinsClient jenkinsClient,
      JenkinsJobImporter importer
  ) {
    this.projectManager = projectManager;
    this.jenkinsClient = jenkinsClient;
    this.importer = importer;
    webControllerManager.registerController(PATH, this);
  }

  @Override
  protected ModelAndView doHandle(HttpServletRequest request, HttpServletResponse response) throws Exception {
    String projectExternalId = request.getParameter("projectExternalId");
    SProject project = projectExternalId == null ? null : projectManager.findProjectByExternalId(projectExternalId);
    if (project == null) {
      return error(response, 400, "Unknown or missing project");
    }
    SUser user = SessionUser.getUser(request);
    if (user == null || !user.isPermissionGrantedForProject(project.getProjectId(), Permission.EDIT_PROJECT)) {
      return error(response, 403, "You do not have permission to edit this project");
    }

    String action = request.getParameter("action");
    try {
      if ("import".equals(action)) {
        return handleImport(request, response, projectExternalId);
      }
      return handleList(request, response, projectExternalId);
    } catch (Exception e) {
      return error(response, 502, e.getClass().getSimpleName()
          + (e.getMessage() == null ? "" : ": " + e.getMessage()));
    }
  }

  private ModelAndView handleList(HttpServletRequest request, HttpServletResponse response, String projectExternalId)
      throws Exception {
    String folderPath = request.getParameter("folderPath");
    Set<String> mirrored = importer.alreadyMirroredJobs(projectExternalId);

    List<JobView> views = new ArrayList<JobView>();
    for (JenkinsJob job : jenkinsClient.listJobs(folderPath == null ? "" : folderPath)) {
      views.add(new JobView(job, mirrored.contains(job.getFullName())));
    }
    return writeJson(response, views);
  }

  private ModelAndView handleImport(HttpServletRequest request, HttpServletResponse response, String projectExternalId)
      throws Exception {
    String[] jobs = request.getParameterValues("job");
    List<String> selected = jobs == null ? Collections.<String>emptyList() : Arrays.asList(jobs);
    ImportResult result = importer.importJobs(projectExternalId, selected);
    return writeJson(response, result);
  }

  private ModelAndView writeJson(HttpServletResponse response, Object payload) throws IOException {
    response.setContentType("application/json");
    response.setCharacterEncoding("UTF-8");
    response.getWriter().write(GSON.toJson(payload));
    return null;
  }

  private ModelAndView error(HttpServletResponse response, int status, String message) throws IOException {
    response.setStatus(status);
    return writeJson(response, Collections.singletonMap("error", message));
  }

  /** Shape sent to the browser for each listed job. */
  @SuppressWarnings("unused")
  private static final class JobView {
    final String name;
    final String fullName;
    final String type;
    final boolean importable;
    final boolean isMultibranch;
    final boolean alreadyImported;

    JobView(JenkinsJob job, boolean alreadyImported) {
      this.name = job.getName();
      this.fullName = job.getFullName();
      this.type = job.getType();
      this.importable = job.isImportable();
      this.isMultibranch = job.isMultibranch();
      this.alreadyImported = alreadyImported;
    }
  }
}
