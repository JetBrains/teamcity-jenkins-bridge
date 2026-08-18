package com.jetbrains.teamcity.jenkinsbridge.persistence;

import java.io.IOException;

/**
 * Indicates that persisted Jenkins Bridge state cannot be decoded safely.
 *
 * <p>The bridge preserves the stored data when this is raised. It is deliberately distinct from
 * unexpected runtime failures in the storage or bridge code, which must remain visible.</p>
 */
public class BridgeStateCorruptionException extends IOException {
  public BridgeStateCorruptionException(String message) {
    super(message);
  }

  public BridgeStateCorruptionException(String message, Throwable cause) {
    super(message, cause);
  }
}
