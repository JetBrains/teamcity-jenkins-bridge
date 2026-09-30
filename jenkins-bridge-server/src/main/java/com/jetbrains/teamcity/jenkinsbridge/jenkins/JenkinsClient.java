package com.jetbrains.teamcity.jenkinsbridge.jenkins;

import com.intellij.openapi.diagnostic.Logger;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import com.jetbrains.teamcity.jenkinsbridge.http.BridgeHttpClient;
import com.jetbrains.teamcity.jenkinsbridge.http.BridgeHttpException;
import com.jetbrains.teamcity.jenkinsbridge.http.BridgeHttpResponse;
import com.jetbrains.teamcity.jenkinsbridge.connection.JenkinsConnection;
import com.jetbrains.teamcity.jenkinsbridge.model.*;
import com.jetbrains.teamcity.jenkinsbridge.vcs.VcsRefType;
import com.jetbrains.teamcity.jenkinsbridge.vcs.constants.GitConstants;
import com.jetbrains.teamcity.jenkinsbridge.xml.JaxbUnmarshaller;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.UnsupportedEncodingException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLEncoder;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.regex.Pattern;

import static com.jetbrains.teamcity.jenkinsbridge.util.Utilities.nullToEmpty;
import static com.jetbrains.teamcity.jenkinsbridge.util.Utilities.stringValue;

/**
 * Talks to one Jenkins server, the one described by the {@link JenkinsConnection} it was created
 * with. Instances are cheap and are built per connection by {@link JenkinsClientFactory}.
 */
public class JenkinsClient {
  // Jenkins embeds invisible ConsoleNote annotations in the raw console as an ANSI "conceal" block:
  // ESC[8m + "ha:" + base64 payload + ESC[0m. Jenkins' own UI hides them; the bridge must strip them
  // so they do not leak into the mirrored TeamCity log as base64 garbage. A note never spans a line,
  // so the (line-bounded) non-greedy match is safe; a note split across two progressive fetches is a
  // rare edge that can leak one partial note at the boundary.
  private static final Pattern CONSOLE_NOTE = Pattern.compile("\\[8m.*?\\[0m");
  private static final Pattern QUEUE_ITEM_LOCATION =
      Pattern.compile("(?:^|/)queue/item/([0-9]+)/?(?:$|[?#])");
  private static final Logger LOG = Logger.getInstance(JenkinsClient.class.getName());

  private final JenkinsConnection myConnection;
  private final BridgeHttpClient httpClient;
  private final JaxbUnmarshaller xmlUnmarshaller;
  private final JsonParser jsonParser = new JsonParser();

  private interface JsonMapper<T> {
    T map(JsonObject json);
  }

  public JenkinsClient(@NotNull JenkinsConnection connection,
                       @NotNull BridgeHttpClient httpClient,
                       @NotNull JaxbUnmarshaller xmlUnmarshaller) {
    this.myConnection = connection;
    this.httpClient = httpClient;
    this.xmlUnmarshaller = xmlUnmarshaller;
  }

  /** The Jenkins server this client is bound to. */
  @NotNull
  public JenkinsConnection getConnection() {
    return myConnection;
  }

  /**
   * Identifies the Jenkins server behind this client. Jenkins queue ids are only unique per server,
   * so pending triggers are namespaced by this value.
   *
   * @return the Jenkins server URL
   */
  @NotNull
  public String getControllerIdentity() {
    return myConnection.getUrl();
  }

  /**
   * Returns the numbers of the (up to 100) most recent builds. Jenkins caps the {@code builds}
   * collection at the 100 newest builds to avoid loading the whole history; callers that may have
   * fallen further behind can escalate to {@link #getAllBuildNumbers(String)}.
   */
  public List<Integer> getBuildNumbers(String jobName) throws BridgeHttpException, JenkinsDataException {
    return fetchBuildNumbers(jobName, "builds");
  }

  /**
   * Returns the numbers of all builds via Jenkins' {@code allBuilds} collection. This is complete
   * but can be expensive on jobs with a long history, so use it only as a fallback when a gap is
   * detected below the {@link #getBuildNumbers(String)} window.
   */
  public List<Integer> getAllBuildNumbers(String jobName) throws BridgeHttpException, JenkinsDataException {
    return fetchBuildNumbers(jobName, "allBuilds");
  }

  /**
   * Returns the (up to 100) most recent builds with enough metadata to identify a Jenkins run even
   * when build numbers are reused after history deletion/reset.
   */
  public List<JenkinsBuildInfo> getBuilds(String jobName) throws BridgeHttpException, JenkinsDataException {
    return fetchBuildInfos(jobName, "builds");
  }

  /**
   * Returns all builds with identity metadata. This is complete but potentially expensive, so
   * callers should use it only after detecting that the recent-build window is insufficient.
   */
  public List<JenkinsBuildInfo> getAllBuilds(String jobName) throws BridgeHttpException, JenkinsDataException {
    return fetchBuildInfos(jobName, "allBuilds");
  }

  private List<Integer> fetchBuildNumbers(String jobName, String collection)
      throws BridgeHttpException, JenkinsDataException {
    String tree = collection + "[number]";
    String url = myConnection.getUrl()
        + jenkinsJobPath(jobName)
        + "/api/json?tree="
        + encodeQueryValue(tree);

    String response = httpClient.get(url, myConnection.getUser(), myConnection.getToken(), "application/json");
    JsonObject root = parseJsonObject(response, collection + " build numbers");
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

  private List<JenkinsBuildInfo> fetchBuildInfos(String jobName, String collection)
      throws BridgeHttpException, JenkinsDataException {
    String tree = collection + "[number,timestamp,url,queueId,actions[causes[shortDescription]]]";
    String url = myConnection.getUrl()
        + jenkinsJobPath(jobName)
        + "/api/json?tree="
        + encodeQueryValue(tree);

    String response = httpClient.get(url, myConnection.getUser(), myConnection.getToken(), "application/json");
    JsonObject root = parseJsonObject(response, collection + " build information");
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

  public JenkinsBuildInfo getBuildInfo(String jobName, int buildNumber)
      throws BridgeHttpException, JenkinsDataException {
    String tree = "number,queueId,building,result,timestamp,duration,estimatedDuration,url,actions[causes[shortDescription]]";
    String url = myConnection.getUrl()
        + jenkinsJobPath(jobName)
        + "/"
        + buildNumber
        + "/api/json?tree="
        + encodeQueryValue(tree);

    String response = httpClient.get(url, myConnection.getUser(), myConnection.getToken(), "application/json");
    return parseJson(response, "build information", new JsonMapper<JenkinsBuildInfo>() {
      public JenkinsBuildInfo map(JsonObject json) {
        return JenkinsBuildInfo.fromJson(json);
      }
    });
  }

  @NotNull
  public JenkinsVcsInfo getBuildVcs(String jobName, int buildNumber)
      throws BridgeHttpException, JenkinsDataException {
    String tree = "actions[_class,"
        + GitConstants.API_FIELDS
//      + ','
//      + MercurialConstants.API_FIELDS
        + ']';
    String url = myConnection.getUrl()
        + jenkinsJobPath(jobName)
        + "/"
        + buildNumber
        + "/api/json?tree="
        + encodeQueryValue(tree);

    try {
      String response = httpClient.get(url, myConnection.getUser(), myConnection.getToken(), "application/json");
      return parseJson(response, "build VCS information", new JsonMapper<JenkinsVcsInfo>() {
        public JenkinsVcsInfo map(JsonObject json) {
          return JenkinsVcsInfo.fromJson(json);
        }
      });
    } catch (BridgeHttpException e) {
      if (e.getStatusCode() == 404) {
        return JenkinsVcsInfo.empty();
      }
      throw e;
    }
  }

  /**
   * Fetches only the console output produced since {@code start} (a byte offset) using Jenkins'
   * progressive log API, instead of re-downloading the whole console each poll.
   */
  public JenkinsLogChunk getProgressiveLog(String jobName, int buildNumber, long start) throws BridgeHttpException {
    long safeStart = Math.max(0L, start);
    String url = myConnection.getUrl()
        + jenkinsJobPath(jobName)
        + "/"
        + buildNumber
        + "/logText/progressiveText?start="
        + safeStart;

    BridgeHttpResponse response = httpClient.getResponse(
        url, myConnection.getUser(), myConnection.getToken(), "text/plain");

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

  private <T> T parseJson(String response, String operation, JsonMapper<T> mapper)
      throws JenkinsDataException {
    return mapper.map(parseJsonObject(response, operation));
  }

  private JsonObject parseJsonObject(String response, String operation) throws JenkinsDataException {
    JsonElement parsed = parseJsonElement(response, operation);
    if (!parsed.isJsonObject()) {
      throw new JenkinsDataException("Jenkins returned invalid " + operation
          + ": response was not a JSON object");
    }
    return parsed.getAsJsonObject();
  }

  private JsonElement parseJsonElement(String response, String operation) throws JenkinsDataException {
    try {
      return jsonParser.parse(response);
    } catch (JsonSyntaxException e) {
      throw new JenkinsDataException("Jenkins returned invalid " + operation, e);
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

  public JenkinsTestReport getTestReport(String jobName, int buildNumber)
      throws BridgeHttpException, JenkinsDataException {
    String tree = "suites[name,cases[className,name,status,duration,errorDetails,errorStackTrace,skippedMessage,stdout,stderr]]";
    String url = myConnection.getUrl()
        + jenkinsJobPath(jobName)
        + "/"
        + buildNumber
        + "/testReport/api/json?tree="
        + encodeQueryValue(tree);

    try {
      String response = httpClient.get(url, myConnection.getUser(), myConnection.getToken(), "application/json");
      return parseJson(response, "test report", new JsonMapper<JenkinsTestReport>() {
        public JenkinsTestReport map(JsonObject json) {
          return JenkinsTestReport.fromJson(json);
        }
      });
    } catch (BridgeHttpException e) {
      if (e.getStatusCode() == 404) {
        return JenkinsTestReport.empty();
      }
      throw e;
    }
  }

  public JenkinsArtifacts getArtifacts(String jobName, int buildNumber)
      throws BridgeHttpException, JenkinsDataException {
    String tree = "artifacts[fileName,relativePath]";
    String buildUrl = myConnection.getUrl()
        + jenkinsJobPath(jobName)
        + "/"
        + buildNumber;
    String url = buildUrl
        + "/api/json?tree="
        + encodeQueryValue(tree);

    try {
      String response = httpClient.get(url, myConnection.getUser(), myConnection.getToken(), "application/json");
      JenkinsArtifacts artifacts = parseJson(response, "build artifacts", new JsonMapper<JenkinsArtifacts>() {
        public JenkinsArtifacts map(JsonObject json) {
          return JenkinsArtifacts.fromJson(json);
        }
      });
      List<JenkinsArtifact> sizedArtifacts = new ArrayList<>();
      for (JenkinsArtifact artifact : artifacts.getArtifacts()) {
        String artifactUrl = buildUrl + "/artifact/" + artifact.relativePath();
        // TODO: HEAD requests may be parallelized
        // TODO: Alternatively, the /artifact HTML page can be scraped to get the sizes
        int size = 0;
        try {
          String sizeString = httpClient.head(artifactUrl, myConnection.getUser(), myConnection.getToken(), "*/*").getHeader("Content-Length");
          size = Integer.parseInt(sizeString);
        } catch (BridgeHttpException e) {
          LOG.warn("Failed to get artifact size for " + artifactUrl, e);
        }
        sizedArtifacts.add(new JenkinsArtifact(artifact.fileName(), artifact.relativePath(), size));
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
    return myConnection.getUrl()
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
    String url = artifactUrl(jobName, buildNumber, relativePath);
    httpClient.getStream(url, myConnection.getUser(), myConnection.getToken(), "*/*", handler);
  }

  /**
   * Lists the top-level jobs at {@code folderPath} (blank = Jenkins root). Does not recurse into
   * folders or expand multibranch projects; folder/multibranch entries are returned but marked
   * non-importable. Reads from this client's Jenkins connection.
   */
  public List<JenkinsJob> listJobs(String folderPath) throws BridgeHttpException, JenkinsDataException {
    return listJobs(folderPath, -1, -1);
  }

  /**
   * Lists a bounded range of top-level jobs using Jenkins Remote API tree range syntax. Jenkins
   * does not expose conventional page metadata, so callers should request the next range while
   * the returned page is full. Negative start/limit preserves the historical unbounded request.
   */
  public List<JenkinsJob> listJobs(String folderPath, int start, int limit)
      throws BridgeHttpException, JenkinsDataException {
    return listJobs(folderPath, start, limit, "");
  }

  /**
   * Lists a page of jobs matching {@code search}. For a non-empty search Jenkins is scanned in
   * bounded ranges so the bridge never downloads the whole job catalog into one request or one
   * in-memory collection. Jenkins' generic API has no server-side text search or total-count
   * metadata, so a search may still require scanning several ranges.
   */
  public List<JenkinsJob> listJobs(String folderPath, int start, int limit, String search)
      throws BridgeHttpException, JenkinsDataException {
    if (search != null && !search.trim().isEmpty()) {
      return searchJobs(folderPath, start, limit, search.trim());
    }
    return listJobsPage(folderPath, start, limit);
  }

  private List<JenkinsJob> listJobsPage(String folderPath, int start, int limit)
      throws BridgeHttpException, JenkinsDataException {
    String tree = "jobs[name,fullName,url,_class,buildable,color]";
    if (start >= 0 && limit > 0) {
      tree += "{" + start + "," + (start + limit) + "}";
    }
    String url = myConnection.getUrl()
        + jenkinsJobPath(folderPath == null ? "" : folderPath)
        + "/api/json?tree="
        + encodeQueryValue(tree);

    String response = httpClient.get(url, myConnection.getUser(), myConnection.getToken(), "application/json");
    JsonObject root = parseJsonObject(response, "job list");
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

  private List<JenkinsJob> searchJobs(String folderPath, int start, int limit, String search)
      throws BridgeHttpException, JenkinsDataException {
    final int scanPageSize = 100;
    String needle = search.toLowerCase(Locale.ROOT);
    int matchingJobsSeen = 0;
    int remoteOffset = 0;
    List<JenkinsJob> result = new ArrayList<JenkinsJob>();

    while (true) {
      List<JenkinsJob> page = listJobsPage(folderPath, remoteOffset, scanPageSize);
      if (page.isEmpty()) {
        return result;
      }
      for (JenkinsJob job : page) {
        String name = job.getName() == null ? "" : job.getName().toLowerCase(Locale.ROOT);
        String fullName = job.getFullName() == null ? "" : job.getFullName().toLowerCase(Locale.ROOT);
        if (!name.contains(needle) && !fullName.contains(needle)) {
          continue;
        }
        if (matchingJobsSeen++ < start) {
          continue;
        }
        result.add(job);
        if (result.size() >= limit) {
          return result;
        }
      }
      if (page.size() < scanPageSize) {
        return result;
      }
      remoteOffset += scanPageSize;
    }
  }

  /**
   * Lists the branch jobs of a multibranch pipeline together with their recent builds.
   * Maps each branch job's {@code fullName} to its builds.
   */
  public Map<String, List<JenkinsBuildInfo>> listBranchBuilds(String pipelinePath)
      throws BridgeHttpException, JenkinsDataException {
    String tree = "jobs[fullName,builds[number,timestamp,url]]";
    String url = myConnection.getUrl()
        + jenkinsJobPath(pipelinePath == null ? "" : pipelinePath)
        + "/api/json?tree="
        + encodeQueryValue(tree);

    String response = httpClient.get(url, myConnection.getUser(), myConnection.getToken(), "application/json");
    JsonObject root = parseJsonObject(response, "branch build list");
    JsonArray jobs = root.getAsJsonArray("jobs");
    Map<String, List<JenkinsBuildInfo>> result = new LinkedHashMap<>();
    if (jobs == null) {
      return result;
    }
    for (JsonElement jobElement : jobs) {
      if (jobElement == null || !jobElement.isJsonObject()) {
        continue;
      }
      JsonObject job = jobElement.getAsJsonObject();
      String fullName = stringValue(job, "fullName");
      if (fullName.isEmpty()) {
        continue;
      }
      List<JenkinsBuildInfo> builds = new ArrayList<>();
      JsonArray buildArray = job.getAsJsonArray("builds");
      if (buildArray != null) {
        for (JsonElement build : buildArray) {
          if (build != null && build.isJsonObject()) {
            builds.add(JenkinsBuildInfo.fromJson(build.getAsJsonObject()));
          }
        }
      }
      result.put(fullName, builds);
    }
    return result;
  }

  /**
   * The Jenkins {@code _class} of a single job.
   * Returns an empty string when Jenkins does not report a class.
   */
  @NotNull
  public String getJobClass(String fullName) throws BridgeHttpException, JenkinsDataException {
    String url = myConnection.getUrl()
        + jenkinsJobPath(fullName == null ? "" : fullName)
        + "/api/json?tree="
        + encodeQueryValue("_class");

    String response = httpClient.get(url, myConnection.getUser(), myConnection.getToken(), "application/json");
    JsonObject root = parseJsonObject(response, "job class");
    return stringValue(root, "_class");
  }

  /**
   * Absolute Jenkins job page URL for {@code fullName}, derived from the connection's base URL.
   */
  public String jobUrl(String fullName) {
    return myConnection.getUrl()
        + jenkinsJobPath(fullName == null ? "" : fullName)
        + "/";
  }

  /**
   * Fetches the Pipeline stage list via Jenkins' {@code wfapi/describe} (Pipeline Stage View
   * plugin). A {@code 404} means the build is not a Pipeline, or the plugin is absent: callers
   * fall back to the flat console log.
   */
  public JenkinsStages getStages(String jobName, int buildNumber)
      throws BridgeHttpException, JenkinsDataException {
    String url = myConnection.getUrl()
        + jenkinsJobPath(jobName)
        + "/"
        + buildNumber
        + "/wfapi/describe";

    try {
      String response = httpClient.get(url, myConnection.getUser(), myConnection.getToken(), "application/json");
      return parseJson(response, "Pipeline stages", new JsonMapper<JenkinsStages>() {
        public JenkinsStages map(JsonObject json) {
          return JenkinsStages.fromJson(json);
        }
      });
    } catch (BridgeHttpException e) {
      if (e.getStatusCode() == 404) {
        return JenkinsStages.notPipeline();
      }
      throw e;
    }
  }

  @Nullable
  public JenkinsPipelineGraph getPipelineGraph(String jobName, int buildNumber)
      throws BridgeHttpException, JenkinsDataException {
    return getPipelineGraph(jobName, buildNumber, jobName + "#" + buildNumber);
  }

  @Nullable
  public JenkinsPipelineGraph getPipelineGraph(String jobName, int buildNumber, String flowIdPrefix)
      throws BridgeHttpException, JenkinsDataException {
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
  ) throws BridgeHttpException, JenkinsDataException {
    String url = myConnection.getUrl()
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
      String response = httpClient.get(url, myConnection.getUser(), myConnection.getToken(), "application/json");
      root = parseJsonObject(response, "WFAPI Pipeline description");
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
  ) throws BridgeHttpException, JenkinsDataException {
    List<String> diagnostics = new ArrayList<String>();

    String url = myConnection.getUrl()
        + "/blue/rest/organizations/jenkins"
        + blueOceanPipelinePath(jobName)
        + "/runs/"
        + buildNumber
        + "/nodes/";

    JsonArray nodes;
    try {
      String response = httpClient.get(url, myConnection.getUser(), myConnection.getToken(), "application/json");
      JsonElement parsed = parseJsonElement(response, "Blue Ocean Pipeline graph");
      if (parsed.isJsonArray()) {
        nodes = parsed.getAsJsonArray();
      } else if (parsed.isJsonObject()
          && parsed.getAsJsonObject().get("nodes") != null
          && parsed.getAsJsonObject().get("nodes").isJsonArray()) {
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
    // Normalize Blue Ocean result/state into the shared node-status vocabulary. Notably fixes
    // state=SKIPPED (was "" -> generated build stuck queued forever) and state=PAUSED (was "PAUSED";
    // now PAUSED_PENDING_INPUT); unrecognized -> UNKNOWN instead of blank.
    return JenkinsPipelineNodeStatus.fromBlueOcean(
        stringValue(node, "result"), stringValue(node, "state")).name();
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
    } catch (IllegalStateException | ClassCastException  e) {
      return defaultValue;
    }
  }

  /**
   * Fetches the console text of one Pipeline stage. The stage node itself carries no log, so this
   * descends into the stage's {@code stageFlowNodes} (echo / sh / etc. steps) and concatenates each
   * step node's log in flow order. ConsoleNote annotations are stripped, same as the progressive log.
   */
  public JenkinsStageLog getStageLog(String jobName, int buildNumber, String stageId)
      throws BridgeHttpException, JenkinsDataException {
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
   * Fetches the steps of a stage with WFAPI's display name, stored argument description, status,
   * duration, and console log (G3b). The argument description comes from Jenkins' ArgumentsAction;
   * it is intentionally not derived from log text. Descends the stage's {@code stageFlowNodes}; for
   * each step that carries a log link, fetches its node log (Console annotations stripped). Steps
   * without a log (e.g. structural nodes) are still returned with empty log. A {@code 404} (stage not
   * materialized) yields no steps.
   */
  public List<JenkinsStageStep> getStageSteps(String jobName, int buildNumber, String stageId)
      throws BridgeHttpException, JenkinsDataException {
    JsonObject describe = getStageNodeDescribe(jobName, buildNumber, stageId, null);
    if (describe == null) {
      return Collections.emptyList();
    }
    List<JenkinsStageStep> steps = new ArrayList<JenkinsStageStep>();
    for (JenkinsWfapiNode node : JenkinsWfapiNode.stageFlowNodesFromJson(describe)) {
      String log = node.isLogNode() ? getNodeLog(jobName, buildNumber, node.getId()).getText() : "";
      steps.add(new JenkinsStageStep(
          node.getId(), node.getName(), node.getParameterDescription(), node.getStatus(), node.getDurationMillis(), log));
    }
    return steps;
  }

  /**
   * Steps for a graph node, resolving the Blue-Ocean-vs-WFAPI id mismatch on parallel branches. A Blue
   * Ocean parallel-branch node (e.g. "Linux Tests", id 21) is a container whose WFAPI describe has no
   * steps; the actual steps live under a nested WFAPI stage (e.g. "Linux", id 26) whose id Blue Ocean
   * does not expose. So: try the node id directly (works for linear stages, ids match); if that yields no
   * steps, fall back to the WFAPI stage whose name matches the clicked node's name.
   */
  public List<JenkinsStageStep> getStageStepsForNode(String jobName, int buildNumber, String nodeId, String nodeName)
      throws BridgeHttpException, JenkinsDataException {
    List<JenkinsStageStep> direct = getStageSteps(jobName, buildNumber, nodeId);
    if (!direct.isEmpty()) {
      return direct;
    }
    String matchedId = matchWfapiStageIdByName(jobName, buildNumber, nodeId, nodeName);
    if (matchedId != null && !matchedId.equals(nodeId)) {
      return getStageSteps(jobName, buildNumber, matchedId);
    }
    return direct;
  }

  /**
   * Finds the WFAPI stage id whose name corresponds to a Blue Ocean node name. Prefers an exact name
   * match, then a stage name that the node name starts with (branch "Linux Tests" -> stage "Linux"),
   * then containment. Returns null if nothing matches.
   */
  private String matchWfapiStageIdByName(String jobName, int buildNumber, String excludeId, String nodeName)
      throws BridgeHttpException, JenkinsDataException {
    if (nodeName == null || nodeName.trim().length() == 0) {
      return null;
    }
    JenkinsStages stages = getStages(jobName, buildNumber);
    String exact = null;
    String prefix = null;
    String contains = null;
    for (JenkinsStage stage : stages.getStages()) {
      if (stage.getId().equals(excludeId)) {
        continue;
      }
      String stageName = stage.getName();
      if (stageName.length() == 0) {
        continue;
      }
      if (nodeName.equals(stageName)) {
        exact = stage.getId();
        break;
      }
      if (prefix == null && nodeName.startsWith(stageName)) {
        prefix = stage.getId();
      }
      if (contains == null && nodeName.contains(stageName)) {
        contains = stage.getId();
      }
    }
    return exact != null ? exact : (prefix != null ? prefix : contains);
  }

  /**
   * Fetches the step nodes of a stage via {@code execution/node/<stageId>/wfapi/describe}. A
   * {@code 404} (stage not yet materialized) yields no nodes.
   */
  JenkinsStageNodes getStageNodes(String jobName, int buildNumber, String stageId)
      throws BridgeHttpException, JenkinsDataException {
    JsonObject describe = getStageNodeDescribe(jobName, buildNumber, stageId, null);
    if (describe == null) {
      return JenkinsStageNodes.empty();
    }
    return JenkinsStageNodes.fromJson(describe);
  }

  private JsonObject getStageNodeDescribe(String jobName, int buildNumber, String stageId, List<String> diagnostics)
      throws BridgeHttpException, JenkinsDataException {
    String url = myConnection.getUrl()
        + jenkinsJobPath(jobName)
        + "/"
        + buildNumber
        + "/execution/node/"
        + encodePathSegment(stageId)
        + "/wfapi/describe";

    try {
      String response = httpClient.get(url, myConnection.getUser(), myConnection.getToken(), "application/json");
      return parseJson(response, "WFAPI stage node " + stageId, new JsonMapper<JsonObject>() {
        public JsonObject map(JsonObject json) {
          return json;
        }
      });
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
  JenkinsStageLog getNodeLog(String jobName, int buildNumber, String nodeId)
      throws BridgeHttpException, JenkinsDataException {
    String url = myConnection.getUrl()
        + jenkinsJobPath(jobName)
        + "/"
        + buildNumber
        + "/execution/node/"
        + encodePathSegment(nodeId)
        + "/wfapi/log";

    try {
      String response = httpClient.get(url, myConnection.getUser(), myConnection.getToken(), "application/json");
      JenkinsStageLog log = parseJson(response, "WFAPI node log " + nodeId,
          new JsonMapper<JenkinsStageLog>() {
            public JenkinsStageLog map(JsonObject json) {
              return JenkinsStageLog.fromJson(json);
            }
          });
      return JenkinsStageLog.of(stripConsoleNotes(log.getText()));
    } catch (BridgeHttpException e) {
      if (e.getStatusCode() == 404) {
        return JenkinsStageLog.empty();
      }
      throw e;
    }
  }

  /**
   * Reads pull or merge request metadata from a multibranch pipeline branch job's config.xml.
   * Returns {@link Optional#empty()} when the branch is not a pull or merge request, or
   * the config cannot be read.
   */
  @NotNull
  public Optional<JenkinsPullRequestInfo> getPullRequestInfo(String jobName) {
    String url = myConnection.getUrl()
        + jenkinsJobPath(jobName)
        + "/config.xml";

    try {
      String response = httpClient.get(url, myConnection.getUser(), myConnection.getToken(), "application/xml");
      var infoResult = JenkinsPullRequestInfo.fromConfigXml(response, xmlUnmarshaller);
      if (infoResult.isPresent()) {
        var info = infoResult.get();
        LOG.info("Branch job " + jobName + " is pull request #" + info.number()
            + " merging from " + info.sourceBranch() + " into " + info.targetBranch());
      } else {
        LOG.warn("Found no pull or merge request metadata for branch job " + jobName);
      }
      return infoResult;
    } catch (BridgeHttpException e) {
      LOG.warn("Failed to read the config of branch job " + jobName, e);
      return Optional.empty();
    }
  }

  /** Reads the primary SCM serialized on a Jenkins multibranch child job. */
  @NotNull
  public Optional<JenkinsScmHeadInfo> getBranchScmInfo(String jobName) throws BridgeHttpException {
    String url = myConnection.getUrl()
        + jenkinsJobPath(jobName)
        + "/config.xml";
    String response = httpClient.get(
        url, myConnection.getUser(), myConnection.getToken(), "application/xml");
    return JenkinsBranchHead.scmInfo(response, xmlUnmarshaller);
  }

  /**
   * Reads whether a multibranch pipeline branch job's config.xml is for a tag.
   * Defaults to {@link VcsRefType#HEADS} when the config cannot be read.
   */
  @NotNull
  public VcsRefType getBranchRefType(String jobName) {
    String url = myConnection.getUrl()
        + jenkinsJobPath(jobName)
        + "/config.xml";

    try {
      String response = httpClient.get(url, myConnection.getUser(), myConnection.getToken(), "application/xml");
      return JenkinsBranchHead.refType(response, xmlUnmarshaller);
    } catch (BridgeHttpException e) {
      LOG.warn("Failed to read the config of branch job " + jobName, e);
      return VcsRefType.HEADS;
    }
  }

  /**
   * Reads the build parameters a Jenkins job declares. Empty for a non-parameterized job (or a
   * {@code 404}). Drives the TeamCity trigger form and validates which values are accepted.
   */
  public JenkinsJobParameters getJobParameters(String jobName) throws BridgeHttpException, JenkinsDataException {
    String tree = "property[parameterDefinitions[name,type,defaultParameterValue[value],choices]]";
    String url = myConnection.getUrl()
        + jenkinsJobPath(jobName)
        + "/api/json?tree="
        + encodeQueryValue(tree);

    try {
      String response = httpClient.get(url, myConnection.getUser(), myConnection.getToken(), "application/json");
      return parseJson(response, "job parameters", new JsonMapper<JenkinsJobParameters>() {
        public JenkinsJobParameters map(JsonObject json) {
          return JenkinsJobParameters.fromJson(json);
        }
      });
    } catch (BridgeHttpException e) {
      if (e.getStatusCode() == 404) {
        return JenkinsJobParameters.empty();
      }
      throw e;
    }
  }

  /**
   * Reads the parameter values attached to one concrete Jenkins build run. Empty for
   * non-parameterized builds or builds whose ParametersAction is absent. A {@code 404} is
   * propagated because it means the requested Jenkins build resource was not found.
   */
  public JenkinsBuildParameters getBuildParameters(String jobName, int buildNumber)
      throws BridgeHttpException, JenkinsDataException {
    String tree = "actions[parameters[name,value,_class]]";
    String url = myConnection.getUrl()
        + jenkinsJobPath(jobName)
        + "/"
        + buildNumber
        + "/api/json?tree="
        + encodeQueryValue(tree);

    String response = httpClient.get(url, myConnection.getUser(), myConnection.getToken(), "application/json");
    return parseJson(response, "build parameters", new JsonMapper<JenkinsBuildParameters>() {
      public JenkinsBuildParameters map(JsonObject json) {
        return JenkinsBuildParameters.fromJson(json);
      }
    });
  }

  /**
   * Fetches a Jenkins CSRF crumb. A {@code 404} means crumb protection is disabled, in which case
   * {@link JenkinsCrumb#disabled()} is returned and no crumb header is sent on the trigger POST.
   */
  public JenkinsCrumb getCrumb() throws BridgeHttpException, JenkinsDataException {
    String url = myConnection.getUrl() + "/crumbIssuer/api/json";
    try {
      String response = httpClient.get(url, myConnection.getUser(), myConnection.getToken(), "application/json");
      return parseJson(response, "CSRF crumb", new JsonMapper<JenkinsCrumb>() {
        public JenkinsCrumb map(JsonObject json) {
          return JenkinsCrumb.fromJson(json);
        }
      });
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
  public String triggerBuild(String jobName, Map<String, String> parameters)
      throws BridgeHttpException, JenkinsDataException {
    return triggerBuildWithQueueId(jobName, parameters).getQueueItemUrl();
  }

  /**
   * Triggers a Jenkins build like {@link #triggerBuild}, but also reports the numeric queue id.
   *
   * @param jobName    Jenkins job to trigger
   * @param parameters build parameters, may be empty
   * @return the absolute queue-item URL and its queue id, both empty/-1 when Jenkins sent no Location header
   */
  public JenkinsTriggerResponse triggerBuildWithQueueId(String jobName, Map<String, String> parameters)
      throws BridgeHttpException, JenkinsDataException {
    return triggerBuildWithQueueId(jobName, parameters, null);
  }

  public JenkinsTriggerResponse triggerBuildWithQueueId(String jobName, Map<String, String> parameters,
                                                        String cause)
      throws BridgeHttpException, JenkinsDataException {
    boolean parameterized = parameters != null && !parameters.isEmpty();

    String url = buildTriggerUrl(jobName, parameterized, cause);

    String body = parameterized ? encodeForm(parameters) : "";

    Map<String, String> headers = new LinkedHashMap<String, String>();
    JenkinsCrumb crumb = getCrumb();
    if (crumb.isPresent()) {
      headers.put(crumb.getField(), crumb.getValue());
    }

    BridgeHttpResponse response = httpClient.postResponseNoRedirect(
        url, myConnection.getUser(), myConnection.getToken(),
        body, "application/x-www-form-urlencoded", "application/json", headers);

    String location = response.getHeader("Location");
    String normalizedLocation = normalizeQueueItemUrl(location, myConnection.getUrl());
    long queueId = parseQueueId(normalizedLocation);
    return new JenkinsTriggerResponse(normalizedLocation, queueId);
  }

  @NotNull
  private String buildTriggerUrl(@NotNull String jobName, boolean parameterized, @Nullable String cause) {
    String endpoint = parameterized ? "buildWithParameters" : "build";
    String causeParameter = cause == null || cause.trim().isEmpty()
        ? ""
        : "?cause=" + encodeQueryValue(cause);
    return myConnection.getUrl() + jenkinsJobPath(jobName) + "/" + endpoint + causeParameter;
  }

  /** Returns the numeric queue id in a Jenkins queue-item URL, or -1 for an invalid URL. */
  public static long parseQueueId(String location) {
    if (location == null) {
      return -1L;
    }
    java.util.regex.Matcher matcher = QUEUE_ITEM_LOCATION.matcher(location.trim());
    if (!matcher.find()) {
      return -1L;
    }
    try {
      return Long.parseLong(matcher.group(1));
    } catch (NumberFormatException e) {
      return -1L;
    }
  }

  private static String normalizeQueueItemUrl(String location, String jenkinsUrl) {
    if (location == null || location.trim().isEmpty()) {
      return "";
    }
    String value = location.trim();
    try {
      if (value.startsWith("/")) {
        return new URL(new URL(jenkinsUrl + "/"), value).toString();
      }
      return new java.net.URL(value).toString();
    } catch (MalformedURLException e) {
      return value;
    }
  }

  /**
   * Reads a Jenkins queue item to find the build it turned into.
   *
   * @param queueItemUrl absolute queue-item URL returned when the build was triggered
   * @return the resolved build number, or a pending/cancelled resolution while Jenkins has not started it
   */
  public JenkinsQueueBuildResolution resolveQueuedBuildNumber(String queueItemUrl)
      throws BridgeHttpException, JenkinsDataException {
    if (queueItemUrl == null || queueItemUrl.trim().isEmpty()) {
      return JenkinsQueueBuildResolution.pending();
    }

    String url = appendApiJson(queueItemUrl.trim());
    String response = httpClient.get(url, myConnection.getUser(), myConnection.getToken(), "application/json");
    JsonObject root = parseJsonObject(response, "queue item");

    JsonElement executable = root.get("executable");
    if (executable != null && executable.isJsonObject()) {
      JsonElement number = executable.getAsJsonObject().get("number");
      if (number != null && !number.isJsonNull()) {
        return JenkinsQueueBuildResolution.resolved(number.getAsInt());
      }
    }

    JsonElement cancelled = root.get("cancelled");
    if (cancelled != null && !cancelled.isJsonNull() && cancelled.getAsBoolean()) {
      return JenkinsQueueBuildResolution.cancelled();
    }

    return JenkinsQueueBuildResolution.pending();
  }

  private static String appendApiJson(String queueItemUrl) {
    String normalized = queueItemUrl.endsWith("/") ? queueItemUrl : queueItemUrl + "/";
    return normalized + "api/json";
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
