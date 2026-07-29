package com.jetbrains.teamcity.jenkinsbridge.model;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static com.jetbrains.teamcity.jenkinsbridge.util.Utilities.firstNonBlankString;
import static com.jetbrains.teamcity.jenkinsbridge.util.Utilities.nullToEmpty;

public class JenkinsWfapiNode {
  private final String id;
  private final String name;
  private final String status;
  private final long startTimeMillis;
  private final long durationMillis;
  private final List<String> parentIds;
  private final boolean logNode;

  public JenkinsWfapiNode(
      String id,
      String name,
      String status,
      long startTimeMillis,
      long durationMillis,
      List<String> parentIds,
      boolean logNode
  ) {
    this.id = nullToEmpty(id);
    this.name = nullToEmpty(name);
    this.status = nullToEmpty(status);
    this.startTimeMillis = startTimeMillis;
    this.durationMillis = durationMillis;
    this.parentIds = copy(parentIds);
    this.logNode = logNode;
  }

  public static JenkinsWfapiNode fromStage(JenkinsStage stage) {
    return new JenkinsWfapiNode(
        stage.getId(),
        stage.getName(),
        stage.getStatus(),
        stage.getStartTimeMillis(),
        stage.getDurationMillis(),
        Collections.<String>emptyList(),
        false);
  }

  public static JenkinsWfapiNode fromJson(JsonObject json) {
    return new JenkinsWfapiNode(
        getString(json, "id", ""),
        firstNonBlankString(getString(json, "name", ""), getString(json, "displayName", "")),
        getString(json, "status", ""),
        getLong(json, "startTimeMillis"),
        getLong(json, "durationMillis"),
        parseParentIds(json),
        hasLogLink(json));
  }

  public static List<JenkinsWfapiNode> stageFlowNodesFromJson(JsonObject stageDescribe) {
    JsonArray nodesJson = stageDescribe.getAsJsonArray("stageFlowNodes");
    if (nodesJson == null || nodesJson.size() == 0) {
      return Collections.emptyList();
    }

    List<JenkinsWfapiNode> nodes = new ArrayList<JenkinsWfapiNode>();
    for (JsonElement nodeJson : nodesJson) {
      if (nodeJson != null && nodeJson.isJsonObject()) {
        JenkinsWfapiNode node = fromJson(nodeJson.getAsJsonObject());
        if (node.hasId()) {
          nodes.add(node);
        }
      }
    }
    return nodes;
  }

  public JenkinsWfapiNode merge(JenkinsWfapiNode other) {
    if (other == null) {
      return this;
    }
    Set<String> parents = new LinkedHashSet<String>(getParentIds());
    parents.addAll(other.getParentIds());
    return new JenkinsWfapiNode(
        firstNonBlankString(other.getId(), getId()),
        firstNonBlankString(other.getName(), getName()),
        firstNonBlankString(other.getStatus(), getStatus()),
        other.getStartTimeMillis() > 0 ? other.getStartTimeMillis() : getStartTimeMillis(),
        other.getDurationMillis() > 0 ? other.getDurationMillis() : getDurationMillis(),
        new ArrayList<String>(parents),
        isLogNode() || other.isLogNode());
  }

  public boolean hasId() {
    return getId().length() > 0;
  }

  public String getId() {
    return id;
  }

  public String getName() {
    return name;
  }

  public String getStatus() {
    return status;
  }

  public long getStartTimeMillis() {
    return startTimeMillis;
  }

  public long getDurationMillis() {
    return durationMillis;
  }

  public List<String> getParentIds() {
    return parentIds;
  }

  public boolean isLogNode() {
    return logNode;
  }

  private static List<String> parseParentIds(JsonObject json) {
    JsonArray parents = json.getAsJsonArray("parentNodes");
    if (parents == null || parents.size() == 0) {
      return Collections.emptyList();
    }

    List<String> ids = new ArrayList<String>();
    for (JsonElement parent : parents) {
      String id = "";
      if (parent == null || parent.isJsonNull()) {
        id = "";
      } else if (parent.isJsonPrimitive()) {
        id = parent.getAsString();
      } else if (parent.isJsonObject()) {
        id = getString(parent.getAsJsonObject(), "id", "");
      }
      if (id != null && id.trim().length() > 0) {
        ids.add(id);
      }
    }
    return ids;
  }

  private static boolean hasLogLink(JsonObject node) {
    JsonElement links = node.get("_links");
    if (links == null || !links.isJsonObject()) {
      return false;
    }
    JsonElement log = links.getAsJsonObject().get("log");
    return log != null && log.isJsonObject();
  }

  private static String getString(JsonObject json, String name, String defaultValue) {
    JsonElement element = json.get(name);
    if (element == null || element.isJsonNull()) {
      return defaultValue;
    }
    return element.getAsString();
  }

  private static long getLong(JsonObject json, String name) {
    JsonElement element = json.get(name);
    if (element == null || element.isJsonNull()) {
      return 0L;
    }
    try {
      return element.getAsLong();
    } catch (NumberFormatException e) {
      return 0L;
    }
  }

  private static List<String> copy(List<String> values) {
    if (values == null || values.isEmpty()) {
      return Collections.emptyList();
    }
    return Collections.unmodifiableList(new ArrayList<String>(values));
  }

}