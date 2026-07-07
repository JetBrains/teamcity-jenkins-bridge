package com.jetbrains.teamcity.jenkinsbridge.jenkins;

import com.intellij.openapi.diagnostic.Logger;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.jetbrains.teamcity.jenkinsbridge.http.BridgeHttpClient;
import com.jetbrains.teamcity.jenkinsbridge.http.BridgeHttpException;
import com.jetbrains.teamcity.jenkinsbridge.http.BridgeHttpResponse;
import com.jetbrains.teamcity.jenkinsbridge.model.*;
import com.jetbrains.teamcity.jenkinsbridge.settings.JenkinsBridgeSettings;
import com.jetbrains.teamcity.jenkinsbridge.settings.JenkinsBridgeSettingsProvider;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

public class JenkinsClient {
  // Jenkins embeds invisible ConsoleNote annotations in the raw console as an ANSI "conceal" block:
  // ESC[8m + "ha:" + base64 payload + ESC[0m. Jenkins' own UI hides them; the bridge must strip them
  // so they do not leak into the mirrored TeamCity log as base64 garbage. A note never spans a line,
  // so the (line-bounded) non-greedy match is safe; a note split across two progressive fetches is a
  // rare edge that can leak one partial note at the boundary.
  private static final Pattern CONSOLE_NOTE = Pattern.compile("\\[8m.*?\\[0m");
  private static final Logger LOG = Logger.getInstance(JenkinsClient.class.getName());

  private final JenkinsBridgeSettingsProvider settingsProvider;
  private final BridgeHttpClient httpClient;
  private final JsonParser jsonParser = new JsonParser();

  public JenkinsClient(JenkinsBridgeSettingsProvider settingsProvider, BridgeHttpClient httpClient) {
    this.settingsProvider = settingsProvider;
    this.httpClient = httpClient;
  }

  /**
   * Returns the numbers of the (up to 100) most recent builds. Jenkins caps the {@code builds}
   * collection at the 100 newest builds to avoid loading the whole history; callers that may have
   * fallen further behind can escalate to {@link #getAllBuildNumbers(String)}.
   */
  public List<Integer> getBuildNumbers(String jobName) throws BridgeHttpException {
    return fetchBuildNumbers(jobName, "builds");
  }

  /**
   * Returns the numbers of all builds via Jenkins' {@code allBuilds} collection. This is complete
   * but can be expensive on jobs with a long history, so use it only as a fallback when a gap is
   * detected below the {@link #getBuildNumbers(String)} window.
   */
  public List<Integer> getAllBuildNumbers(String jobName) throws BridgeHttpException {
    return fetchBuildNumbers(jobName, "allBuilds");
  }

  /**
   * Returns the (up to 100) most recent builds with enough metadata to identify a Jenkins run even
   * when build numbers are reused after history deletion/reset.
   */
  public List<JenkinsBuildInfo> getBuilds(String jobName) throws BridgeHttpException {
    return fetchBuildInfos(jobName, "builds");
  }

  /**
   * Returns all builds with identity metadata. This is complete but potentially expensive, so
   * callers should use it only after detecting that the recent-build window is insufficient.
   */
  public List<JenkinsBuildInfo> getAllBuilds(String jobName) throws BridgeHttpException {
    return fetchBuildInfos(jobName, "allBuilds");
  }

  private List<Integer> fetchBuildNumbers(String jobName, String collection) throws BridgeHttpException {
    JenkinsBridgeSettings settings = settingsProvider.load();
    String tree = collection + "[number]";
    String url = settings.getJenkinsUrl()
        + jenkinsJobPath(jobName)
        + "/api/json?tree="
        + encodeQueryValue(tree);

    String response = httpClient.get(url, settings.getJenkinsUser(), settings.getJenkinsToken(), "application/json");
    JsonObject root = jsonParser.parse(response).getAsJsonObject();
    JsonArray builds = root.getAsJsonArray(collection);
    List<Integer> numbers = new ArrayList<Integer>();
    if (builds == null) {
      return numbers;
    }

    for (JsonElement build : builds) {
      if (build == null || !build.isJsonObject()) {
        continue;
      }
      JsonElement number = build.getAsJsonObject().get("number");
      if (number != null && !number.isJsonNull()) {
        numbers.add(number.getAsInt());
      }
    }

    return numbers;
  }

  private List<JenkinsBuildInfo> fetchBuildInfos(String jobName, String collection) throws BridgeHttpException {
    JenkinsBridgeSettings settings = settingsProvider.load();
    String tree = collection + "[number,timestamp,url]";
    String url = settings.getJenkinsUrl()
        + jenkinsJobPath(jobName)
        + "/api/json?tree="
        + encodeQueryValue(tree);

    String response = httpClient.get(url, settings.getJenkinsUser(), settings.getJenkinsToken(), "application/json");
    JsonObject root = jsonParser.parse(response).getAsJsonObject();
    JsonArray builds = root.getAsJsonArray(collection);
    List<JenkinsBuildInfo> result = new ArrayList<JenkinsBuildInfo>();
    if (builds == null) {
      return result;
    }

    for (JsonElement build : builds) {
      if (build != null && build.isJsonObject()) {
        result.add(JenkinsBuildInfo.fromJson(build.getAsJsonObject()));
      }
    }

    return result;
  }

  public JenkinsBuildInfo getBuildInfo(String jobName, int buildNumber) throws BridgeHttpException {
    JenkinsBridgeSettings settings = settingsProvider.load();
    String tree = "number,building,result,timestamp,duration,estimatedDuration,url";
    String url = settings.getJenkinsUrl()
        + jenkinsJobPath(jobName)
        + "/"
        + buildNumber
        + "/api/json?tree="
        + encodeQueryValue(tree);

    String response = httpClient.get(url, settings.getJenkinsUser(), settings.getJenkinsToken(), "application/json");
    return JenkinsBuildInfo.fromJson(jsonParser.parse(response).getAsJsonObject());
  }

  /**
   * Fetches only the console output produced since {@code start} (a byte offset) using Jenkins'
   * progressive log API, instead of re-downloading the whole console each poll.
   */
  public JenkinsLogChunk getProgressiveLog(String jobName, int buildNumber, long start) throws BridgeHttpException {
    JenkinsBridgeSettings settings = settingsProvider.load();
    long safeStart = Math.max(0L, start);
    String url = settings.getJenkinsUrl()
        + jenkinsJobPath(jobName)
        + "/"
        + buildNumber
        + "/logText/progressiveText?start="
        + safeStart;

    BridgeHttpResponse response = httpClient.getResponse(
        url, settings.getJenkinsUser(), settings.getJenkinsToken(), "text/plain");

    String rawBody = response.getBody();
    // X-Text-Size is the new total byte size; fall back to advancing by the raw body length if
    // absent. Offsets track the raw log, so stripping console notes below must not change nextStart.
    long nextStart = parseLong(response.getHeader("X-Text-Size"), safeStart + rawBody.length());
    boolean hasMoreData = "true".equalsIgnoreCase(response.getHeader("X-More-Data"));
    return new JenkinsLogChunk(stripConsoleNotes(rawBody), nextStart, hasMoreData);
  }

  private static long parseLong(String value, long defaultValue) {
    if (value == null) {
      return defaultValue;
    }
    try {
      return Long.parseLong(value.trim());
    } catch (NumberFormatException e) {
      return defaultValue;
    }
  }

  /**
   * Removes Jenkins ConsoleNote annotations (ESC[8m...ESC[0m conceal blocks) from console text so
   * they don't leak into the mirrored TeamCity log. Visible for testing.
   */
  static String stripConsoleNotes(String text) {
    if (text == null || text.isEmpty()) {
      return text == null ? "" : text;
    }
    return CONSOLE_NOTE.matcher(text).replaceAll("");
  }

  public JenkinsTestReport getTestReport(String jobName, int buildNumber) throws BridgeHttpException {
    JenkinsBridgeSettings settings = settingsProvider.load();
    String tree = "suites[name,cases[className,name,status,duration,errorDetails,errorStackTrace,skippedMessage,stdout,stderr]]";
    String url = settings.getJenkinsUrl()
        + jenkinsJobPath(jobName)
        + "/"
        + buildNumber
        + "/testReport/api/json?tree="
        + encodeQueryValue(tree);

    try {
      String response = httpClient.get(url, settings.getJenkinsUser(), settings.getJenkinsToken(), "application/json");
      return JenkinsTestReport.fromJson(jsonParser.parse(response).getAsJsonObject());
    } catch (BridgeHttpException e) {
      if (e.getStatusCode() == 404) {
        return JenkinsTestReport.empty();
      }
      throw e;
    }
  }

  public JenkinsArtifacts getArtifacts(String jobName, int buildNumber) throws BridgeHttpException {
    JenkinsBridgeSettings settings = settingsProvider.load();
    String tree = "artifacts[fileName,relativePath]";
    String buildUrl = settings.getJenkinsUrl()
        + jenkinsJobPath(jobName)
        + "/"
        + buildNumber;
    String url = buildUrl
        + "/api/json?tree="
        + encodeQueryValue(tree);

    try {
      String response = httpClient.get(url, settings.getJenkinsUser(), settings.getJenkinsToken(), "application/json");
      JenkinsArtifacts artifacts = JenkinsArtifacts.fromJson(JsonParser.parseString(response).getAsJsonObject());
      List<JenkinsArtifact> sizedArtifacts = new ArrayList<>();
      for (JenkinsArtifact artifact : artifacts.getArtifacts()) {
        String artifactUrl = buildUrl + "/artifact/" + artifact.getRelativePath();
        // TODO: HEAD requests may be parallelized
        // TODO: Alternatively, the /artifact HTML page can be scraped to get the sizes
        int size = 0;
        try {
          String sizeString = httpClient.head(artifactUrl, settings.getJenkinsUser(), settings.getJenkinsToken(), "*/*").getHeader("Content-Length");
          size = Integer.parseInt(sizeString);
        } catch (BridgeHttpException e) {
          LOG.warn("Failed to get artifact size for " + artifactUrl, e);
        }
        sizedArtifacts.add(new JenkinsArtifact(artifact.getFileName(), artifact.getRelativePath(), size));
      }
      return new JenkinsArtifacts(sizedArtifacts);
    } catch (BridgeHttpException e) {
      if (e.getStatusCode() == 404) {
        return JenkinsArtifacts.empty();
      }
      throw e;
    }
  }

  /**
   * Builds the absolute Jenkins download URL for a single build artifact:
   * {@code <jenkinsUrl>/job/<job>/<buildNumber>/artifact/<relativePath>}.
   */
  public String artifactUrl(String jobName, int buildNumber, String relativePath) {
    JenkinsBridgeSettings settings = settingsProvider.load();
    return settings.getJenkinsUrl()
        + jenkinsJobPath(jobName)
        + "/"
        + buildNumber
        + "/artifact/"
        + encodeRelativePath(relativePath);
  }

  public void streamArtifact(
      String jobName,
      int buildNumber,
      String relativePath,
      BridgeHttpClient.StreamHandler handler
  ) throws BridgeHttpException {
    JenkinsBridgeSettings settings = settingsProvider.load();
    String url = artifactUrl(jobName, buildNumber, relativePath);
    httpClient.getStream(url, settings.getJenkinsUser(), settings.getJenkinsToken(), "*/*", handler);
  }

  /**
   * Lists the top-level jobs at {@code folderPath} (blank = Jenkins root). Does not recurse into
   * folders or expand multibranch projects; folder/multibranch entries are returned but marked
   * non-importable. Reuses the global Jenkins connection.
   */
  public List<JenkinsJob> listJobs(String folderPath) throws BridgeHttpException {
    JenkinsBridgeSettings settings = settingsProvider.load();
    String tree = "jobs[name,fullName,url,_class,buildable,color]";
    String url = settings.getJenkinsUrl()
        + jenkinsJobPath(folderPath == null ? "" : folderPath)
        + "/api/json?tree="
        + encodeQueryValue(tree);

    String response = httpClient.get(url, settings.getJenkinsUser(), settings.getJenkinsToken(), "application/json");
    JsonObject root = jsonParser.parse(response).getAsJsonObject();
    JsonArray jobs = root.getAsJsonArray("jobs");
    List<JenkinsJob> result = new ArrayList<JenkinsJob>();
    if (jobs == null) {
      return result;
    }
    for (JsonElement element : jobs) {
      if (element != null && element.isJsonObject()) {
        result.add(JenkinsJob.fromJson(element.getAsJsonObject()));
      }
    }
    return result;
  }

  /**
   * Absolute Jenkins job page URL for {@code fullName}, derived from the global base URL.
   */
  public String jobUrl(String fullName) {
    return settingsProvider.load().getJenkinsUrl()
        + jenkinsJobPath(fullName == null ? "" : fullName)
        + "/";
  }

  /**
   * Fetches the Pipeline stage list via Jenkins' {@code wfapi/describe} (Pipeline Stage View
   * plugin). A {@code 404} means the build is not a Pipeline, or the plugin is absent: callers
   * fall back to the flat console log.
   */
  public JenkinsStages getStages(String jobName, int buildNumber) throws BridgeHttpException {
    JenkinsBridgeSettings settings = settingsProvider.load();
    String url = settings.getJenkinsUrl()
        + jenkinsJobPath(jobName)
        + "/"
        + buildNumber
        + "/wfapi/describe";

    try {
      String response = httpClient.get(url, settings.getJenkinsUser(), settings.getJenkinsToken(), "application/json");
      return JenkinsStages.fromJson(jsonParser.parse(response).getAsJsonObject());
    } catch (BridgeHttpException e) {
      if (e.getStatusCode() == 404) {
        return JenkinsStages.notPipeline();
      }
      throw e;
    }
  }

  public JenkinsPipelineGraph getPipelineGraph(String jobName, int buildNumber) throws BridgeHttpException {
    return getPipelineGraph(jobName, buildNumber, jobName + "#" + buildNumber);
  }

  public JenkinsPipelineGraph getPipelineGraph(String jobName, int buildNumber, String flowIdPrefix)
      throws BridgeHttpException {
    JenkinsPipelineGraph blueOceanGraph = getBlueOceanPipelineGraph(jobName, buildNumber, flowIdPrefix);
    if (isUsableForNativeChain(blueOceanGraph)) {
      return blueOceanGraph;
    }

    JenkinsPipelineGraph wfapiGraph = getWfapiPipelineGraph(
        jobName, buildNumber, flowIdPrefix, blueOceanGraph.getDiagnostics());
    return wfapiGraph.isPipeline() ? wfapiGraph : blueOceanGraph;
  }

  private boolean isUsableForNativeChain(JenkinsPipelineGraph graph) {
    return graph != null
        && graph.isPipeline()
        && graph.getConfidence() == GraphConfidence.EXPLICIT;
  }

  private JenkinsPipelineGraph getWfapiPipelineGraph(
      String jobName,
      int buildNumber,
      String flowIdPrefix,
      List<String> previousDiagnostics
  ) throws BridgeHttpException {
    JenkinsBridgeSettings settings = settingsProvider.load();
    String url = settings.getJenkinsUrl()
        + jenkinsJobPath(jobName)
        + "/"
        + buildNumber
        + "/wfapi/describe";

    List<String> diagnostics = new ArrayList<String>();
    if (previousDiagnostics != null) {
      diagnostics.addAll(previousDiagnostics);
    }
    JsonObject root;
    JenkinsStages stages;
    try {
      String response = httpClient.get(url, settings.getJenkinsUser(), settings.getJenkinsToken(), "application/json");
      root = jsonParser.parse(response).getAsJsonObject();
      stages = JenkinsStages.fromJson(root);
    } catch (BridgeHttpException e) {
      if (e.getStatusCode() == 404) {
        diagnostics.add("WFAPI describe endpoint returned 404");
        return JenkinsPipelineGraph.unavailable(diagnostics);
      }
      throw e;
    }

    List<JenkinsWfapiNode> rawNodes = new ArrayList<JenkinsWfapiNode>();
    for (JenkinsStage stage : stages.getStages()) {
      JsonObject describe = getStageNodeDescribe(jobName, buildNumber, stage.getId(), diagnostics);
      if (describe == null) {
        continue;
      }

      JenkinsWfapiNode rootNode = JenkinsWfapiNode.fromJson(describe);
      if (rootNode.hasId()) {
        rawNodes.add(rootNode);
      }
      rawNodes.addAll(JenkinsWfapiNode.stageFlowNodesFromJson(describe));
    }

    return JenkinsPipelineGraph.fromWfapi(flowIdPrefix, stages, rawNodes, diagnostics);
  }

  private JenkinsPipelineGraph getBlueOceanPipelineGraph(
      String jobName,
      int buildNumber,
      String flowIdPrefix
  ) throws BridgeHttpException {
    List<String> diagnostics = new ArrayList<String>();

    JenkinsBridgeSettings settings = settingsProvider.load();
    String url = settings.getJenkinsUrl()
        + "/blue/rest/organizations/jenkins"
        + blueOceanPipelinePath(jobName)
        + "/runs/"
        + buildNumber
        + "/nodes/";

    JsonArray nodes;
    try {
      String response = httpClient.get(url, settings.getJenkinsUser(), settings.getJenkinsToken(), "application/json");
      JsonElement parsed = jsonParser.parse(response);
      if (parsed.isJsonArray()) {
        nodes = parsed.getAsJsonArray();
      } else if (parsed.isJsonObject() && parsed.getAsJsonObject().get("nodes") != null) {
        nodes = parsed.getAsJsonObject().getAsJsonArray("nodes");
      } else {
        diagnostics.add("Blue Ocean nodes endpoint returned an unsupported JSON shape");
        return JenkinsPipelineGraph.unavailable(diagnostics);
      }
    } catch (BridgeHttpException e) {
      if (e.getStatusCode() == 404) {
        diagnostics.add("Blue Ocean nodes endpoint returned 404");
        return JenkinsPipelineGraph.unavailable(diagnostics);
      }
      throw e;
    }

    return fromBlueOceanNodes(flowIdPrefix, nodes, diagnostics);
  }

  private JenkinsPipelineGraph fromBlueOceanNodes(
      String flowIdPrefix,
      JsonArray blueNodes,
      List<String> diagnostics
  ) {
    Map<String, JsonObject> rawById = new LinkedHashMap<String, JsonObject>();
    Map<String, Set<String>> childIdsById = new LinkedHashMap<String, Set<String>>();
    Map<String, Set<String>> parentIdsById = new LinkedHashMap<String, Set<String>>();

    for (JsonElement element : blueNodes) {
      if (element == null || !element.isJsonObject()) {
        continue;
      }
      JsonObject node = element.getAsJsonObject();
      String id = stringValue(node, "id");
      if (id.length() == 0 || isBlueOceanStepNode(node)) {
        continue;
      }
      rawById.put(id, node);
      childIdsById.put(id, new LinkedHashSet<String>());
      parentIdsById.put(id, new LinkedHashSet<String>());
    }

    if (rawById.isEmpty()) {
      diagnostics.add("Blue Ocean returned no graph nodes");
      return JenkinsPipelineGraph.unavailable(diagnostics);
    }

    for (Map.Entry<String, JsonObject> entry : rawById.entrySet()) {
      String parentId = entry.getKey();
      JsonArray edges = entry.getValue().getAsJsonArray("edges");
      if (edges == null) {
        continue;
      }
      for (JsonElement edge : edges) {
        String childId = edgeId(edge);
        if (childId.length() == 0 || !rawById.containsKey(childId)) {
          continue;
        }
        childIdsById.get(parentId).add(childId);
        parentIdsById.get(childId).add(parentId);
      }
    }

    boolean hasEdge = false;
    for (Set<String> children : childIdsById.values()) {
      if (!children.isEmpty()) {
        hasEdge = true;
        break;
      }
    }
    if (!hasEdge && rawById.size() > 1) {
      diagnostics.add("Blue Ocean returned nodes without usable edges");
      return JenkinsPipelineGraph.unavailable(diagnostics);
    }

    List<String> orderedIds = new ArrayList<String>(rawById.keySet());
    List<JenkinsPipelineGraphNode> graphNodes = new ArrayList<JenkinsPipelineGraphNode>();
    for (String id : orderedIds) {
      JsonObject raw = rawById.get(id);
      graphNodes.add(new JenkinsPipelineGraphNode(
          id,
          nullToEmpty(flowIdPrefix) + ":" + id,
          displayName(raw),
          blueOceanStatus(raw),
          blueOceanStartTimeMillis(raw),
          longValue(raw, "durationMillis", longValue(raw, "durationInMillis", 0L)),
          inOrder(parentIdsById.get(id), orderedIds),
          inOrder(childIdsById.get(id), orderedIds),
          new ArrayList<String>()));
    }

    diagnostics.add("Blue Ocean graph used for Pipeline topology");
    return JenkinsPipelineGraph.explicit(JenkinsPipelineGraph.SOURCE_BLUE_OCEAN, graphNodes, diagnostics);
  }

  private boolean isBlueOceanStepNode(JsonObject node) {
    String type = stringValue(node, "type");
    return "STEP".equalsIgnoreCase(type);
  }

  private String edgeId(JsonElement edge) {
    if (edge == null || edge.isJsonNull()) {
      return "";
    }
    if (edge.isJsonPrimitive()) {
      return edge.getAsString();
    }
    if (edge.isJsonObject()) {
      return stringValue(edge.getAsJsonObject(), "id");
    }
    return "";
  }

  private List<String> inOrder(Set<String> ids, List<String> orderedIds) {
    List<String> ordered = new ArrayList<String>();
    if (ids == null || ids.isEmpty()) {
      return ordered;
    }
    for (String orderedId : orderedIds) {
      if (ids.contains(orderedId)) {
        ordered.add(orderedId);
      }
    }
    return ordered;
  }

  private String displayName(JsonObject node) {
    String displayName = stringValue(node, "displayName");
    if (displayName.length() > 0) {
      return displayName;
    }
    return stringValue(node, "name");
  }

  private String blueOceanStatus(JsonObject node) {
    String result = stringValue(node, "result");
    if (result.length() > 0 && !"UNKNOWN".equalsIgnoreCase(result)) {
      if ("NOT_BUILT".equalsIgnoreCase(result)) {
        return "NOT_EXECUTED";
      }
      if ("FAILED".equalsIgnoreCase(result)) {
        return "FAILURE";
      }
      return result.toUpperCase();
    }

    String state = stringValue(node, "state");
    if ("RUNNING".equalsIgnoreCase(state)) {
      return "IN_PROGRESS";
    }
    if ("QUEUED".equalsIgnoreCase(state) || "PAUSED".equalsIgnoreCase(state)) {
      return state.toUpperCase();
    }
    return "";
  }

  private long blueOceanStartTimeMillis(JsonObject node) {
    long numericStart = longValue(node, "startTimeMillis", 0L);
    if (numericStart > 0L) {
      return numericStart;
    }
    String startTime = stringValue(node, "startTime");
    if (startTime.length() == 0) {
      return 0L;
    }
    try {
      Date date = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US).parse(startTime);
      return date == null ? 0L : date.getTime();
    } catch (ParseException e) {
      return 0L;
    }
  }

  private long longValue(JsonObject object, String key, long defaultValue) {
    JsonElement value = object.get(key);
    if (value == null || value.isJsonNull()) {
      return defaultValue;
    }
    try {
      return value.getAsLong();
    } catch (RuntimeException e) {
      return defaultValue;
    }
  }

  private String stringValue(JsonObject object, String key) {
    JsonElement value = object.get(key);
    if (value == null || value.isJsonNull()) {
      return "";
    }
    try {
      return value.getAsString();
    } catch (RuntimeException e) {
      return "";
    }
  }

  /**
   * Fetches the console text of one Pipeline stage. The stage node itself carries no log, so this
   * descends into the stage's {@code stageFlowNodes} (echo / sh / etc. steps) and concatenates each
   * step node's log in flow order. ConsoleNote annotations are stripped, same as the progressive log.
   */
  public JenkinsStageLog getStageLog(String jobName, int buildNumber, String stageId) throws BridgeHttpException {
    JenkinsStageNodes nodes = getStageNodes(jobName, buildNumber, stageId);
    if (nodes.getLogNodeIds().isEmpty()) {
      return JenkinsStageLog.empty();
    }

    StringBuilder text = new StringBuilder();
    for (String nodeId : nodes.getLogNodeIds()) {
      text.append(getNodeLog(jobName, buildNumber, nodeId).getText());
    }
    return JenkinsStageLog.of(text.toString());
  }

  /**
   * Fetches the step nodes of a stage via {@code execution/node/<stageId>/wfapi/describe}. A
   * {@code 404} (stage not yet materialized) yields no nodes.
   */
  JenkinsStageNodes getStageNodes(String jobName, int buildNumber, String stageId) throws BridgeHttpException {
    JsonObject describe = getStageNodeDescribe(jobName, buildNumber, stageId, null);
    if (describe == null) {
      return JenkinsStageNodes.empty();
    }
    return JenkinsStageNodes.fromJson(describe);
  }

  private JsonObject getStageNodeDescribe(String jobName, int buildNumber, String stageId, List<String> diagnostics)
      throws BridgeHttpException {
    JenkinsBridgeSettings settings = settingsProvider.load();
    String url = settings.getJenkinsUrl()
        + jenkinsJobPath(jobName)
        + "/"
        + buildNumber
        + "/execution/node/"
        + encodePathSegment(stageId)
        + "/wfapi/describe";

    try {
      String response = httpClient.get(url, settings.getJenkinsUser(), settings.getJenkinsToken(), "application/json");
      return jsonParser.parse(response).getAsJsonObject();
    } catch (BridgeHttpException e) {
      if (e.getStatusCode() == 404) {
        if (diagnostics != null) {
          diagnostics.add("WFAPI node " + stageId + " returned 404");
        }
        return null;
      }
      throw e;
    }
  }

  /**
   * Fetches one flow node's console text via {@code execution/node/<id>/wfapi/log}, with Jenkins
   * ConsoleNote annotations stripped. A {@code 404} (node not yet materialized) yields empty text.
   */
  JenkinsStageLog getNodeLog(String jobName, int buildNumber, String nodeId) throws BridgeHttpException {
    JenkinsBridgeSettings settings = settingsProvider.load();
    String url = settings.getJenkinsUrl()
        + jenkinsJobPath(jobName)
        + "/"
        + buildNumber
        + "/execution/node/"
        + encodePathSegment(nodeId)
        + "/wfapi/log";

    try {
      String response = httpClient.get(url, settings.getJenkinsUser(), settings.getJenkinsToken(), "application/json");
      JenkinsStageLog log = JenkinsStageLog.fromJson(jsonParser.parse(response).getAsJsonObject());
      return JenkinsStageLog.of(stripConsoleNotes(log.getText()));
    } catch (BridgeHttpException e) {
      if (e.getStatusCode() == 404) {
        return JenkinsStageLog.empty();
      }
      throw e;
    }
  }

  /**
   * Reads the build parameters a Jenkins job declares. Empty for a non-parameterized job (or a
   * {@code 404}). Drives the TeamCity trigger form and validates which values are accepted.
   */
  public JenkinsJobParameters getJobParameters(String jobName) throws BridgeHttpException {
    JenkinsBridgeSettings settings = settingsProvider.load();
    String tree = "property[parameterDefinitions[name,type,defaultParameterValue[value],choices]]";
    String url = settings.getJenkinsUrl()
        + jenkinsJobPath(jobName)
        + "/api/json?tree="
        + encodeQueryValue(tree);

    try {
      String response = httpClient.get(url, settings.getJenkinsUser(), settings.getJenkinsToken(), "application/json");
      return JenkinsJobParameters.fromJson(jsonParser.parse(response).getAsJsonObject());
    } catch (BridgeHttpException e) {
      if (e.getStatusCode() == 404) {
        return JenkinsJobParameters.empty();
      }
      throw e;
    }
  }

  /**
   * Reads the parameter values attached to one concrete Jenkins build run. Empty for
   * non-parameterized builds, builds whose ParametersAction is absent, or a {@code 404}.
   */
  public JenkinsBuildParameters getBuildParameters(String jobName, int buildNumber) throws BridgeHttpException {
    JenkinsBridgeSettings settings = settingsProvider.load();
    String tree = "actions[parameters[name,value,_class]]";
    String url = settings.getJenkinsUrl()
        + jenkinsJobPath(jobName)
        + "/"
        + buildNumber
        + "/api/json?tree="
        + encodeQueryValue(tree);

    try {
      String response = httpClient.get(url, settings.getJenkinsUser(), settings.getJenkinsToken(), "application/json");
      return JenkinsBuildParameters.fromJson(jsonParser.parse(response).getAsJsonObject());
    } catch (BridgeHttpException e) {
      if (e.getStatusCode() == 404) {
        return JenkinsBuildParameters.empty();
      }
      throw e;
    }
  }

  /**
   * Fetches a Jenkins CSRF crumb. A {@code 404} means crumb protection is disabled, in which case
   * {@link JenkinsCrumb#disabled()} is returned and no crumb header is sent on the trigger POST.
   */
  public JenkinsCrumb getCrumb() throws BridgeHttpException {
    JenkinsBridgeSettings settings = settingsProvider.load();
    String url = settings.getJenkinsUrl() + "/crumbIssuer/api/json";
    try {
      String response = httpClient.get(url, settings.getJenkinsUser(), settings.getJenkinsToken(), "application/json");
      return JenkinsCrumb.fromJson(jsonParser.parse(response).getAsJsonObject());
    } catch (BridgeHttpException e) {
      if (e.getStatusCode() == 404) {
        return JenkinsCrumb.disabled();
      }
      throw e;
    }
  }

  /**
   * Triggers a Jenkins build. With parameters it POSTs to {@code buildWithParameters}; without, to
   * {@code build}. Attaches a CSRF crumb when the controller requires one. Returns the queue-item
   * URL from the {@code Location} response header (empty if absent), which the caller can poll until
   * Jenkins assigns a build number.
   */
  public String triggerBuild(String jobName, Map<String, String> parameters) throws BridgeHttpException {
    JenkinsBridgeSettings settings = settingsProvider.load();
    boolean parameterized = parameters != null && !parameters.isEmpty();

    String url = settings.getJenkinsUrl()
        + jenkinsJobPath(jobName)
        + "/"
        + (parameterized ? "buildWithParameters" : "build");

    String body = parameterized ? encodeForm(parameters) : "";

    Map<String, String> headers = new LinkedHashMap<String, String>();
    JenkinsCrumb crumb = getCrumb();
    if (crumb.isPresent()) {
      headers.put(crumb.getField(), crumb.getValue());
    }

    BridgeHttpResponse response = httpClient.postResponse(
        url, settings.getJenkinsUser(), settings.getJenkinsToken(),
        body, "application/x-www-form-urlencoded", "application/json", headers);

    String location = response.getHeader("Location");
    return location == null ? "" : location;
  }

  private static String encodeForm(Map<String, String> parameters) {
    StringBuilder body = new StringBuilder();
    for (Map.Entry<String, String> entry : parameters.entrySet()) {
      if (body.length() > 0) {
        body.append('&');
      }
      body.append(encodeQueryValue(entry.getKey()))
          .append('=')
          .append(encodeQueryValue(entry.getValue() == null ? "" : entry.getValue()));
    }
    return body.toString();
  }

  private String jenkinsJobPath(String jobName) {
    String[] segments = jobName.split("/");
    StringBuilder result = new StringBuilder();
    for (String segment : segments) {
      if (segment.length() > 0) {
        result.append("/job/").append(encodePathSegment(segment));
      }
    }
    return result.toString();
  }

  private String blueOceanPipelinePath(String jobName) {
    String[] segments = jobName.split("/");
    StringBuilder result = new StringBuilder();
    for (String segment : segments) {
      if (segment.length() > 0) {
        result.append("/pipelines/").append(encodePathSegment(segment));
      }
    }
    return result.toString();
  }

  private String encodeRelativePath(String relativePath) {
    String[] segments = relativePath.split("/");
    StringBuilder result = new StringBuilder();
    for (String segment : segments) {
      if (segment.length() > 0) {
        if (result.length() > 0) {
          result.append('/');
        }
        result.append(encodePathSegment(segment));
      }
    }
    return result.toString();
  }

  private String nullToEmpty(String value) {
    return value == null ? "" : value;
  }

  private String encodePathSegment(String value) {
    return encodeQueryValue(value).replace("+", "%20");
  }

  public static String encodeQueryValue(String value) {
    try {
      return URLEncoder.encode(value, "UTF-8");
    } catch (UnsupportedEncodingException e) {
      throw new IllegalStateException("UTF-8 is not available", e);
    }
  }
}
