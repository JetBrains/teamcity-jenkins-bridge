package com.jetbrains.teamcity.jenkinsbridge.artifactstorage;

import org.junit.Test;

import static org.junit.Assert.*;

public class JenkinsArtifactDownloadSignerTest {
  private final JenkinsArtifactDownloadSigner signer = new JenkinsArtifactDownloadSigner();

  @Test
  public void signatureValidatesForTheExactParametersItWasSignedFor() {
    ExpiringSignature signed = signer.sign("job", 7, "target/app.jar");

    assertTrue(signer.isValid("job", 7, "target/app.jar", signed.expiry(), signed.signature()));
  }

  @Test
  public void signatureIsRejectedWhenJobDiffers() {
    ExpiringSignature signed = signer.sign("job", 7, "target/app.jar");

    assertFalse(signer.isValid("other-job", 7, "target/app.jar", signed.expiry(), signed.signature()));
  }

  @Test
  public void signatureIsRejectedWhenBuildNumberDiffers() {
    ExpiringSignature signed = signer.sign("job", 7, "target/app.jar");

    assertFalse(signer.isValid("job", 8, "target/app.jar", signed.expiry(), signed.signature()));
  }

  @Test
  public void signatureIsRejectedWhenPathDiffers() {
    ExpiringSignature signed = signer.sign("job", 7, "target/app.jar");

    assertFalse(signer.isValid("job", 7, "target/other.jar", signed.expiry(), signed.signature()));
  }

  @Test
  public void signatureIsRejectedAfterExpiry() {
    ExpiringSignature signed = signer.sign("job", 7, "target/app.jar");
    assertFalse(signer.isValid("job", 7, "target/app.jar", 0L, signed.signature()));
  }

  @Test
  public void signatureIsRejectedWhenExpiryIsTamperedWith() {
    ExpiringSignature signed = signer.sign("job", 7, "target/app.jar");

    assertFalse(signer.isValid("job", 7, "target/app.jar", signed.expiry() + 1, signed.signature()));
  }

  @Test
  public void differentFieldBoundariesDoNotProduceTheSameSignature() {
    ExpiringSignature signatureA = signer.sign("a", 1, "b/c");
    ExpiringSignature signatureB = signer.sign("a/b", 1, "c");

    assertNotEquals(signatureA.signature(), signatureB.signature());
  }
}
