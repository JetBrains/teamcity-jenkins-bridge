package com.jetbrains.teamcity.jenkinsbridge.feature;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import org.jetbrains.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** Encodes the small cross-node hand-off carried by a TeamCity promotion. */
public final class JenkinsTriggerCorrelation {
  public static final int VERSION = 1;
  private static final Gson GSON = new Gson();

  private JenkinsTriggerCorrelation() {
  }

  public static String encode(long promotionId, String nodeId, String triggeredBy, String createdAt) {
    Payload payload = new Payload(VERSION, promotionId, nodeId, triggeredBy, createdAt,
        cause(promotionId, nodeId));
    return Base64.getUrlEncoder().withoutPadding().encodeToString(
        GSON.toJson(payload).getBytes(StandardCharsets.UTF_8));
  }

  @Nullable
  public static Payload decode(@Nullable String encoded) {
    if (encoded == null || encoded.trim().isEmpty()) return null;
    try {
      Payload payload = GSON.fromJson(new String(
          Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8), Payload.class);
      return payload != null && payload.version == VERSION && payload.promotionId >= 0
          && payload.cause != null && !payload.cause.trim().isEmpty() ? payload : null;
    } catch (IllegalArgumentException | JsonSyntaxException ignored) {
      return null;
    }
  }

  public static String cause(long promotionId, String nodeId) {
    return "Jenkins Bridge: TeamCity promotion " + promotionId + " (node "
        + (nodeId == null || nodeId.trim().isEmpty() ? "unknown" : nodeId) + ")";
  }

  public static final class Payload {
    private int version;
    private long promotionId;
    private String nodeId;
    private String triggeredBy;
    private String createdAt;
    private String cause;

    private Payload(int version, long promotionId, String nodeId, String triggeredBy, String createdAt,
                    String cause) {
      this.version = version;
      this.promotionId = promotionId;
      this.nodeId = nodeId;
      this.triggeredBy = triggeredBy;
      this.createdAt = createdAt;
      this.cause = cause;
    }

    public long getPromotionId() { return promotionId; }
    public String getNodeId() { return nodeId; }
    public String getTriggeredBy() { return triggeredBy; }
    public String getCreatedAt() { return createdAt; }
    public String getCause() { return cause; }
  }
}
