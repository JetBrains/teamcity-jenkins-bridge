package com.jetbrains.teamcity.jenkinsbridge.artifactstorage;

import com.intellij.openapi.diagnostic.Logger;
import jetbrains.buildServer.serverSide.BuildPromotion;
import jetbrains.buildServer.serverSide.BuildTypeEx;
import jetbrains.buildServer.serverSide.CustomDataStorage;
import jetbrains.buildServer.serverSide.MultiNodeLocks;
import jetbrains.buildServer.serverSide.SBuildType;
import jetbrains.buildServer.serverSide.BuildTypeSettingsEx;
import jetbrains.buildServer.serverSide.impl.SecureDataStorage;
import org.jetbrains.annotations.NotNull;

import java.security.SecureRandom;
import java.util.Base64;

/**
 * Stores an encrypted artifact-signing secret in shared build-type data.
 *
 * <p>{@link SecureDataStorage} performs the TeamCity encryption. The encrypted token is persisted
 * separately because SecureDataStorage is an encryption facade, not a general-purpose store.</p>
 *
 * <p>This class is the only bridge boundary that depends on TeamCity's internal secure-storage
 * API. Keep that dependency here and verify it with a real TeamCity integration test.</p>
 */
public class TeamCitySecureArtifactSigningSecretProvider implements JenkinsArtifactSigningSecretProvider {
  private static final Logger LOG = Logger.getInstance(TeamCitySecureArtifactSigningSecretProvider.class.getName());
  private static final String STORAGE_NAME = "jenkinsBridgeArtifactSigning";
  private static final String SECRET_TOKEN_KEY = "hmac-secret-v1";
  private static final String LOCK_TYPE = "jenkinsBridgeArtifactSigningSecret";
  private static final long LOCK_TIMEOUT_MILLIS = 5_000L;
  private static final String CONTEXT = "Jenkins Bridge artifact download signing key";

  private final MultiNodeLocks myMultiNodeLocks;
  private final SecureRandom mySecureRandom;

  public TeamCitySecureArtifactSigningSecretProvider(@NotNull MultiNodeLocks multiNodeLocks) {
    myMultiNodeLocks = multiNodeLocks;
    mySecureRandom = new SecureRandom();
  }

  @NotNull
  @Override
  public String secretFor(@NotNull BuildPromotion promotion) {
    SBuildType buildType = promotion.getBuildType();
    if (!(buildType instanceof BuildTypeEx)) {
      throw new IllegalStateException("TeamCity build type does not expose secure artifact storage");
    }

    BuildTypeSettingsEx settings = ((BuildTypeEx) buildType).getSettings();
    SecureDataStorage secureDataStorage = settings.getSecureDataStorage();
    if (secureDataStorage == null) {
      throw new IllegalStateException("TeamCity secure artifact storage is unavailable");
    }
    CustomDataStorage storage = buildType.getCustomDataStorage(STORAGE_NAME);
    String token = storage.getValue(SECRET_TOKEN_KEY);
    if (token != null && !token.isEmpty()) {
      return decrypt(secureDataStorage, token);
    }

    MultiNodeLocks.Lock lock;
    try {
      lock = myMultiNodeLocks.tryLock(LOCK_TYPE, lockId(buildType), LOCK_TIMEOUT_MILLIS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted while initializing artifact signing secret", e);
    }
    if (lock == null) {
      throw new IllegalStateException("Could not acquire lock while initializing artifact signing secret");
    }

    try {
      storage.refresh();
      token = storage.getValue(SECRET_TOKEN_KEY);
      if (token == null || token.isEmpty()) {
        String secret = newSecret();
        token = secureDataStorage.getOrCreateToken(secret, CONTEXT);
        storage.putValue(SECRET_TOKEN_KEY, token);
        storage.flush();
        LOG.info("Jenkins Bridge initialized the shared artifact signing secret for build type "
            + buildType.getExternalId());
      }
      return decrypt(secureDataStorage, token);
    } finally {
      lock.close();
    }
  }

  @NotNull
  private static String decrypt(@NotNull SecureDataStorage secureDataStorage, @NotNull String token) {
    String secret = secureDataStorage.getSecureValue(token, CONTEXT);
    if (secret == null || secret.isEmpty()) {
      throw new IllegalStateException("TeamCity artifact signing secret could not be decrypted");
    }
    return secret;
  }

  @NotNull
  private String newSecret() {
    byte[] bytes = new byte[32];
    mySecureRandom.nextBytes(bytes);
    return Base64.getEncoder().encodeToString(bytes);
  }

  private static long lockId(@NotNull SBuildType buildType) {
    return Integer.toUnsignedLong(buildType.getExternalId().hashCode());
  }
}
