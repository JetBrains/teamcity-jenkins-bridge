package com.jetbrains.teamcity.jenkinsbridge.feature;

import com.jetbrains.teamcity.jenkinsbridge.jenkins.JenkinsClient;
import com.jetbrains.teamcity.jenkinsbridge.jenkins.JenkinsClientFactory;
import com.jetbrains.teamcity.jenkinsbridge.model.JenkinsJobParameters;
import com.jetbrains.teamcity.jenkinsbridge.model.JenkinsTriggerResponse;
import com.jetbrains.teamcity.jenkinsbridge.persistence.BuildMirrorStore;
import jetbrains.buildServer.serverSide.BuildPromotion;
import jetbrains.buildServer.serverSide.BuildServerListener;
import jetbrains.buildServer.serverSide.SBuildFeatureDescriptor;
import jetbrains.buildServer.serverSide.SBuildType;
import jetbrains.buildServer.serverSide.SQueuedBuild;
import jetbrains.buildServer.util.EventDispatcher;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.io.IOException;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class JenkinsTriggerOnRunListenerTest {

  @Test
  public void missingQueueIdAbandonsTeamCityFirstAttemptForPollingFallback() throws Exception {
    Fixture fixture = new Fixture();
    when(fixture.client.getJobParameters("job")).thenReturn(JenkinsJobParameters.empty());
    when(fixture.client.triggerBuildWithQueueId(any(), any()))
        .thenReturn(new JenkinsTriggerResponse("", -1L));

    fixture.listener().buildTypeAddedToQueue(fixture.queued);

    InOrder order = inOrder(fixture.queued, fixture.store);
    order.verify(fixture.queued).removeFromQueue(isNull(), contains("could not correlate"));
    order.verify(fixture.store).removePendingTrigger(42L);
  }

  @Test
  public void runtimeFailureDoesNotRunTriggerFailureCleanup() throws Exception {
    Fixture fixture = new Fixture();
    when(fixture.client.getJobParameters("job")).thenReturn(JenkinsJobParameters.empty());
    when(fixture.client.triggerBuildWithQueueId(any(), any()))
        .thenThrow(new NullPointerException("unexpected bridge defect"));

    fixture.listener().buildTypeAddedToQueue(fixture.queued);

    verify(fixture.store).savePendingTrigger(any());
    verify(fixture.store, never()).removePendingTrigger(anyLong());
    verify(fixture.queued, never()).removeFromQueue(any(), any());
  }

  @Test
  public void unexpectedFailureBeforeJenkinsRequestCleansUpTeamCityAttempt() throws Exception {
    Fixture fixture = new Fixture();
    when(fixture.client.getJobParameters("job"))
        .thenThrow(new NullPointerException("unexpected preparation defect"));

    fixture.listener().buildTypeAddedToQueue(fixture.queued);

    verify(fixture.queued).removeFromQueue(isNull(), contains("could not prepare"));
    verify(fixture.store).removePendingTrigger(42L);
  }

  @Test
  public void persistenceFailureBeforeJenkinsRequestCleansUpTeamCityAttempt() throws Exception {
    Fixture fixture = new Fixture();
    doThrow(new IOException("state unavailable"))
        .when(fixture.store).savePendingTrigger(any());

    fixture.listener().buildTypeAddedToQueue(fixture.queued);

    verify(fixture.queued).removeFromQueue(isNull(), contains("could not prepare"));
    verify(fixture.store).removePendingTrigger(42L);
  }

  @Test
  public void concurrentCallbacksForDifferentPromotionsKeepAttemptStateIndependent() throws Exception {
    Fixture first = new Fixture(42L, "first-job");
    Fixture second = new Fixture(43L, "second-job");
    when(first.client.getJobParameters("first-job")).thenReturn(JenkinsJobParameters.empty());
    when(second.client.getJobParameters("second-job")).thenReturn(JenkinsJobParameters.empty());
    when(first.client.triggerBuildWithQueueId(any(), any()))
        .thenReturn(new JenkinsTriggerResponse("/queue/item/101/", 101L));
    when(second.client.triggerBuildWithQueueId(any(), any()))
        .thenReturn(new JenkinsTriggerResponse("/queue/item/202/", 202L));

    BuildServerListener firstListener = first.listener();
    BuildServerListener secondListener = second.listener();
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    Future<?> firstCall = executor.submit(() -> {
      await(start);
      firstListener.buildTypeAddedToQueue(first.queued);
    });
    Future<?> secondCall = executor.submit(() -> {
      await(start);
      secondListener.buildTypeAddedToQueue(second.queued);
    });

    start.countDown();
    firstCall.get();
    secondCall.get();
    executor.shutdownNow();

    verify(first.client).triggerBuildWithQueueId(any(), any());
    verify(second.client).triggerBuildWithQueueId(any(), any());
    verify(first.store, times(2)).savePendingTrigger(any());
    verify(second.store, times(2)).savePendingTrigger(any());
    verify(first.queued, never()).removeFromQueue(any(), any());
    verify(second.queued, never()).removeFromQueue(any(), any());
  }

  private static void await(CountDownLatch latch) {
    try {
      latch.await();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new AssertionError("Test callback was interrupted", e);
    }
  }

  private static class Fixture {
    final EventDispatcher<BuildServerListener> dispatcher = mock(EventDispatcher.class);
    final JenkinsClientFactory clientFactory = mock(JenkinsClientFactory.class);
    final BuildMirrorStore store = mock(BuildMirrorStore.class);
    final JenkinsClient client = mock(JenkinsClient.class);
    final SQueuedBuild queued = mock(SQueuedBuild.class);

    Fixture() throws Exception {
      this(42L, "job");
    }

    Fixture(long promotionId, String job) throws Exception {
      BuildPromotion promotion = mock(BuildPromotion.class);
      SBuildType buildType = mock(SBuildType.class);
      SBuildFeatureDescriptor feature = mock(SBuildFeatureDescriptor.class);

      when(queued.getBuildPromotion()).thenReturn(promotion);
      when(promotion.getId()).thenReturn(promotionId);
      when(promotion.getBuildType()).thenReturn(buildType);
      when(promotion.getCustomParameters()).thenReturn(Collections.<String, String>emptyMap());
      when(promotion.getDefaultParameters()).thenReturn(Collections.<String, String>emptyMap());
      when(buildType.getExternalId()).thenReturn("buildType");
      when(buildType.getBuildFeaturesOfType(BridgeBuildFeatureConstants.TYPE))
          .thenReturn(Collections.singletonList(feature));
      when(feature.getParameters()).thenReturn(Collections.singletonMap(
          BridgeBuildFeatureConstants.PARAM_JENKINS_JOB, job));
      when(clientFactory.forBuildType(buildType)).thenReturn(client);
      when(client.getControllerIdentity()).thenReturn("http://jenkins");
      when(store.getPendingTriggers()).thenReturn(Collections.emptyList());

      new JenkinsTriggerOnRunListener(dispatcher, clientFactory, store);
    }

    BuildServerListener listener() {
      ArgumentCaptor<BuildServerListener> listener = ArgumentCaptor.forClass(BuildServerListener.class);
      verify(dispatcher).addListener(listener.capture());
      return listener.getValue();
    }
  }
}
