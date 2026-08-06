package com.jetbrains.teamcity.jenkinsbridge.polling;

import com.jetbrains.teamcity.jenkinsbridge.http.BridgeHttpException;
import com.jetbrains.teamcity.jenkinsbridge.settings.MirroredJob;
import com.intellij.openapi.diagnostic.Logger;
import jetbrains.buildServer.serverSide.SBuildType;
import jetbrains.buildServer.serverSide.ProjectManager;
import jetbrains.buildServer.serverSide.systemProblems.SystemProblem;
import jetbrains.buildServer.serverSide.systemProblems.SystemProblemNotification;
import jetbrains.buildServer.serverSide.systemProblems.SystemProblemTicket;

import java.util.HashMap;
import java.util.Map;

/**
 * Publishes persistent TeamCity system problems for Jenkins failures and clears them after a
 * successful poll. Problems are attached to the build configuration that owns the Jenkins Bridge
 * feature, so the warning is visible where the broken mapping is configured.
 */
public class JenkinsBridgeSystemProblemReporter {
  private static final Logger LOG = Logger.getInstance(JenkinsBridgeSystemProblemReporter.class.getName());
  static final String MISSING_JOB_TYPE = "jenkinsBridge.missingJob";
  static final String CONNECTIVITY_TYPE = "jenkinsBridge.connectivity";
  private static final String SOURCE = "Jenkins Bridge";

  private final ProjectManager projectManager;
  private final SystemProblemNotification notification;
  private final Map<String, SystemProblemTicket> tickets = new HashMap<>();

  public JenkinsBridgeSystemProblemReporter(
      ProjectManager projectManager,
      SystemProblemNotification notification
  ) {
    this.projectManager = projectManager;
    this.notification = notification;
  }

  public void report(MirroredJob job, Exception failure) {
    SBuildType buildType = buildType(job);
    if (buildType == null || notification == null) {
      LOG.warn("Jenkins Bridge cannot report a system problem for " + job.describeForLog()
          + ": TeamCity build type was not found");
      return;
    }

    boolean missing = failure instanceof BridgeHttpException
        && ((BridgeHttpException) failure).getStatusCode() == 404;
    String problemType = missing ? MISSING_JOB_TYPE : CONNECTIVITY_TYPE;
    String key = buildType.getExternalId() + "|" + problemType;
    if (tickets.containsKey(key)) {
      return;
    }

    String description = missing
        ? "Jenkins job '" + job.jenkinsJob() + "' no longer exists or is not accessible."
        : "Jenkins cannot be reached while polling job '" + job.jenkinsJob() + "'.";
    SystemProblem problem = new SystemProblem(
        description,
        failure,
        failure == null ? "" : failure.getMessage(),
        problemType,
        SOURCE);
    tickets.put(key, notification.raiseProblem(buildType, problem));
    LOG.warn(description, failure);
  }

  public void clear(MirroredJob job) {
    SBuildType buildType = buildType(job);
    if (buildType == null) {
      return;
    }
    String prefix = buildType.getExternalId() + "|";
    clear(prefix + MISSING_JOB_TYPE);
    clear(prefix + CONNECTIVITY_TYPE);
  }

  private void clear(String key) {
    SystemProblemTicket ticket = tickets.remove(key);
    if (ticket != null) {
      ticket.cancel();
    }
  }

  private SBuildType buildType(MirroredJob job) {
    if (job == null || !job.hasMinimumConfiguration()) {
      return null;
    }
    return projectManager.findBuildTypeByExternalId(job.teamCityBuildTypeExternalId());
  }
}
