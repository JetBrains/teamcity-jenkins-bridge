package com.jetbrains.teamcity.jenkinsbridge.polling;

import com.jetbrains.teamcity.jenkinsbridge.http.BridgeHttpException;
import com.jetbrains.teamcity.jenkinsbridge.connection.JenkinsConnectionResolver;
import com.jetbrains.teamcity.jenkinsbridge.settings.MirroredJob;
import jetbrains.buildServer.serverSide.ProjectManager;
import jetbrains.buildServer.serverSide.SBuildType;
import jetbrains.buildServer.serverSide.SProject;
import jetbrains.buildServer.serverSide.connections.ConnectionDescriptor;
import jetbrains.buildServer.serverSide.systemProblems.SystemProblem;
import jetbrains.buildServer.serverSide.systemProblems.SystemProblemNotification;
import jetbrains.buildServer.serverSide.systemProblems.SystemProblemTicket;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class JenkinsBridgeSystemProblemReporterTest {
  private final ProjectManager projectManager = mock(ProjectManager.class);
  private final SystemProblemNotification notification = mock(SystemProblemNotification.class);
  private final JenkinsConnectionResolver connectionResolver = mock(JenkinsConnectionResolver.class);
  private final SBuildType buildType = mock(SBuildType.class);
  private final SProject project = mock(SProject.class);
  private final ConnectionDescriptor connection = mock(ConnectionDescriptor.class);
  private final SystemProblemTicket missingTicket = mock(SystemProblemTicket.class);
  private final SystemProblemTicket connectivityTicket = mock(SystemProblemTicket.class);
  private JenkinsBridgeSystemProblemReporter reporter;

  @Before
  public void setUp() {
    when(projectManager.findBuildTypeByExternalId("Build_One")).thenReturn(buildType);
    when(buildType.getProject()).thenReturn(project);
    when(connectionResolver.findConnection(project, "connection-a")).thenReturn(connection);
    when(connection.getDisplayName()).thenReturn("Local Jenkins");
    when(notification.raiseProblem(org.mockito.ArgumentMatchers.eq(buildType),
        org.mockito.ArgumentMatchers.any(SystemProblem.class)))
        .thenReturn(missingTicket, connectivityTicket);
    reporter = new JenkinsBridgeSystemProblemReporter(projectManager, notification, connectionResolver);
  }

  @Test
  public void duplicateProblemForTheSameMappingIsRaisedOnlyOnce() {
    MirroredJob job = job("connection-a", "folder/job");

    reporter.reportMissingJob(job, notFound());
    reporter.reportMissingJob(job, notFound());

    verify(notification, times(1)).raiseProblem(org.mockito.ArgumentMatchers.eq(buildType),
        org.mockito.ArgumentMatchers.any(SystemProblem.class));
  }

  @Test
  public void changingProblemTypeCancelsThePreviousTicketBeforeRaisingTheReplacement() {
    MirroredJob job = job("connection-a", "folder/job");

    reporter.reportMissingJob(job, notFound());
    reporter.reportConnectivity(job, unavailable());

    InOrder order = inOrder(notification, missingTicket);
    order.verify(notification).raiseProblem(org.mockito.ArgumentMatchers.eq(buildType),
        org.mockito.ArgumentMatchers.any(SystemProblem.class));
    order.verify(missingTicket).cancel();
    order.verify(notification).raiseProblem(org.mockito.ArgumentMatchers.eq(buildType),
        org.mockito.ArgumentMatchers.any(SystemProblem.class));
    verify(connectivityTicket, never()).cancel();
  }

  @Test
  public void changingConnectionDisplayNameRefreshesTheActiveProblem() {
    MirroredJob job = job("connection-a", "folder/job");
    when(connection.getDisplayName()).thenReturn("Local Jenkins", "Renamed Jenkins");

    reporter.reportMissingJob(job, notFound());
    reporter.reportMissingJob(job, notFound());

    verify(missingTicket).cancel();
    verify(notification, times(2)).raiseProblem(org.mockito.ArgumentMatchers.eq(buildType),
        org.mockito.ArgumentMatchers.any(SystemProblem.class));
  }

  @Test
  public void concurrentDuplicateReportsStillRaiseOneTicket() throws Exception {
    MirroredJob job = job("connection-a", "folder/job");
    int workers = 8;
    ExecutorService executor = Executors.newFixedThreadPool(workers);
    CountDownLatch start = new CountDownLatch(1);
    List<Future<?>> futures = new ArrayList<>();
    try {
      for (int i = 0; i < workers; i++) {
        futures.add(executor.submit(() -> {
          start.await();
          reporter.reportConnectivity(job, unavailable());
          return null;
        }));
      }
      start.countDown();
      for (Future<?> future : futures) {
        future.get();
      }
    } finally {
      executor.shutdownNow();
    }

    verify(notification, times(1)).raiseProblem(org.mockito.ArgumentMatchers.eq(buildType),
        org.mockito.ArgumentMatchers.any(SystemProblem.class));
  }

  @Test
  public void missingBuildTypeDoesNotRaiseAProblem() {
    MirroredJob job = new MirroredJob(
        "connection-a", "folder/job", "Deleted_Build", "Deleted Build", 1, false);

    reporter.reportConnectivity(job, unavailable());

    verify(notification, never()).raiseProblem(
        org.mockito.ArgumentMatchers.any(SBuildType.class),
        org.mockito.ArgumentMatchers.any(SystemProblem.class));
  }

  @Test
  public void recoveryCancelsTheSingleActiveTicketAndIsIdempotent() {
    MirroredJob job = job("connection-a", "folder/job");

    reporter.reportConnectivity(job, unavailable());
    reporter.recover(job);
    reporter.recover(job);

    verify(missingTicket, times(1)).cancel();
  }

  @Test
  public void reconcileRemovesTicketsForDeletedOrChangedMappings() {
    MirroredJob retained = job("connection-a", "folder/job");
    MirroredJob removed = job("connection-b", "other/job");
    reporter.reportMissingJob(retained, notFound());
    reporter.reportConnectivity(removed, unavailable());

    reporter.reconcile(Arrays.asList(retained));

    verify(connectivityTicket).cancel();
    verify(missingTicket, never()).cancel();
  }

  @Test
  public void clearAllCancelsEveryActiveTicket() {
    reporter.reportMissingJob(job("connection-a", "folder/job"), notFound());
    reporter.reportConnectivity(job("connection-b", "other/job"), unavailable());

    reporter.clearAll();

    verify(missingTicket).cancel();
    verify(connectivityTicket).cancel();
  }

  @Test
  public void problemDescriptionNamesTheSafeMappingIdentityAndDoesNotExposeFailureBody() {
    reporter.reportMissingJob(job("connection-a", "folder/job"),
        new BridgeHttpException("GET", "http://jenkins", 404, "secret-response"));

    ArgumentCaptor<SystemProblem> problem = forClass(SystemProblem.class);
    verify(notification).raiseProblem(org.mockito.ArgumentMatchers.eq(buildType), problem.capture());
    assertEquals(JenkinsBridgeSystemProblemReporter.MISSING_JOB_TYPE, problem.getValue().getProblemType());
    assertTrue(problem.getValue().getDescription().contains("connection-a"));
    assertTrue(problem.getValue().getDescription().contains("Local Jenkins"));
    assertTrue(problem.getValue().getDescription().contains("folder/job"));
    assertEquals("Jenkins returned HTTP 404.", problem.getValue().getCauseDetails());
    assertNull(problem.getValue().getCause());
  }

  private static MirroredJob job(String connectionId, String jenkinsJob) {
    return new MirroredJob(connectionId, jenkinsJob, "Build_One", "Build One", 1, false);
  }

  private static BridgeHttpException notFound() {
    return new BridgeHttpException("GET", "http://jenkins", 404, "not found");
  }

  private static BridgeHttpException unavailable() {
    return new BridgeHttpException("GET", "http://jenkins", new RuntimeException("down"));
  }
}
