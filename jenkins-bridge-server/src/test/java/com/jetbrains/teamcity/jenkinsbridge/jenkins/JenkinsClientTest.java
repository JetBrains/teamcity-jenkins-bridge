package com.jetbrains.teamcity.jenkinsbridge.jenkins;

import com.jetbrains.teamcity.jenkinsbridge.http.BridgeHttpClient;
import com.jetbrains.teamcity.jenkinsbridge.http.BridgeHttpException;
import com.jetbrains.teamcity.jenkinsbridge.http.BridgeHttpResponse;
import com.jetbrains.teamcity.jenkinsbridge.model.JenkinsArtifacts;
import com.jetbrains.teamcity.jenkinsbridge.model.JenkinsBuildInfo;
import com.jetbrains.teamcity.jenkinsbridge.model.JenkinsCrumb;
import com.jetbrains.teamcity.jenkinsbridge.model.JenkinsBuildParameters;
import com.jetbrains.teamcity.jenkinsbridge.model.JenkinsJobParameters;
import com.jetbrains.teamcity.jenkinsbridge.model.JenkinsLogChunk;
import com.jetbrains.teamcity.jenkinsbridge.model.GraphConfidence;
import com.jetbrains.teamcity.jenkinsbridge.model.JenkinsPipelineGraph;
import com.jetbrains.teamcity.jenkinsbridge.model.JenkinsStageLog;
import com.jetbrains.teamcity.jenkinsbridge.model.JenkinsStageNodes;
import com.jetbrains.teamcity.jenkinsbridge.model.JenkinsStages;
import com.jetbrains.teamcity.jenkinsbridge.model.JenkinsTestReport;
import com.jetbrains.teamcity.jenkinsbridge.settings.JenkinsBridgeSettings;
import com.jetbrains.teamcity.jenkinsbridge.settings.JenkinsBridgeSettingsProvider;
import org.junit.Test;

import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class JenkinsClientTest {
  @Test
  public void returnsEmptyReportWhenJenkinsHasNoTestReport() throws Exception {
    NotFoundHttpClient httpClient = new NotFoundHttpClient();
    JenkinsClient client = new JenkinsClient(new StaticSettingsProvider(), httpClient);

    JenkinsTestReport report = client.getTestReport("folder/job", 7);

    assertTrue(report.isEmpty());
    assertEquals("http://jenkins/job/folder/job/job/7/testReport/api/json?tree=suites%5Bname%2Ccases%5BclassName%2Cname%2Cstatus%2Cduration%2CerrorDetails%2CerrorStackTrace%2CskippedMessage%2Cstdout%2Cstderr%5D%5D", httpClient.url);
  }

  @Test
  public void getArtifactsParsesArchivedArtifacts() throws Exception {
    StubResponseHttpClient httpClient = new StubResponseHttpClient();
    httpClient.body = "{\"artifacts\":["
        + "{\"fileName\":\"app.jar\",\"relativePath\":\"target/app.jar\"},"
        + "{\"fileName\":\"report.txt\",\"relativePath\":\"reports/unit/report.txt\"}"
        + "]}";
    JenkinsClient client = new JenkinsClient(new StaticSettingsProvider(), httpClient);

    JenkinsArtifacts artifacts = client.getArtifacts("folder/job", 7);

    assertEquals("http://jenkins/job/folder/job/job/7/api/json?tree=artifacts%5BfileName%2CrelativePath%5D",
        httpClient.url);
    assertEquals(2, artifacts.size());
    assertEquals("app.jar", artifacts.getArtifacts().get(0).getFileName());
    assertEquals("reports/unit/report.txt", artifacts.getArtifacts().get(1).getRelativePath());
  }

  @Test
  public void getArtifactsReturnsEmptyWhenArrayMissing() throws Exception {
    StubResponseHttpClient httpClient = new StubResponseHttpClient();
    httpClient.body = "{}";
    JenkinsClient client = new JenkinsClient(new StaticSettingsProvider(), httpClient);

    assertTrue(client.getArtifacts("job", 3).isEmpty());
  }

  @Test
  public void streamArtifactEncodesEachPathSegment() throws Exception {
    StubResponseHttpClient httpClient = new StubResponseHttpClient();
    httpClient.streamText = "bytes";
    JenkinsClient client = new JenkinsClient(new StaticSettingsProvider(), httpClient);
    final StringBuilder captured = new StringBuilder();

    client.streamArtifact("folder/job", 7, "dir with space/report #1.txt",
        new BridgeHttpClient.StreamHandler() {
          public void handle(InputStream inputStream) throws java.io.IOException {
            int read;
            while ((read = inputStream.read()) != -1) {
              captured.append((char)read);
            }
          }
        });

    assertEquals("http://jenkins/job/folder/job/job/7/artifact/dir%20with%20space/report%20%231.txt",
        httpClient.url);
    assertEquals("bytes", captured.toString());
  }

  @Test(expected = BridgeHttpException.class)
  public void streamArtifactPropagatesHttpFailures() throws Exception {
    JenkinsClient client = new JenkinsClient(new StaticSettingsProvider(), new NotFoundHttpClient());

    client.streamArtifact("job", 7, "missing.bin", new BridgeHttpClient.StreamHandler() {
      public void handle(InputStream inputStream) {
        // never reached
      }
    });
  }

  @Test
  public void progressiveLogBuildsUrlAndParsesHeaders() throws Exception {
    StubResponseHttpClient httpClient = new StubResponseHttpClient();
    httpClient.body = "new log line\n";
    httpClient.headers.put("X-Text-Size", "2048");
    httpClient.headers.put("X-More-Data", "true");
    JenkinsClient client = new JenkinsClient(new StaticSettingsProvider(), httpClient);

    JenkinsLogChunk chunk = client.getProgressiveLog("folder/job", 7, 1024);

    assertEquals("http://jenkins/job/folder/job/job/7/logText/progressiveText?start=1024", httpClient.url);
    assertEquals("new log line\n", chunk.getText());
    assertEquals(2048L, chunk.getNextStart());
    assertTrue(chunk.hasMoreData());
  }

  @Test
  public void progressiveLogFallsBackToBodyLengthWhenSizeHeaderMissing() throws Exception {
    StubResponseHttpClient httpClient = new StubResponseHttpClient();
    httpClient.body = "abcde";
    JenkinsClient client = new JenkinsClient(new StaticSettingsProvider(), httpClient);

    JenkinsLogChunk chunk = client.getProgressiveLog("job", 3, 100);

    assertEquals(105L, chunk.getNextStart());
    assertFalse(chunk.hasMoreData());
  }

  @Test
  public void progressiveLogParsesMoreDataCaseInsensitively() throws Exception {
    StubResponseHttpClient httpClient = new StubResponseHttpClient();
    httpClient.body = "";
    httpClient.headers.put("x-more-data", "TRUE");
    JenkinsClient client = new JenkinsClient(new StaticSettingsProvider(), httpClient);

    JenkinsLogChunk chunk = client.getProgressiveLog("job", 3, 0);

    assertTrue(chunk.hasMoreData());
  }

  @Test
  public void progressiveLogStripsJenkinsConsoleNotes() throws Exception {
    String esc = "";
    StubResponseHttpClient httpClient = new StubResponseHttpClient();
    httpClient.body = esc + "[8mha:////AAAA==" + esc + "[0m[Pipeline] Start of Pipeline\nplain line\n";
    httpClient.headers.put("X-Text-Size", "5000");
    JenkinsClient client = new JenkinsClient(new StaticSettingsProvider(), httpClient);

    JenkinsLogChunk chunk = client.getProgressiveLog("job", 7, 100);

    // Console note removed; visible text kept; offset still driven by X-Text-Size (raw byte size).
    assertEquals("[Pipeline] Start of Pipeline\nplain line\n", chunk.getText());
    assertEquals(5000L, chunk.getNextStart());
  }

  @Test
  public void stripConsoleNotesRemovesConcealBlocksAcrossLines() {
    String esc = "";
    String input = esc + "[8mha:AAA==" + esc + "[0mStarted by user Ahmed\n"
        + esc + "[8mha:BBB==" + esc + "[0m[Pipeline] node\n";

    assertEquals("Started by user Ahmed\n[Pipeline] node\n", JenkinsClient.stripConsoleNotes(input));
    assertEquals("plain text", JenkinsClient.stripConsoleNotes("plain text"));
    assertEquals("", JenkinsClient.stripConsoleNotes(""));
    assertEquals("", JenkinsClient.stripConsoleNotes(null));
  }

  @Test
  public void getBuildNumbersUsesBuildsTreeAndParsesNumbers() throws Exception {
    StubResponseHttpClient httpClient = new StubResponseHttpClient();
    httpClient.body = "{\"builds\":[{\"number\":50},{\"number\":49},{\"number\":48}]}";
    JenkinsClient client = new JenkinsClient(new StaticSettingsProvider(), httpClient);

    List<Integer> numbers = client.getBuildNumbers("folder/job");

    assertEquals("http://jenkins/job/folder/job/job/api/json?tree=builds%5Bnumber%5D", httpClient.url);
    assertEquals(Arrays.asList(50, 49, 48), numbers);
  }

  @Test
  public void getBuildsUsesBuildsTreeAndParsesIdentityMetadata() throws Exception {
    StubResponseHttpClient httpClient = new StubResponseHttpClient();
    httpClient.body = "{\"builds\":["
        + "{\"number\":50,\"timestamp\":1710000000050,\"url\":\"http://jenkins/job/job/50/\"},"
        + "{\"number\":49,\"timestamp\":1710000000049,\"url\":\"http://jenkins/job/job/49/\"}"
        + "]}";
    JenkinsClient client = new JenkinsClient(new StaticSettingsProvider(), httpClient);

    List<JenkinsBuildInfo> builds = client.getBuilds("folder/job");

    assertEquals("http://jenkins/job/folder/job/job/api/json?tree=builds%5Bnumber%2Ctimestamp%2Curl%5D", httpClient.url);
    assertEquals(2, builds.size());
    assertEquals(50, builds.get(0).getNumber());
    assertEquals(1710000000050L, builds.get(0).getTimestamp());
    assertEquals("http://jenkins/job/job/50/", builds.get(0).getUrl());
  }

  @Test
  public void getAllBuildNumbersUsesAllBuildsTree() throws Exception {
    StubResponseHttpClient httpClient = new StubResponseHttpClient();
    httpClient.body = "{\"allBuilds\":[{\"number\":2},{\"number\":1}]}";
    JenkinsClient client = new JenkinsClient(new StaticSettingsProvider(), httpClient);

    List<Integer> numbers = client.getAllBuildNumbers("job");

    assertEquals("http://jenkins/job/job/api/json?tree=allBuilds%5Bnumber%5D", httpClient.url);
    assertEquals(Arrays.asList(2, 1), numbers);
  }

  @Test
  public void getAllBuildsUsesAllBuildsTreeAndParsesIdentityMetadata() throws Exception {
    StubResponseHttpClient httpClient = new StubResponseHttpClient();
    httpClient.body = "{\"allBuilds\":[{\"number\":2,\"timestamp\":1710000000002}]}";
    JenkinsClient client = new JenkinsClient(new StaticSettingsProvider(), httpClient);

    List<JenkinsBuildInfo> builds = client.getAllBuilds("job");

    assertEquals("http://jenkins/job/job/api/json?tree=allBuilds%5Bnumber%2Ctimestamp%2Curl%5D", httpClient.url);
    assertEquals(1, builds.size());
    assertEquals(2, builds.get(0).getNumber());
    assertEquals(1710000000002L, builds.get(0).getTimestamp());
  }


  @Test
  public void listJobsBuildsRootUrlWhenFolderBlank() throws Exception {
    StubResponseHttpClient httpClient = new StubResponseHttpClient();
    httpClient.body = "{\"jobs\":[]}";
    JenkinsClient client = new JenkinsClient(new StaticSettingsProvider(), httpClient);

    client.listJobs("");

    assertEquals("http://jenkins/api/json?tree=jobs%5Bname%2CfullName%2Curl%2C_class%2Cbuildable%2Ccolor%5D",
        httpClient.url);
  }

  @Test
  public void listJobsBuildsFolderUrlAndClassifiesEntries() throws Exception {
    StubResponseHttpClient httpClient = new StubResponseHttpClient();
    httpClient.body = "{\"jobs\":["
        + "{\"name\":\"free\",\"fullName\":\"team/free\",\"_class\":\"hudson.model.FreeStyleProject\"},"
        + "{\"name\":\"sub\",\"fullName\":\"team/sub\",\"_class\":\"com.cloudbees.hudson.plugins.folder.Folder\"}"
        + "]}";
    JenkinsClient client = new JenkinsClient(new StaticSettingsProvider(), httpClient);

    List<JenkinsJob> jobs = client.listJobs("team");

    assertEquals("http://jenkins/job/team/api/json?tree=jobs%5Bname%2CfullName%2Curl%2C_class%2Cbuildable%2Ccolor%5D",
        httpClient.url);
    assertEquals(2, jobs.size());
    assertEquals("team/free", jobs.get(0).getFullName());
    assertTrue(jobs.get(0).isImportable());
    assertFalse(jobs.get(1).isImportable());
  }

  @Test
  public void jobUrlBuildsAbsoluteJobPageUrlFromGlobalBase() {
    JenkinsClient client = new JenkinsClient(new StaticSettingsProvider(), new StubResponseHttpClient());

    assertEquals("http://jenkins/job/multi-test/", client.jobUrl("multi-test"));
    assertEquals("http://jenkins/job/team/job/my-pipeline/", client.jobUrl("team/my-pipeline"));
  }

  @Test
  public void artifactUrlBuildsAbsoluteDownloadUrlFromGlobalBase() {
    JenkinsClient client = new JenkinsClient(new StaticSettingsProvider(), new StubResponseHttpClient());

    assertEquals("http://jenkins/job/job/7/artifact/target/app.jar",
        client.artifactUrl("job", 7, "target/app.jar"));
  }

  @Test
  public void artifactUrlBuildsUrlForFolderedJob() {
    JenkinsClient client = new JenkinsClient(new StaticSettingsProvider(), new StubResponseHttpClient());

    assertEquals("http://jenkins/job/folder/job/job/7/artifact/target/app.jar",
        client.artifactUrl("folder/job", 7, "target/app.jar"));
  }

  @Test
  public void artifactUrlEncodesEachPathSegment() {
    JenkinsClient client = new JenkinsClient(new StaticSettingsProvider(), new StubResponseHttpClient());

    assertEquals("http://jenkins/job/folder/job/job/7/artifact/dir%20with%20space/report%20%231.txt",
        client.artifactUrl("folder/job", 7, "dir with space/report #1.txt"));
  }

  @Test
  public void getStagesBuildsWfapiUrlAndParsesStages() throws Exception {
    StubResponseHttpClient httpClient = new StubResponseHttpClient();
    httpClient.body = "{\"status\":\"IN_PROGRESS\",\"stages\":["
        + "{\"id\":\"6\",\"name\":\"Build\",\"status\":\"SUCCESS\",\"startTimeMillis\":1000,\"durationMillis\":500},"
        + "{\"id\":\"12\",\"name\":\"Test\",\"status\":\"IN_PROGRESS\",\"startTimeMillis\":2000,\"durationMillis\":0}"
        + "]}";
    JenkinsClient client = new JenkinsClient(new StaticSettingsProvider(), httpClient);

    JenkinsStages stages = client.getStages("folder/job", 7);

    assertEquals("http://jenkins/job/folder/job/job/7/wfapi/describe", httpClient.url);
    assertTrue(stages.isPipeline());
    assertEquals(2, stages.getStages().size());
    assertEquals("Build", stages.getStages().get(0).getName());
    assertTrue(stages.getStages().get(0).isTerminal());
    assertFalse(stages.getStages().get(1).isTerminal());
  }

  @Test
  public void getStagesReturnsNotPipelineOn404() throws Exception {
    NotFoundHttpClient httpClient = new NotFoundHttpClient();
    JenkinsClient client = new JenkinsClient(new StaticSettingsProvider(), httpClient);

    JenkinsStages stages = client.getStages("job", 3);

    assertFalse(stages.isPipeline());
    assertTrue(stages.getStages().isEmpty());
    assertEquals("http://jenkins/job/job/3/wfapi/describe", httpClient.url);
  }

  @Test
  public void getStageNodesParsesStepNodeIdsWithLogLinks() throws Exception {
    StubResponseHttpClient httpClient = new StubResponseHttpClient();
    httpClient.body = "{\"stageFlowNodes\":["
        + "{\"id\":\"7\",\"_links\":{\"log\":{\"href\":\"a\"}}},"
        + "{\"id\":\"8\",\"_links\":{\"log\":{\"href\":\"b\"}}},"
        + "{\"id\":\"99\",\"_links\":{\"self\":{\"href\":\"c\"}}}"
        + "]}";
    JenkinsClient client = new JenkinsClient(new StaticSettingsProvider(), httpClient);

    JenkinsStageNodes nodes = client.getStageNodes("folder/job", 7, "6");

    assertEquals("http://jenkins/job/folder/job/job/7/execution/node/6/wfapi/describe", httpClient.url);
    assertEquals(Arrays.asList("7", "8"), nodes.getLogNodeIds());
  }

  @Test
  public void getStageLogConcatenatesStepNodeLogsInOrder() throws Exception {
    RoutingHttpClient httpClient = new RoutingHttpClient();
    httpClient.responses.put("/execution/node/6/wfapi/describe",
        "{\"stageFlowNodes\":[{\"id\":\"7\",\"_links\":{\"log\":{\"href\":\"a\"}}},"
            + "{\"id\":\"8\",\"_links\":{\"log\":{\"href\":\"b\"}}}]}");
    httpClient.responses.put("/execution/node/7/wfapi/log", "{\"text\":\"executing step 1\\n\"}");
    httpClient.responses.put("/execution/node/8/wfapi/log", "{\"text\":\"+ sleep 10\\n\"}");
    JenkinsClient client = new JenkinsClient(new StaticSettingsProvider(), httpClient);

    JenkinsStageLog log = client.getStageLog("job", 26, "6");

    assertEquals("executing step 1\n+ sleep 10\n", log.getText());
  }

  @Test
  public void getNodeLogStripsConsoleNotes() throws Exception {
    String esc = "";
    StubResponseHttpClient httpClient = new StubResponseHttpClient();
    httpClient.body = "{\"text\":\"" + esc + "[8mha:AAA==" + esc + "[0m+ mvn test\\nok\\n\"}";
    JenkinsClient client = new JenkinsClient(new StaticSettingsProvider(), httpClient);

    JenkinsStageLog log = client.getNodeLog("folder/job", 7, "8");

    assertEquals("http://jenkins/job/folder/job/job/7/execution/node/8/wfapi/log", httpClient.url);
    assertEquals("+ mvn test\nok\n", log.getText());
  }

  @Test
  public void getPipelineGraphFetchesWfapiNodesAndBuildsStageGraph() throws Exception {
    RoutingHttpClient httpClient = new RoutingHttpClient();
    httpClient.responses.put("/job/job/26/wfapi/describe",
        "{\"stages\":["
            + "{\"id\":\"6\",\"name\":\"Build\",\"status\":\"SUCCESS\",\"startTimeMillis\":1000,\"durationMillis\":500},"
            + "{\"id\":\"9\",\"name\":\"Test\",\"status\":\"IN_PROGRESS\",\"startTimeMillis\":2000,\"durationMillis\":0}"
            + "]}");
    httpClient.responses.put("/execution/node/6/wfapi/describe",
        "{\"id\":\"6\",\"name\":\"Build\",\"status\":\"SUCCESS\",\"parentNodes\":[],"
            + "\"stageFlowNodes\":[{\"id\":\"7\",\"name\":\"sh\",\"parentNodes\":[\"6\"],"
            + "\"_links\":{\"log\":{\"href\":\"x\"}}}]}");
    httpClient.responses.put("/execution/node/9/wfapi/describe",
        "{\"id\":\"9\",\"name\":\"Test\",\"status\":\"IN_PROGRESS\",\"parentNodes\":[{\"id\":\"6\"}],"
            + "\"stageFlowNodes\":[{\"id\":\"10\",\"name\":\"sh\",\"parentNodes\":[{\"id\":\"9\"}],"
            + "\"_links\":{\"log\":{\"href\":\"y\"}}}]}");
    JenkinsClient client = new JenkinsClient(new StaticSettingsProvider(), httpClient);

    JenkinsPipelineGraph graph = client.getPipelineGraph("job", 26, "job#26");

    assertTrue(graph.isPipeline());
    assertEquals(GraphConfidence.EXPLICIT, graph.getConfidence());
    assertEquals(2, graph.getNodes().size());
    assertEquals("job#26:9", graph.getNodes().get(1).getFlowId());
    assertEquals(Arrays.asList("6"), graph.getNodes().get(1).getParentIds());
    assertEquals(Arrays.asList("10"), graph.getNodes().get(1).getLogNodeIds());
  }

  @Test
  public void getPipelineGraphUsesBlueOceanEdgesWhenAvailable() throws Exception {
    RoutingHttpClient httpClient = new RoutingHttpClient();
    httpClient.responses.put("/job/job/42/wfapi/describe",
        "{\"stages\":["
            + "{\"id\":\"6\",\"name\":\"Setup\",\"status\":\"SUCCESS\",\"startTimeMillis\":1000,\"durationMillis\":100},"
            + "{\"id\":\"9\",\"name\":\"Parallel Tests\",\"status\":\"SUCCESS\",\"startTimeMillis\":1200,\"durationMillis\":100},"
            + "{\"id\":\"12\",\"name\":\"Linux Tests\",\"status\":\"SUCCESS\",\"startTimeMillis\":1300,\"durationMillis\":300},"
            + "{\"id\":\"15\",\"name\":\"Windows Tests\",\"status\":\"SUCCESS\",\"startTimeMillis\":1300,\"durationMillis\":300},"
            + "{\"id\":\"18\",\"name\":\"Deploy\",\"status\":\"NOT_EXECUTED\",\"startTimeMillis\":1700,\"durationMillis\":1}"
            + "]}");
    httpClient.responses.put("/execution/node/6/wfapi/describe", "{\"id\":\"6\",\"parentNodes\":[]}");
    httpClient.responses.put("/execution/node/9/wfapi/describe", "{\"id\":\"9\",\"parentNodes\":[]}");
    httpClient.responses.put("/execution/node/12/wfapi/describe", "{\"id\":\"12\",\"parentNodes\":[]}");
    httpClient.responses.put("/execution/node/15/wfapi/describe", "{\"id\":\"15\",\"parentNodes\":[]}");
    httpClient.responses.put("/execution/node/18/wfapi/describe", "{\"id\":\"18\",\"parentNodes\":[]}");
    httpClient.responses.put("/blue/rest/organizations/jenkins/pipelines/job/runs/42/nodes/",
        "["
            + "{\"id\":\"6\",\"displayName\":\"Setup\",\"type\":\"STAGE\",\"result\":\"SUCCESS\",\"edges\":[{\"id\":\"9\"}]},"
            + "{\"id\":\"9\",\"displayName\":\"Parallel Tests\",\"type\":\"STAGE\",\"result\":\"SUCCESS\",\"edges\":[{\"id\":\"12\"},{\"id\":\"15\"}]},"
            + "{\"id\":\"12\",\"displayName\":\"Linux Tests\",\"type\":\"PARALLEL\",\"result\":\"SUCCESS\",\"edges\":[{\"id\":\"18\"}]},"
            + "{\"id\":\"15\",\"displayName\":\"Windows Tests\",\"type\":\"PARALLEL\",\"result\":\"SUCCESS\",\"edges\":[{\"id\":\"18\"}]},"
            + "{\"id\":\"18\",\"displayName\":\"Deploy\",\"type\":\"STAGE\",\"result\":\"NOT_BUILT\",\"edges\":[]}"
            + "]");
    JenkinsClient client = new JenkinsClient(new StaticSettingsProvider(), httpClient);

    JenkinsPipelineGraph graph = client.getPipelineGraph("job", 42, "job#42");

    assertEquals(JenkinsPipelineGraph.SOURCE_BLUE_OCEAN, graph.getSource());
    assertEquals(GraphConfidence.EXPLICIT, graph.getConfidence());
    assertEquals(Arrays.asList("9"), graph.getNodes().get(2).getParentIds());
    assertEquals(Arrays.asList("12", "15"), graph.getNodes().get(4).getParentIds());
    assertEquals("NOT_EXECUTED", graph.getNodes().get(4).getStatus());
  }

  @Test
  public void getPipelineGraphFallsBackToLinearWfapiWhenBlueOceanIsUnavailable() throws Exception {
    RoutingHttpClient httpClient = new RoutingHttpClient();
    httpClient.responses.put("/job/job/43/wfapi/describe",
        "{\"stages\":["
            + "{\"id\":\"6\",\"name\":\"Build\",\"status\":\"SUCCESS\"},"
            + "{\"id\":\"9\",\"name\":\"Test\",\"status\":\"SUCCESS\"}"
            + "]}");
    httpClient.responses.put("/execution/node/6/wfapi/describe", "{\"id\":\"6\",\"parentNodes\":[]}");
    httpClient.responses.put("/execution/node/9/wfapi/describe", "{\"id\":\"9\",\"parentNodes\":[]}");
    JenkinsClient client = new JenkinsClient(new StaticSettingsProvider(), httpClient);

    JenkinsPipelineGraph graph = client.getPipelineGraph("job", 43, "job#43");

    assertEquals(JenkinsPipelineGraph.SOURCE_WFAPI, graph.getSource());
    assertEquals(GraphConfidence.LINEAR_FALLBACK, graph.getConfidence());
    assertEquals(Arrays.asList("6"), graph.getNodes().get(1).getParentIds());
    assertTrue(graph.getDiagnostics().toString().contains("Blue Ocean nodes endpoint returned 404"));
  }

  @Test
  public void getPipelineGraphReturnsUnavailableOnWfapi404() throws Exception {
    NotFoundHttpClient httpClient = new NotFoundHttpClient();
    JenkinsClient client = new JenkinsClient(new StaticSettingsProvider(), httpClient);

    JenkinsPipelineGraph graph = client.getPipelineGraph("job", 3);

    assertFalse(graph.isPipeline());
    assertEquals(GraphConfidence.UNAVAILABLE, graph.getConfidence());
    assertTrue(graph.getNodes().isEmpty());
  }

  @Test
  public void getStageLogReturnsEmptyWhenStageNotMaterialized() throws Exception {
    NotFoundHttpClient httpClient = new NotFoundHttpClient();
    JenkinsClient client = new JenkinsClient(new StaticSettingsProvider(), httpClient);

    JenkinsStageLog log = client.getStageLog("job", 3, "9");

    assertEquals("", log.getText());
  }

  @Test
  public void getCrumbParsesFieldAndValue() throws Exception {
    StubResponseHttpClient httpClient = new StubResponseHttpClient();
    httpClient.body = "{\"crumbRequestField\":\"Jenkins-Crumb\",\"crumb\":\"abc123\"}";
    JenkinsClient client = new JenkinsClient(new StaticSettingsProvider(), httpClient);

    JenkinsCrumb crumb = client.getCrumb();

    assertEquals("http://jenkins/crumbIssuer/api/json", httpClient.url);
    assertTrue(crumb.isPresent());
    assertEquals("Jenkins-Crumb", crumb.getField());
    assertEquals("abc123", crumb.getValue());
  }

  @Test
  public void getCrumbReturnsDisabledOn404() throws Exception {
    NotFoundHttpClient httpClient = new NotFoundHttpClient();
    JenkinsClient client = new JenkinsClient(new StaticSettingsProvider(), httpClient);

    assertFalse(client.getCrumb().isPresent());
  }

  @Test
  public void getJobParametersParsesDefinitions() throws Exception {
    StubResponseHttpClient httpClient = new StubResponseHttpClient();
    httpClient.body = "{\"property\":[{\"parameterDefinitions\":["
        + "{\"name\":\"BRANCH\",\"type\":\"StringParameterDefinition\",\"defaultParameterValue\":{\"value\":\"main\"}},"
        + "{\"name\":\"ENV\",\"type\":\"ChoiceParameterDefinition\",\"defaultParameterValue\":{\"value\":\"dev\"},\"choices\":[\"dev\",\"prod\"]}"
        + "]}]}";
    JenkinsClient client = new JenkinsClient(new StaticSettingsProvider(), httpClient);

    JenkinsJobParameters params = client.getJobParameters("folder/job");

    assertEquals("http://jenkins/job/folder/job/job/api/json?tree=property%5BparameterDefinitions%5Bname%2Ctype%2CdefaultParameterValue%5Bvalue%5D%2Cchoices%5D%5D", httpClient.url);
    assertTrue(params.isParameterized());
    assertEquals(2, params.getParameters().size());
    assertEquals("BRANCH", params.getParameters().get(0).getName());
    assertEquals("main", params.getParameters().get(0).getDefaultValue());
    assertEquals(Arrays.asList("dev", "prod"), params.getParameters().get(1).getChoices());
  }

  @Test
  public void getBuildParametersParsesRunValues() throws Exception {
    StubResponseHttpClient httpClient = new StubResponseHttpClient();
    httpClient.body = "{\"actions\":[{},"
        + "{\"_class\":\"hudson.model.ParametersAction\",\"parameters\":["
        + "{\"_class\":\"hudson.model.StringParameterValue\",\"name\":\"BRANCH\",\"value\":\"feature/x\"},"
        + "{\"_class\":\"hudson.model.BooleanParameterValue\",\"name\":\"RUN_TESTS\",\"value\":true},"
        + "{\"_class\":\"hudson.model.IntegerParameterValue\",\"name\":\"RETRIES\",\"value\":2},"
        + "{\"_class\":\"hudson.model.RunParameterValue\",\"name\":\"UPSTREAM_RUN\",\"value\":\"folder/job#42\"},"
        + "{\"_class\":\"custom.ComplexParameterValue\",\"name\":\"COMPLEX\",\"value\":{\"nested\":\"ignored\"}},"
        + "{\"_class\":\"hudson.model.StringParameterValue\",\"name\":\"EMPTY\",\"value\":null}"
        + "]}]}";
    JenkinsClient client = new JenkinsClient(new StaticSettingsProvider(), httpClient);

    JenkinsBuildParameters params = client.getBuildParameters("folder/job", 12);

    assertEquals("http://jenkins/job/folder/job/job/12/api/json?tree=actions%5Bparameters%5Bname%2Cvalue%2C_class%5D%5D",
        httpClient.url);
    assertEquals(6, params.getParameters().size());
    assertEquals("hudson.model.RunParameterValue", params.getParameters().get(3).getParameterClass());
    assertEquals("feature/x", params.asMap().get("BRANCH"));
    assertEquals("true", params.asMap().get("RUN_TESTS"));
    assertEquals("2", params.asMap().get("RETRIES"));
    assertEquals("folder/job#42", params.asMap().get("UPSTREAM_RUN"));
    assertEquals("", params.asMap().get("COMPLEX"));
    assertEquals("", params.asMap().get("EMPTY"));
  }

  @Test
  public void getBuildParametersReturnsEmptyWhenNoParametersActionExists() throws Exception {
    StubResponseHttpClient httpClient = new StubResponseHttpClient();
    httpClient.body = "{\"actions\":[{},{}]}";
    JenkinsClient client = new JenkinsClient(new StaticSettingsProvider(), httpClient);

    assertTrue(client.getBuildParameters("job", 5).isEmpty());
  }

  @Test
  public void getBuildParametersReturnsEmptyOn404() throws Exception {
    NotFoundHttpClient httpClient = new NotFoundHttpClient();
    JenkinsClient client = new JenkinsClient(new StaticSettingsProvider(), httpClient);

    assertTrue(client.getBuildParameters("job", 5).isEmpty());
    assertEquals("http://jenkins/job/job/5/api/json?tree=actions%5Bparameters%5Bname%2Cvalue%2C_class%5D%5D",
        httpClient.url);
  }

  @Test
  public void triggerBuildWithParametersPostsFormAndAttachesCrumb() throws Exception {
    TriggerHttpClient httpClient = new TriggerHttpClient();
    JenkinsClient client = new JenkinsClient(new StaticSettingsProvider(), httpClient);

    Map<String, String> values = new LinkedHashMap<String, String>();
    values.put("BRANCH", "feature/x");
    values.put("DEPLOY", "true");

    String queueUrl = client.triggerBuild("folder/job", values);

    assertEquals("http://jenkins/job/folder/job/job/buildWithParameters", httpClient.postUrl);
    assertEquals("BRANCH=feature%2Fx&DEPLOY=true", httpClient.postBody);
    assertEquals("abc123", httpClient.postHeaders.get("Jenkins-Crumb"));
    assertEquals("http://jenkins/queue/item/42/", queueUrl);
  }

  @Test
  public void triggerBuildWithoutParametersUsesBuildEndpoint() throws Exception {
    TriggerHttpClient httpClient = new TriggerHttpClient();
    JenkinsClient client = new JenkinsClient(new StaticSettingsProvider(), httpClient);

    String queueUrl = client.triggerBuild("job", new LinkedHashMap<String, String>());

    assertEquals("http://jenkins/job/job/build", httpClient.postUrl);
    assertEquals("", httpClient.postBody);
    assertEquals("http://jenkins/queue/item/42/", queueUrl);
  }

  @Test
  public void triggerBuildOmitsCrumbHeaderWhenDisabled() throws Exception {
    TriggerHttpClient httpClient = new TriggerHttpClient();
    httpClient.crumbDisabled = true;
    JenkinsClient client = new JenkinsClient(new StaticSettingsProvider(), httpClient);

    client.triggerBuild("job", new LinkedHashMap<String, String>());

    assertTrue(httpClient.postHeaders.isEmpty());
  }

  private static class StubResponseHttpClient extends BridgeHttpClient {
    private String url;
    private String body = "";
    private String streamText = "";
    private final Map<String, String> headers = new LinkedHashMap<String, String>();

    @Override
    public BridgeHttpResponse getResponse(String url, String user, String password, String accept) {
      this.url = url;
      return new BridgeHttpResponse(200, body, headers);
    }

    @Override
    public String get(String url, String user, String password, String accept) {
      this.url = url;
      return body;
    }

    @Override
    public void getStream(String url, String user, String password, String accept, StreamHandler handler)
        throws BridgeHttpException {
      this.url = url;
      try {
        handler.handle(new java.io.ByteArrayInputStream(streamText.getBytes("UTF-8")));
      } catch (java.io.IOException e) {
        throw new BridgeHttpException("GET", url, e);
      }
    }
  }

  // Serves the crumb on GET and captures the trigger POST, returning a 201 + Location header.
  private static class TriggerHttpClient extends BridgeHttpClient {
    boolean crumbDisabled = false;
    String postUrl;
    String postBody;
    Map<String, String> postHeaders;

    @Override
    public String get(String url, String user, String password, String accept) throws BridgeHttpException {
      if (url.contains("/crumbIssuer/")) {
        if (crumbDisabled) {
          throw new BridgeHttpException("GET", url, 404, "no crumb");
        }
        return "{\"crumbRequestField\":\"Jenkins-Crumb\",\"crumb\":\"abc123\"}";
      }
      return "{}";
    }

    @Override
    public BridgeHttpResponse postResponse(String url, String user, String password, String body,
                                           String contentType, String accept, Map<String, String> headers) {
      this.postUrl = url;
      this.postBody = body;
      this.postHeaders = headers;
      Map<String, String> responseHeaders = new LinkedHashMap<String, String>();
      responseHeaders.put("Location", "http://jenkins/queue/item/42/");
      return new BridgeHttpResponse(201, "", responseHeaders);
    }
  }

  // Routes each GET to a canned body by matching a URL substring; unmatched URLs 404.
  private static class RoutingHttpClient extends BridgeHttpClient {
    private final Map<String, String> responses = new LinkedHashMap<String, String>();
    private String url;

    @Override
    public String get(String url, String user, String password, String accept) throws BridgeHttpException {
      this.url = url;
      for (Map.Entry<String, String> entry : responses.entrySet()) {
        if (url.contains(entry.getKey())) {
          return entry.getValue();
        }
      }
      throw new BridgeHttpException("GET", url, 404, "not found");
    }
  }

  private static class StaticSettingsProvider extends JenkinsBridgeSettingsProvider {
    StaticSettingsProvider() {
      super(null);
    }

    @Override
    public JenkinsBridgeSettings load() {
      try {
        Constructor<JenkinsBridgeSettings> constructor = JenkinsBridgeSettings.class.getDeclaredConstructor(
            boolean.class,
            String.class,
            String.class,
            String.class,
            String.class,
            String.class,
            String.class,
            String.class,
            String.class,
            int.class,
            int.class,
            String.class,
            String.class
        );
        constructor.setAccessible(true);
        return constructor.newInstance(
            true,
            "http://jenkins",
            "jenkins-user",
            "jenkins-token",
            "job",
            "http://teamcity",
            "teamcity-user",
            "teamcity-password",
            "buildType",
            10,
            1,
            "Europe/Berlin",
            ""
        );
      } catch (Exception e) {
        throw new AssertionError(e);
      }
    }
  }

  private static class NotFoundHttpClient extends BridgeHttpClient {
    private String url;

    @Override
    public String get(String url, String user, String password, String accept) throws BridgeHttpException {
      this.url = url;
      throw new BridgeHttpException("GET", url, 404, "not found");
    }

    @Override
    public void getStream(String url, String user, String password, String accept, StreamHandler handler)
        throws BridgeHttpException {
      this.url = url;
      throw new BridgeHttpException("GET", url, 404, "not found");
    }
  }
}
