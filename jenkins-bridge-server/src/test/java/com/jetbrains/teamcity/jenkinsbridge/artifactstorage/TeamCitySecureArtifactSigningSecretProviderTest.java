package com.jetbrains.teamcity.jenkinsbridge.artifactstorage;

import jetbrains.buildServer.serverSide.BuildPromotion;
import jetbrains.buildServer.serverSide.BuildTypeEx;
import jetbrains.buildServer.serverSide.BuildTypeSettingsEx;
import jetbrains.buildServer.serverSide.CustomDataStorage;
import jetbrains.buildServer.serverSide.MultiNodeLocks;
import jetbrains.buildServer.serverSide.SBuildType;
import jetbrains.buildServer.serverSide.impl.SecureDataStorage;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class TeamCitySecureArtifactSigningSecretProviderTest {
  @Test
  public void createsAndReusesEncryptedTokenForBuildType() throws Exception {
    SBuildType buildType = mock(SBuildType.class, org.mockito.Mockito.withSettings()
        .extraInterfaces(BuildTypeEx.class));
    BuildTypeEx buildTypeEx = (BuildTypeEx) buildType;
    BuildTypeSettingsEx settings = mock(BuildTypeSettingsEx.class);
    when(buildTypeEx.getSettings()).thenReturn(settings);
    BuildPromotion promotion = mock(BuildPromotion.class);
    when(promotion.getBuildType()).thenReturn(buildType);
    when(buildType.getExternalId()).thenReturn("bt");

    SecureDataStorage secureStorage = mock(SecureDataStorage.class);
    when(settings.getSecureDataStorage()).thenReturn(secureStorage);
    when(secureStorage.getOrCreateToken(anyString(), anyString())).thenReturn("encrypted-token");
    when(secureStorage.getSecureValue("encrypted-token", "Jenkins Bridge artifact download signing key"))
        .thenReturn("decrypted-secret");

    MemoryStorage storage = new MemoryStorage();
    when(buildType.getCustomDataStorage("jenkinsBridgeArtifactSigning")).thenReturn(storage);
    MultiNodeLocks locks = mock(MultiNodeLocks.class);
    MultiNodeLocks.Lock lock = mock(MultiNodeLocks.Lock.class);
    when(locks.tryLock(anyString(), anyLong(), anyLong())).thenReturn(lock);

    TeamCitySecureArtifactSigningSecretProvider provider =
        new TeamCitySecureArtifactSigningSecretProvider(locks);

    assertEquals("decrypted-secret", provider.secretFor(promotion));
    assertEquals("encrypted-token", storage.values.get("hmac-secret-v1"));
    assertNotNull(storage.values.get("hmac-secret-v1"));

    assertEquals("decrypted-secret", provider.secretFor(promotion));
    verify(secureStorage).getOrCreateToken(anyString(), anyString());
    verify(lock).close();
  }

  @Test(expected = IllegalStateException.class)
  public void doesNotFallBackToLocalSecretWhenSecureStorageIsUnavailable() {
    SBuildType buildType = mock(SBuildType.class);
    BuildPromotion promotion = mock(BuildPromotion.class);
    when(promotion.getBuildType()).thenReturn(buildType);
    new TeamCitySecureArtifactSigningSecretProvider(mock(MultiNodeLocks.class)).secretFor(promotion);
  }

  private static final class MemoryStorage implements CustomDataStorage {
    private final Map<String, String> values = new HashMap<>();

    @Override public void putValues(Map<String, String> values) { this.values.putAll(values); }
    @Override public void updateValues(Map<String, String> values, java.util.Set<String> keys) { this.values.putAll(values); }
    @Override public void putValuesAndFlush(Map<String, String> values) { putValues(values); }
    @Override public void putValuesAndFlush(Map<String, String> values, ConflictResolution resolution) { putValues(values); }
    @Override public Map<String, String> getValues() { return values; }
    @Override public String getValue(String key) { return values.get(key); }
    @Override public void putValue(String key, String value) { values.put(key, value); }
    @Override public void flush() { }
    @Override public void flush(ConflictResolution resolution) { }
    @Override public void scheduleFlush() { }
    @Override public void clear() { values.clear(); }
    @Override public void dispose() { }
    @Override public void refresh() { }
    @Override public boolean isDirty() { return false; }
    @Override public java.util.Date getDataLoadTime() { return null; }
  }
}
