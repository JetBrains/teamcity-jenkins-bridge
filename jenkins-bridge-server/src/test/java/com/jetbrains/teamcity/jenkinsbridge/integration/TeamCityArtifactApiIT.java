package com.jetbrains.teamcity.jenkinsbridge.integration;

import com.jetbrains.teamcity.jenkinsbridge.model.JenkinsArtifact;
import com.jetbrains.teamcity.jenkinsbridge.artifactstorage.JenkinsArtifactDownloadSigner;
import com.jetbrains.teamcity.jenkinsbridge.artifactstorage.TeamCitySecureArtifactSigningSecretProvider;
import com.jetbrains.teamcity.jenkinsbridge.artifactstorage.ExpiringSignature;
import com.jetbrains.teamcity.jenkinsbridge.teamcity.TeamCityArtifactPublisher;
import jetbrains.buildServer.ArtifactsConstants;
import org.testng.annotations.Test;
import jetbrains.buildServer.serverSide.BuildAttributes;
import jetbrains.buildServer.serverSide.BuildPromotionEx;
import jetbrains.buildServer.serverSide.MultiNodeLocks;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;

/** Verifies artifact registration through TeamCity's real RunningBuildEx artifact API. */
public class TeamCityArtifactApiIT extends TeamCityIntegrationTestBase {
  @Test
  public void secureArtifactSigningSecretIsSharedBySignerInstances() throws Exception {
    TeamCityBuildFixture build = queueAndStartBuild(
        Collections.singletonMap("jenkins.build.key", "artifact-signing-secret"), Collections.emptyMap());
    MultiNodeLocks locks = myFixture.getSingletonService(MultiNodeLocks.class);
    TeamCitySecureArtifactSigningSecretProvider firstProvider =
        new TeamCitySecureArtifactSigningSecretProvider(locks);
    TeamCitySecureArtifactSigningSecretProvider secondProvider =
        new TeamCitySecureArtifactSigningSecretProvider(locks);
    JenkinsArtifactDownloadSigner firstSigner = new JenkinsArtifactDownloadSigner(firstProvider);
    JenkinsArtifactDownloadSigner secondSigner = new JenkinsArtifactDownloadSigner(secondProvider);

    ExpiringSignature signature = firstSigner.sign(
        build.getRunningBuild().getBuildPromotion(), build.getBuildId(), "job", 1, "artifact.txt");

    assertTrue(secondSigner.isValid(build.getRunningBuild().getBuildPromotion(), build.getBuildId(),
        "job", 1, "artifact.txt", signature.expiry(), signature.signature()));
    build.finish("SUCCESS", new java.util.Date());
  }

  @Test
  public void artifactListIsPersistedWithJenkinsRoutingMetadata() throws Exception {
    LinkedHashMap<String, String> bridgeParameters = new LinkedHashMap<>();
    bridgeParameters.put("jenkins.build.key", "artifact-api-1");
    bridgeParameters.put("jenkins.job", "folder/job");
    bridgeParameters.put("jenkins.build.number", "17");
    bridgeParameters.put("jenkins.connection.id", "local");
    TeamCityBuildFixture build = queueAndStartBuild(bridgeParameters, Collections.emptyMap());
    ((BuildPromotionEx) build.getRunningBuild().getBuildPromotion()).setAttribute(
        BuildAttributes.STORAGE_SETTINGS_REFERENCE, "IT-JENKINS-STORAGE");
    TeamCityArtifactPublisher publisher = new TeamCityArtifactPublisher(build.getLocator());

    publisher.publishArtifactList(build.getPromotionId(), Arrays.asList(
        new JenkinsArtifact("app.jar", "target/app.jar", 100),
        new JenkinsArtifact("report.xml", "reports/report.xml", 50)));
    build.finish("SUCCESS", new java.util.Date());

    java.util.Map<String, String> commonProperties = build.reload()
        .getCustomDataStorage(ArtifactsConstants.EXTERNAL_ARTIFACTS_STORAGE_COMMON_PROPS)
        .getValues();
    assertNotNull(commonProperties);
    assertEquals("folder/job", commonProperties.get("jenkins.job"));
    assertEquals("17", commonProperties.get("jenkins.build.number"));
    assertEquals("local", commonProperties.get("jenkins.connection.id"));

    java.util.Map<String, String> artifacts = build.reload()
        .getCustomDataStorage(ArtifactsConstants.EXTERNAL_ARTIFACTS_STORAGE_LIST)
        .getValues();
    assertNotNull(artifacts);
    assertEquals(2, artifacts.size());
    assertEquals("100", artifacts.get("target/app.jar"));
    assertEquals("50", artifacts.get("reports/report.xml"));
  }

  @Test(expectedExceptions = java.io.IOException.class)
  public void artifactPublishingAfterFinishIsRejected() throws Exception {
    TeamCityBuildFixture build = queueAndStartBuild(
        Collections.singletonMap("jenkins.build.key", "artifact-api-finished"), Collections.emptyMap());
    build.finish("SUCCESS", new java.util.Date());
    new TeamCityArtifactPublisher(build.getLocator())
        .publishArtifactList(build.getPromotionId(),
            Collections.singletonList(new JenkinsArtifact("late.txt", "late.txt", 1)));
  }

}
