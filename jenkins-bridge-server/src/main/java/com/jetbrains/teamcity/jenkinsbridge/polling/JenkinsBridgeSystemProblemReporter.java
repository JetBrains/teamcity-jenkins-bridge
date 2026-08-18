package com.jetbrains.teamcity.jenkinsbridge.polling;

import com.jetbrains.teamcity.jenkinsbridge.connection.JenkinsConnectionResolver;
import com.jetbrains.teamcity.jenkinsbridge.http.BridgeHttpException;
import com.jetbrains.teamcity.jenkinsbridge.settings.MirroredJob;
import com.intellij.openapi.diagnostic.Logger;
import jetbrains.buildServer.serverSide.SBuildType;
import jetbrains.buildServer.serverSide.ProjectManager;
import jetbrains.buildServer.serverSide.systemProblems.SystemProblem;
import jetbrains.buildServer.serverSide.systemProblems.SystemProblemNotification;
import jetbrains.buildServer.serverSide.systemProblems.SystemProblemTicket;
import jetbrains.buildServer.serverSide.connections.ConnectionDescriptor;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

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
  private final JenkinsConnectionResolver connectionResolver;
  /**
   * A ticket is deliberately keyed by the complete configured mapping rather than just the build
   * type. This makes a changed Jenkins connection or job a new mapping and lets reconciliation
   * remove the old mapping's warning.
   */
  private final Map<MappingKey, ActiveTicket> tickets = new HashMap<>();

  public JenkinsBridgeSystemProblemReporter(
      ProjectManager projectManager,
      SystemProblemNotification notification,
      JenkinsConnectionResolver connectionResolver
  ) {
    this.projectManager = projectManager;
    this.notification = notification;
    this.connectionResolver = connectionResolver;
  }

  /** Raises the missing-job problem for an authoritative Jenkins job lookup. */
  public synchronized void reportMissingJob(MirroredJob job, BridgeHttpException failure) {
    report(job, MISSING_JOB_TYPE, failure);
  }

  /** Raises the connectivity/access problem for a Jenkins transport or authorization failure. */
  public synchronized void reportConnectivity(MirroredJob job, BridgeHttpException failure) {
    report(job, CONNECTIVITY_TYPE, failure);
  }

  private void report(MirroredJob job, String problemType, BridgeHttpException failure) {
    if (job == null || !job.hasMinimumConfiguration()) {
      LOG.warn("Jenkins Bridge cannot report a system problem for an incomplete mapping");
      return;
    }
    SBuildType buildType = buildType(job);
    if (buildType == null || notification == null) {
      LOG.warn("Jenkins Bridge cannot report a system problem for " + job.describeForLog()
          + ": TeamCity build type was not found");
      return;
    }

    MappingKey key = MappingKey.of(job);
    String connectionLabel = connectionLabel(buildType, job.connectionId());
    ActiveTicket existing = tickets.get(key);
    if (existing != null
        && existing.problemType.equals(problemType)
        && existing.connectionLabel.equals(connectionLabel)) {
      return;
    }

    if (existing != null) {
      tickets.remove(key);
      cancel(existing.ticket);
    }

    boolean missing = MISSING_JOB_TYPE.equals(problemType);
    String description = missing
        ? "Jenkins job '" + job.jenkinsJob() + "' on connection " + connectionLabel
            + " no longer exists or is not accessible."
        : "Jenkins Bridge cannot access connection " + connectionLabel + " while polling job '"
            + job.jenkinsJob() + "'.";
    SystemProblem problem = new SystemProblem(
        description,
        null,
        causeDetails(failure),
        problemType,
        SOURCE);
    try {
      SystemProblemTicket ticket = notification.raiseProblem(buildType, problem);
      if (ticket != null) {
        tickets.put(key, new ActiveTicket(problemType, connectionLabel, ticket));
      }
      LOG.warn(description + " " + causeDetails(failure));
    } catch (RuntimeException e) {
      LOG.error("Jenkins Bridge failed to raise a system problem for " + job.describeForLog(), e);
    }
  }

  /** Clears the problem for a mapping after a complete successful poll. */
  public synchronized void recover(MirroredJob job) {
    if (job == null || !job.hasMinimumConfiguration()) {
      return;
    }
    ActiveTicket ticket = tickets.remove(MappingKey.of(job));
    if (ticket != null) {
      cancel(ticket.ticket);
    }
  }

  /**
   * Removes tickets belonging to mappings that no longer exist. This intentionally does not look
   * up a build type: a deleted build type must not leave an unreachable ticket in this reporter.
   */
  public synchronized void reconcile(Collection<MirroredJob> activeJobs) {
    Set<MappingKey> activeKeys = new HashSet<>();
    if (activeJobs != null) {
      for (MirroredJob job : activeJobs) {
        if (job != null && job.hasMinimumConfiguration()) {
          activeKeys.add(MappingKey.of(job));
        }
      }
    }

    for (MappingKey key : new HashSet<>(tickets.keySet())) {
      if (!activeKeys.contains(key)) {
        ActiveTicket ticket = tickets.remove(key);
        if (ticket != null) {
          cancel(ticket.ticket);
        }
      }
    }
  }

  /** Clears every ticket when bridge polling is stopped. */
  public synchronized void clearAll() {
    for (ActiveTicket ticket : tickets.values()) {
      cancel(ticket.ticket);
    }
    tickets.clear();
  }

  private void cancel(SystemProblemTicket ticket) {
    try {
      ticket.cancel();
    } catch (RuntimeException e) {
      LOG.error("Jenkins Bridge failed to cancel a system problem ticket", e);
    }
  }

  private String causeDetails(BridgeHttpException failure) {
    if (failure == null) {
      return "Jenkins request failed.";
    }
    int status = failure.getStatusCode();
    return status > 0 ? "Jenkins returned HTTP " + status + "." : "Jenkins request failed.";
  }

  private SBuildType buildType(MirroredJob job) {
    if (job == null || !job.hasMinimumConfiguration()) {
      return null;
    }
    return projectManager == null
        ? null : projectManager.findBuildTypeByExternalId(job.teamCityBuildTypeExternalId());
  }

  private String connectionLabel(SBuildType buildType, String connectionId) {
    if (connectionResolver != null && buildType != null) {
      ConnectionDescriptor descriptor = connectionResolver.findConnection(buildType.getProject(), connectionId);
      String displayName = descriptor == null ? null : descriptor.getDisplayName();
      if (displayName != null && !displayName.trim().isEmpty()) {
        displayName = displayName.trim();
        if (!displayName.equals(connectionId)) {
          return "'" + displayName + "' (ID: " + connectionId + ")";
        }
      }
    }
    return "'" + connectionId + "'";
  }

  private record MappingKey(String buildTypeExternalId, String connectionId, String jenkinsJob) {
    private static MappingKey of(MirroredJob job) {
      return new MappingKey(job.teamCityBuildTypeExternalId(), job.connectionId(), job.jenkinsJob());
    }
  }

  private record ActiveTicket(String problemType, String connectionLabel, SystemProblemTicket ticket) {
  }
}
