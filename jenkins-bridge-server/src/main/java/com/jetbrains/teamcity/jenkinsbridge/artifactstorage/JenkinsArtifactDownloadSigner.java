package com.jetbrains.teamcity.jenkinsbridge.artifactstorage;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.TimeUnit;
import jetbrains.buildServer.serverSide.BuildPromotion;
import org.jetbrains.annotations.NotNull;

/**
 * Signs and validates short-lived tokens that authorize a single Jenkins artifact download without
 * exposing Jenkins credentials to the client.
 */
public class JenkinsArtifactDownloadSigner {
  private static final Charset OUR_CHARSET = StandardCharsets.UTF_8;
  private static final String HMAC_ALGORITHM = "HmacSHA256";
  private static final long TOKEN_TTL_MILLIS = TimeUnit.MINUTES.toMillis(5);

  private final JenkinsArtifactSigningSecretProvider secretProvider;

  public JenkinsArtifactDownloadSigner(@NotNull JenkinsArtifactSigningSecretProvider secretProvider) {
    this.secretProvider = secretProvider;
  }

  /**
   * Returns the expiry timestamp (epoch millis) for a freshly issued token, {@link #TOKEN_TTL_MILLIS}
   * from now.
   */
  private long issueExpiry() {
    return System.currentTimeMillis() + TOKEN_TTL_MILLIS;
  }

  /**
   * Computes the signature for the given artifact coordinates. Callers pass this alongside
   * the parameters it covers. {@link #isValid} recomputes it and compares.
   */
  public ExpiringSignature sign(@NotNull BuildPromotion promotion, long teamCityBuildId, String jobName,
                                int buildNumber, String relativePath) {
    long expiry = issueExpiry();
    return sign(promotion, teamCityBuildId, jobName, buildNumber, relativePath, expiry);
  }

  private ExpiringSignature sign(@NotNull BuildPromotion promotion, long teamCityBuildId, String jobName,
                                 int buildNumber, String relativePath, long expiry) {
    return new ExpiringSignature(
        hmac(secretProvider.secretFor(promotion), safePayloadFrom(teamCityBuildId, jobName, buildNumber, relativePath, expiry)), expiry);
  }

  /**
   * Verifies that {@code signature} matches the given parameters and that {@code expiry} has not
   * passed yet.
   */
  public boolean isValid(@NotNull BuildPromotion promotion, long teamCityBuildId, String jobName, int buildNumber,
                         String relativePath, long expiry, String signature) {
    if (expiry < System.currentTimeMillis()) {
      return false;
    }
    if (signature == null) {
      return false;
    }
    ExpiringSignature expected = sign(promotion, teamCityBuildId, jobName, buildNumber, relativePath, expiry);
    return MessageDigest.isEqual(
        expected.signature().getBytes(OUR_CHARSET),
        signature.getBytes(OUR_CHARSET));
  }

  /**
   * Length-prefixes the variable-length fields (job name, relative path) so that, e.g., job="a",
   * path="b/c" cannot be signed identically to job="a/b", path="c".
   */
  private String safePayloadFrom(long teamCityBuildId, String jobName, int buildNumber, String relativePath,
                                 long expiry) {
    return teamCityBuildId
        + "|" + jobName.length() + ":" + jobName
        + "|" + buildNumber
        + "|" + relativePath.length() + ":" + relativePath
        + "|" + expiry;
  }

  private String hmac(String secret, String payload) {
    try {
      Mac mac = Mac.getInstance(HMAC_ALGORITHM);
      mac.init(new SecretKeySpec(secret.getBytes(OUR_CHARSET), HMAC_ALGORITHM));
      return toHex(mac.doFinal(payload.getBytes(OUR_CHARSET)));
    } catch (NoSuchAlgorithmException | InvalidKeyException e) {
      throw new IllegalStateException("Failed to compute HMAC signature", e);
    }
  }

  private static String toHex(byte[] bytes) {
    StringBuilder result = new StringBuilder(bytes.length * 2);
    for (byte b : bytes) {
      result.append(Character.forDigit((b >> 4) & 0xF, 16));
      result.append(Character.forDigit(b & 0xF, 16));
    }
    return result.toString();
  }
}
