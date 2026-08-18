package com.jetbrains.teamcity.jenkinsbridge.web;

import com.google.gson.Gson;
import com.jetbrains.teamcity.jenkinsbridge.feature.ImportResult;
import com.jetbrains.teamcity.jenkinsbridge.feature.JenkinsJobImporter;
import com.jetbrains.teamcity.jenkinsbridge.http.BridgeHttpException;
import com.jetbrains.teamcity.jenkinsbridge.connection.JenkinsConnectionResolver;
import com.jetbrains.teamcity.jenkinsbridge.jenkins.JenkinsClient;
import com.jetbrains.teamcity.jenkinsbridge.jenkins.JenkinsClientFactory;
import com.jetbrains.teamcity.jenkinsbridge.jenkins.JenkinsDataException;
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
import java.util.Map;
import java.util.Set;
import jetbrains.buildServer.serverSide.connections.ConnectionDescriptor;

/**
 * AJAX endpoint backing the "Jenkins Jobs Sync" project tab. Three actions:
 * <ul>
 *   <li>{@code action=configured}: list already-configured Jenkins jobs without contacting Jenkins.</li>
 *   <li>{@code action=list}: list top-level Jenkins jobs at a folder path, flagged importable /
 *       already-imported (JSON).</li>
 *   <li>{@code action=import}: create build configs for the selected jobs (JSON {@link ImportResult}).</li>
 * </ul>
 * Runs on the request thread under the logged-in user; both actions require EDIT_PROJECT on the
 * target project. List and import also need a {@code connectionId} naming the Jenkins connection to
 * read from, since a project can have several Jenkins servers.
 */
public class JenkinsBridgeImportController extends BaseController {
  static final String PATH = "/admin/jenkinsBridgeImport.html";
  private static final int DEFAULT_PAGE_SIZE = 100;
  private static final int MAX_PAGE_SIZE = 100;
  private static final Gson GSON = new Gson();

  private final ProjectManager projectManager;
  private final JenkinsClientFactory jenkinsClientFactory;
  private final JenkinsJobImporter importer;
  private final JenkinsConnectionResolver connectionResolver;

  public JenkinsBridgeImportController(
      WebControllerManager webControllerManager,
      ProjectManager projectManager,
      JenkinsClientFactory jenkinsClientFactory,
      JenkinsJobImporter importer,
      JenkinsConnectionResolver connectionResolver
  ) {
    this.projectManager = projectManager;
    this.jenkinsClientFactory = jenkinsClientFactory;
    this.importer = importer;
    this.connectionResolver = connectionResolver;
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
      if ("configured".equals(action)) {
        return writeJson(response, Collections.singletonMap(
            "configuredJobs", configuredJobViews(project)));
      }
      String connectionId = request.getParameter("connectionId");
      if (connectionId == null || connectionId.trim().isEmpty()) {
        return error(response, 400, "No Jenkins connection selected");
      }
      if ("import".equals(action)) {
        return handleImport(request, response, projectExternalId, connectionId);
      }
      return handleList(request, response, project, projectExternalId, connectionId);
    } catch (BridgeHttpException | JenkinsDataException e) {
      return error(response, 502, e.getClass().getSimpleName()
          + (e.getMessage() == null ? "" : ": " + e.getMessage()));
    }
  }

  private ModelAndView handleList(HttpServletRequest request, HttpServletResponse response, SProject project,
                                  String projectExternalId, String connectionId) throws Exception {
    String folderPath = request.getParameter("folderPath");
    String search = request.getParameter("search");
    int offset = nonNegativeInt(request.getParameter("offset"), 0);
    int limit = boundedPageSize(request.getParameter("limit"));
    Set<String> mirrored = importer.alreadyMirroredJobs(projectExternalId);

    List<JobView> views = new ArrayList<JobView>();
    JenkinsClient jenkinsClient = jenkinsClientFactory.forConnectionId(project, connectionId);
    int scanOffset = offset;
    int nextOffset = offset;
    boolean hasMore = false;
    while (views.size() < limit) {
      List<JenkinsJob> jobs = jenkinsClient.listJobs(folderPath == null ? "" : folderPath,
          scanOffset, limit, search);
      int consumed = 0;
      for (JenkinsJob job : jobs) {
        consumed++;
        if (!mirrored.contains(job.getFullName())) {
          views.add(new JobView(job, false));
          if (views.size() >= limit) {
            break;
          }
        }
      }
      scanOffset += consumed;
      nextOffset = scanOffset;
      hasMore = consumed < jobs.size() || jobs.size() == limit;
      if (!hasMore || jobs.isEmpty()) {
        break;
      }
    }
    List<JobView> configured = configuredJobViews(project);
    return writeJson(response, new JobPage(offset, limit, nextOffset, hasMore, configured, views));
  }

  private List<JobView> configuredJobViews(SProject project) {
    Set<String> mirrored = importer.alreadyMirroredJobs(project.getExternalId());
    Map<String, String> mirroredTypes = importer.alreadyMirroredJobTypes(project.getExternalId());
    Map<String, String> mirroredConnections = importer.alreadyMirroredJobConnections(project.getExternalId());
    Map<String, String> mirroredUrls = importer.alreadyMirroredJobUrls(project.getExternalId());
    List<String> configuredNames = new ArrayList<String>(mirrored);
    Collections.sort(configuredNames);
    List<JobView> configured = new ArrayList<JobView>();
    for (String name : configuredNames) {
      String connectionId = mirroredConnections.get(name);
      ConnectionDescriptor descriptor = connectionResolver.findConnection(project, connectionId);
      String connectionName = descriptor == null
          ? (connectionId == null || connectionId.trim().isEmpty() ? "Unknown connection" : connectionId)
          : descriptor.getDisplayName();
      configured.add(JobView.configured(name, mirroredTypes.get(name), connectionName, mirroredUrls.get(name)));
    }
    return configured;
  }

  private static int boundedPageSize(String value) {
    return Math.min(MAX_PAGE_SIZE, Math.max(1, nonNegativeInt(value, DEFAULT_PAGE_SIZE)));
  }

  private static int nonNegativeInt(String value, int defaultValue) {
    if (value == null || value.trim().isEmpty()) {
      return defaultValue;
    }
    try {
      return Math.max(0, Integer.parseInt(value.trim()));
    } catch (NumberFormatException ignored) {
      return defaultValue;
    }
  }

  private ModelAndView handleImport(HttpServletRequest request, HttpServletResponse response,
                                    String projectExternalId, String connectionId) throws Exception {
    String[] jobs = request.getParameterValues("job");
    List<String> selected = jobs == null ? Collections.<String>emptyList() : Arrays.asList(jobs);
    ImportResult result = importer.importJobs(projectExternalId, connectionId, selected);
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
    final String displayType;
    final String connection;
    final String url;
    final boolean importable;
    final boolean isMultibranch;
    final boolean alreadyImported;

    JobView(JenkinsJob job, boolean alreadyImported) {
      this.name = job.getName();
      this.fullName = job.getFullName();
      this.type = job.getType();
      this.displayType = job.getDisplayType();
      this.connection = null;
      this.url = job.getUrl();
      this.importable = job.isImportable();
      this.isMultibranch = job.isMultibranch();
      this.alreadyImported = alreadyImported;
    }

    static JobView configured(String fullName, String jenkinsClass, String connection, String url) {
      String displayType = jenkinsClass == null || jenkinsClass.trim().isEmpty()
          ? "Configured"
          : JenkinsJob.displayType(jenkinsClass);
      return new JobView(fullName, displayType, connection, url);
    }

    private JobView(String fullName, String type, String connection, String url) {
      this.name = leafName(fullName);
      this.fullName = fullName;
      this.type = type;
      this.displayType = type;
      this.connection = connection;
      this.url = url;
      this.importable = true;
      this.isMultibranch = false;
      this.alreadyImported = true;
    }

    private static String leafName(String fullName) {
      int slash = fullName.lastIndexOf('/');
      return slash >= 0 && slash < fullName.length() - 1
          ? fullName.substring(slash + 1) : fullName;
    }
  }

  private static final class JobPage {
    final int offset;
    final int limit;
    final int nextOffset;
    final boolean hasMore;
    final List<JobView> configuredJobs;
    final List<JobView> jobs;

    JobPage(int offset, int limit, int nextOffset, boolean hasMore,
            List<JobView> configuredJobs, List<JobView> jobs) {
      this.offset = offset;
      this.limit = limit;
      this.nextOffset = nextOffset;
      this.hasMore = hasMore;
      this.configuredJobs = configuredJobs;
      this.jobs = jobs;
    }
  }
}
