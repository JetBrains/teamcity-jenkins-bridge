package com.jetbrains.teamcity.jenkinsbridge.artifactstorage;

import org.junit.Test;

import static org.junit.Assert.*;

public class JenkinsArtifactDownloadSignerTest {
  private static final long BUILD_ID = 123L;

  private final JenkinsArtifactDownloadSigner signer = new JenkinsArtifactDownloadSigner();

  @Test
  public void signatureValidatesForTheExactParametersItWasSignedFor() {
    ExpiringSignature signed = signer.sign(BUILD_ID, "job", 7, "target/app.jar");

    assertTrue(signer.isValid(BUILD_ID, "job", 7, "target/app.jar", signed.expiry(), signed.signature()));
  }

  @Test
  public void signatureIsRejectedWhenJobDiffers() {
    ExpiringSignature signed = signer.sign(BUILD_ID, "job", 7, "target/app.jar");

    assertFalse(signer.isValid(BUILD_ID, "other-job", 7, "target/app.jar", signed.expiry(), signed.signature()));
  }

  @Test
  public void signatureIsRejectedWhenBuildNumberDiffers() {
    ExpiringSignature signed = signer.sign(BUILD_ID, "job", 7, "target/app.jar");

    assertFalse(signer.isValid(BUILD_ID, "job", 8, "target/app.jar", signed.expiry(), signed.signature()));
  }

  @Test
  public void signatureIsRejectedWhenTeamCityBuildIdDiffers() {
    ExpiringSignature signed = signer.sign(BUILD_ID, "job", 7, "target/app.jar");

    assertFalse(signer.isValid(BUILD_ID + 1, "job", 7, "target/app.jar", signed.expiry(), signed.signature()));
  }

  @Test
  public void signatureIsRejectedWhenPathDiffers() {
    ExpiringSignature signed = signer.sign(BUILD_ID, "job", 7, "target/app.jar");

    assertFalse(signer.isValid(BUILD_ID, "job", 7, "target/other.jar", signed.expiry(), signed.signature()));
  }

  @Test
  public void signatureIsRejectedAfterExpiry() {
    ExpiringSignature signed = signer.sign(BUILD_ID, "job", 7, "target/app.jar");
    assertFalse(signer.isValid(BUILD_ID, "job", 7, "target/app.jar", 0L, signed.signature()));
  }

  @Test
  public void signatureIsRejectedWhenExpiryIsTamperedWith() {
    ExpiringSignature signed = signer.sign(BUILD_ID, "job", 7, "target/app.jar");

    assertFalse(signer.isValid(BUILD_ID, "job", 7, "target/app.jar", signed.expiry() + 1, signed.signature()));
  }

  @Test
  public void differentFieldBoundariesDoNotProduceTheSameSignature() {
    ExpiringSignature signatureA = signer.sign(BUILD_ID, "a", 1, "b/c");
    ExpiringSignature signatureB = signer.sign(BUILD_ID, "a/b", 1, "c");

    assertNotEquals(signatureA.signature(), signatureB.signature());
  }
}
