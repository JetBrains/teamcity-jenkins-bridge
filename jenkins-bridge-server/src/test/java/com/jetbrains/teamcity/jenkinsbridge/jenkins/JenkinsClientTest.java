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
import com.jetbrains.teamcity.jenkinsbridge.model.JenkinsQueueBuildResolution;
import com.jetbrains.teamcity.jenkinsbridge.model.JenkinsStageLog;
import com.jetbrains.teamcity.jenkinsbridge.model.JenkinsStageNodes;
import com.jetbrains.teamcity.jenkinsbridge.model.JenkinsStageStep;
import com.jetbrains.teamcity.jenkinsbridge.model.JenkinsStages;
import com.jetbrains.teamcity.jenkinsbridge.model.JenkinsTestReport;
import com.jetbrains.teamcity.jenkinsbridge.model.JenkinsVcsInfo;
import com.jetbrains.teamcity.jenkinsbridge.model.*;
import com.jetbrains.teamcity.jenkinsbridge.connection.JenkinsConnection;
import com.jetbrains.teamcity.jenkinsbridge.xml.JaxbUnmarshaller;
import com.jetbrains.teamcity.jenkinsbridge.xml.XmlReaderFactory;
import org.junit.Test;

import java.io.InputStream;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;
import static org.junit.Assert.assertTrue;

public class JenkinsClientTest {
  private static JaxbUnmarshaller newJaxbUnmarshaller() {
    return new JaxbUnmarshaller(new XmlReaderFactory());
  }

  @Test
  public void returnsEmptyReportWhenJenkinsHasNoTestReport() throws Exception {
    NotFoundHttpClient httpClient = new NotFoundHttpClient();
    JenkinsClient client = new JenkinsClient(testConnection(), httpClient, newJaxbUnmarshaller());

    JenkinsTestReport report = client.getTestReport("folder/job", 7);

    assertTrue(report.isEmpty());
    assertEquals("http://jenkins/job/folder/job/job/7/testReport/api/json?tree=suites%5Bname%2Ccases%5BclassName%2Cname%2Cstatus%2Cduration%2CerrorDetails%2CerrorStackTrace%2CskippedMessage%2Cstdout%2Cstderr%5D%5D", httpClient.url);
  }

  @Test
  public void getBuildInfoRejectsMalformedJsonAsJenkinsDataException() throws Exception {
    StubResponseHttpClient httpClient = new StubResponseHttpClient();
    httpClient.body = "{";
    JenkinsClient client = new JenkinsClient(testConnection(), httpClient, newJaxbUnmarshaller());

    try {
      client.getBuildInfo("job", 7);
      fail("Expected JenkinsDataException");
    } catch (JenkinsDataException expected) {
      assertTrue(expected.getMessage().contains("build information"));
    }
  }

  @Test
  public void getBuildInfoAllowsMissingResultForRunningBuild() throws Exception {
    StubResponseHttpClient httpClient = new StubResponseHttpClient();
    httpClient.body = "{\"number\":7,\"building\":true,\"result\":null}";
    JenkinsClient client = new JenkinsClient(testConnection(), httpClient, newJaxbUnmarshaller());

    JenkinsBuildInfo build = client.getBuildInfo("job", 7);

    assertTrue(build.isBuilding());
    assertNull(build.getResult());
  }

  @Test
  public void rawJsonEndpointsRejectMalformedOrNonObjectResponsesAsJenkinsDataException() throws Exception {
    assertJenkinsDataFailure("{", client -> client.getBuildNumbers("job"));
    assertJenkinsDataFailure("[]", client -> client.listJobs(""));
    assertJenkinsDataFailure("{", client -> client.getJobParameters("job"));
    assertJenkinsDataFailure("[]", client -> client.getCrumb());
    assertJenkinsDataFailure("{", client -> client.resolveQueuedBuildNumber("http://jenkins/queue/item/7/"));
  }

  @Test
  public void getPipelineGraphRejectsMalformedJsonAsJenkinsDataException() throws Exception {
    RoutingHttpClient httpClient = new RoutingHttpClient();
    httpClient.responses.put("/blue/rest/organizations/jenkins/pipelines/job/runs/7/nodes/", "{");
    JenkinsClient client = new JenkinsClient(testConnection(), httpClient, newJaxbUnmarshaller());

    try {
      client.getPipelineGraph("job", 7);
      fail("Expected JenkinsDataException");
    } catch (JenkinsDataException expected) {
      assertTrue(expected.getMessage().contains("Blue Ocean Pipeline graph"));
    }
  }

  @Test
  public void getArtifactsParsesArchivedArtifacts() throws Exception {
    StubResponseHttpClient httpClient = new StubResponseHttpClient();
    httpClient.body = "{\"artifacts\":["
        + "{\"fileName\":\"app.jar\",\"relativePath\":\"target/app.jar\"},"
        + "{\"fileName\":\"report.txt\",\"relativePath\":\"reports/unit/report.txt\"}"
        + "]}";
    JenkinsClient client = new JenkinsClient(testConnection(), httpClient, newJaxbUnmarshaller());

    JenkinsArtifacts artifacts = client.getArtifacts("folder/job", 7);

    assertEquals("http://jenkins/job/folder/job/job/7/api/json?tree=artifacts%5BfileName%2CrelativePath%5D",
        httpClient.url);
    assertEquals(2, artifacts.size());
    assertEquals("app.jar", artifacts.getArtifacts().get(0).fileName());
    assertEquals("reports/unit/report.txt", artifacts.getArtifacts().get(1).relativePath());
  }

  @Test
  public void getArtifactsReturnsEmptyWhenArrayMissing() throws Exception {
    StubResponseHttpClient httpClient = new StubResponseHttpClient();
    httpClient.body = "{}";
    JenkinsClient client = new JenkinsClient(testConnection(), httpClient, newJaxbUnmarshaller());

    assertTrue(client.getArtifacts("job", 3).isEmpty());
  }

  @Test
  public void getBuildVcsParsesGitRepositories() throws Exception {
    StubResponseHttpClient httpClient = new StubResponseHttpClient();
    httpClient.body = "{\"actions\":[{},"
        + "{\"_class\":\"hudson.plugins.git.util.BuildData\","
        + "\"lastBuiltRevision\":{\"SHA1\":\"8f2fd2f092c3b923e1c7b42c0d6b87aea49d2771\","
        + "\"branch\":[{\"name\":\"refs/remotes/origin/main\"}]},"
        + "\"remoteUrls\":[\"git@github.com:org/repo.git\"]},"
        + "{\"_class\":\"hudson.tasks.junit.TestResultAction\"},"
        + "{\"_class\":\"hudson.plugins.git.util.BuildData\","
        + "\"lastBuiltRevision\":{\"SHA1\":\"15651bb594728915e9c6a0786e969bd532478640\","
        + "\"branch\":[{\"name\":\"refs/remotes/origin/master\"}]},"
        + "\"remoteUrls\":[\"https://github.com/org/other-repo.git\"]}"
        + "]}";
    JenkinsClient client = new JenkinsClient(testConnection(), httpClient, newJaxbUnmarshaller());

    JenkinsVcsInfo vcsInfo = client.getBuildVcs("folder/job", 7);

    assertEquals("http://jenkins/job/folder/job/job/7/api/json?tree="
            + "actions%5B_class%2ClastBuiltRevision%5BSHA1%2Cbranch%5Bname%5D%5D%2CremoteUrls%2C"
            + "revision%5Bhash%2Chead%5Bname%5D%5D%5D",
        httpClient.url);
    assertEquals(2, vcsInfo.repositories().size());
    assertEquals("8f2fd2f092c3b923e1c7b42c0d6b87aea49d2771", vcsInfo.repositories().get(0).sha1());
    assertEquals("refs/remotes/origin/main", vcsInfo.repositories().get(0).rawBranchName());
    assertEquals("git@github.com:org/repo.git",
        vcsInfo.repositories().get(0).remoteUrl());
    assertEquals("https://github.com/org/other-repo.git",
        vcsInfo.repositories().get(1).remoteUrl());
  }

  // Note: Here is where tests for SVN, Mercurial, and Perforce can be added if they are implemented

  @Test
  public void getBuildVcsReturnsEmptyOn404() throws Exception {
    NotFoundHttpClient httpClient = new NotFoundHttpClient();
    JenkinsClient client = new JenkinsClient(testConnection(), httpClient, newJaxbUnmarshaller());

    assertTrue(client.getBuildVcs("job", 5).repositories().isEmpty());
  }

  @Test
  public void streamArtifactEncodesEachPathSegment() throws Exception {
    StubResponseHttpClient httpClient = new StubResponseHttpClient();
    httpClient.streamText = "bytes";
    JenkinsClient client = new JenkinsClient(testConnection(), httpClient, newJaxbUnmarshaller());
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
    JenkinsClient client = new JenkinsClient(testConnection(), new NotFoundHttpClient(), newJaxbUnmarshaller());

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
    JenkinsClient client = new JenkinsClient(testConnection(), httpClient, newJaxbUnmarshaller());

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
    JenkinsClient client = new JenkinsClient(testConnection(), httpClient, newJaxbUnmarshaller());

    JenkinsLogChunk chunk = client.getProgressiveLog("job", 3, 100);

    assertEquals(105L, chunk.getNextStart());
    assertFalse(chunk.hasMoreData());
  }

  @Test
  public void progressiveLogParsesMoreDataCaseInsensitively() throws Exception {
    StubResponseHttpClient httpClient = new StubResponseHttpClient();
    httpClient.body = "";
    httpClient.headers.put("x-more-data", "TRUE");
    JenkinsClient client = new JenkinsClient(testConnection(), httpClient, newJaxbUnmarshaller());

    JenkinsLogChunk chunk = client.getProgressiveLog("job", 3, 0);

    assertTrue(chunk.hasMoreData());
  }

  @Test
  public void progressiveLogStripsJenkinsConsoleNotes() throws Exception {
    String esc = "";
    StubResponseHttpClient httpClient = new StubResponseHttpClient();
    httpClient.body = esc + "[8mha:////AAAA==" + esc + "[0m[Pipeline] Start of Pipeline\nplain line\n";
    httpClient.headers.put("X-Text-Size", "5000");
    JenkinsClient client = new JenkinsClient(testConnection(), httpClient, newJaxbUnmarshaller());

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
    JenkinsClient client = new JenkinsClient(testConnection(), httpClient, newJaxbUnmarshaller());

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
    JenkinsClient client = new JenkinsClient(testConnection(), httpClient, newJaxbUnmarshaller());

    List<JenkinsBuildInfo> builds = client.getBuilds("folder/job");

    assertEquals("http://jenkins/job/folder/job/job/api/json?tree=builds%5Bnumber%2Ctimestamp%2Curl%2CqueueId%2Cactions%5Bcauses%5BshortDescription%5D%5D%5D", httpClient.url);
    assertEquals(2, builds.size());
    assertEquals(50, builds.get(0).getNumber());
    assertEquals(1710000000050L, builds.get(0).getTimestamp());
    assertEquals("http://jenkins/job/job/50/", builds.get(0).getUrl());
  }

  @Test
  public void getAllBuildNumbersUsesAllBuildsTree() throws Exception {
    StubResponseHttpClient httpClient = new StubResponseHttpClient();
    httpClient.body = "{\"allBuilds\":[{\"number\":2},{\"number\":1}]}";
    JenkinsClient client = new JenkinsClient(testConnection(), httpClient, newJaxbUnmarshaller());

    List<Integer> numbers = client.getAllBuildNumbers("job");

    assertEquals("http://jenkins/job/job/api/json?tree=allBuilds%5Bnumber%5D", httpClient.url);
    assertEquals(Arrays.asList(2, 1), numbers);
  }

  @Test
  public void getAllBuildsUsesAllBuildsTreeAndParsesIdentityMetadata() throws Exception {
    StubResponseHttpClient httpClient = new StubResponseHttpClient();
    httpClient.body = "{\"allBuilds\":[{\"number\":2,\"timestamp\":1710000000002}]}";
    JenkinsClient client = new JenkinsClient(testConnection(), httpClient, newJaxbUnmarshaller());

    List<JenkinsBuildInfo> builds = client.getAllBuilds("job");

    assertEquals("http://jenkins/job/job/api/json?tree=allBuilds%5Bnumber%2Ctimestamp%2Curl%2CqueueId%2Cactions%5Bcauses%5BshortDescription%5D%5D%5D", httpClient.url);
    assertEquals(1, builds.size());
    assertEquals(2, builds.get(0).getNumber());
    assertEquals(1710000000002L, builds.get(0).getTimestamp());
  }


  @Test
  public void listJobsBuildsRootUrlWhenFolderBlank() throws Exception {
    StubResponseHttpClient httpClient = new StubResponseHttpClient();
    httpClient.body = "{\"jobs\":[]}";
    JenkinsClient client = new JenkinsClient(testConnection(), httpClient, newJaxbUnmarshaller());

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
    JenkinsClient client = new JenkinsClient(testConnection(), httpClient, newJaxbUnmarshaller());

    List<JenkinsJob> jobs = client.listJobs("team");

    assertEquals("http://jenkins/job/team/api/json?tree=jobs%5Bname%2CfullName%2Curl%2C_class%2Cbuildable%2Ccolor%5D",
        httpClient.url);
    assertEquals(2, jobs.size());
    assertEquals("team/free", jobs.get(0).getFullName());
    assertTrue(jobs.get(0).isImportable());
    assertFalse(jobs.get(1).isImportable());
  }

  @Test
  public void listJobsRequestsOnlyTheRequestedRange() throws Exception {
    StubResponseHttpClient httpClient = new StubResponseHttpClient();
    httpClient.body = "{\"jobs\":[{\"name\":\"job-100\",\"fullName\":\"job-100\",\"_class\":\"hudson.model.FreeStyleProject\"}]}";
    JenkinsClient client = new JenkinsClient(testConnection(), httpClient, newJaxbUnmarshaller());

    List<JenkinsJob> jobs = client.listJobs("", 100, 100);

    assertEquals("http://jenkins/api/json?tree=jobs%5Bname%2CfullName%2Curl%2C_class%2Cbuildable%2Ccolor%5D%7B100%2C200%7D",
        httpClient.url);
    assertEquals(1, jobs.size());
    assertEquals("job-100", jobs.get(0).getFullName());
  }

  @Test
  public void listJobsSearchFiltersTheRequestedRemoteRange() throws Exception {
    StubResponseHttpClient httpClient = new StubResponseHttpClient();
    httpClient.body = "{\"jobs\":["
        + "{\"name\":\"alpha\",\"fullName\":\"team/alpha\",\"_class\":\"hudson.model.FreeStyleProject\"},"
        + "{\"name\":\"beta\",\"fullName\":\"team/beta\",\"_class\":\"hudson.model.FreeStyleProject\"}]}";
    JenkinsClient client = new JenkinsClient(testConnection(), httpClient, newJaxbUnmarshaller());

    List<JenkinsJob> jobs = client.listJobs("team", 0, 100, "beta");

    assertEquals("http://jenkins/job/team/api/json?tree=jobs%5Bname%2CfullName%2Curl%2C_class%2Cbuildable%2Ccolor%5D%7B0%2C100%7D",
        httpClient.url);
    assertEquals(1, jobs.size());
    assertEquals("team/beta", jobs.get(0).getFullName());
  }

  @Test
  public void jobUrlBuildsAbsoluteJobPageUrlFromGlobalBase() {
    JenkinsClient client = new JenkinsClient(testConnection(), new StubResponseHttpClient(), newJaxbUnmarshaller());

    assertEquals("http://jenkins/job/multi-test/", client.jobUrl("multi-test"));
    assertEquals("http://jenkins/job/team/job/my-pipeline/", client.jobUrl("team/my-pipeline"));
  }

  @Test
  public void artifactUrlBuildsAbsoluteDownloadUrlFromGlobalBase() {
    JenkinsClient client = new JenkinsClient(testConnection(), new StubResponseHttpClient(), newJaxbUnmarshaller());

    assertEquals("http://jenkins/job/job/7/artifact/target/app.jar",
        client.artifactUrl("job", 7, "target/app.jar"));
  }

  @Test
  public void artifactUrlBuildsUrlForFolderedJob() {
    JenkinsClient client = new JenkinsClient(testConnection(), new StubResponseHttpClient(), newJaxbUnmarshaller());

    assertEquals("http://jenkins/job/folder/job/job/7/artifact/target/app.jar",
        client.artifactUrl("folder/job", 7, "target/app.jar"));
  }

  @Test
  public void artifactUrlEncodesEachPathSegment() {
    JenkinsClient client = new JenkinsClient(testConnection(), new StubResponseHttpClient(), newJaxbUnmarshaller());

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
    JenkinsClient client = new JenkinsClient(testConnection(), httpClient, newJaxbUnmarshaller());

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
    JenkinsClient client = new JenkinsClient(testConnection(), httpClient, newJaxbUnmarshaller());

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
    JenkinsClient client = new JenkinsClient(testConnection(), httpClient, newJaxbUnmarshaller());

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
    JenkinsClient client = new JenkinsClient(testConnection(), httpClient, newJaxbUnmarshaller());

    JenkinsStageLog log = client.getStageLog("job", 26, "6");

    assertEquals("executing step 1\n+ sleep 10\n", log.getText());
  }

  @Test
  public void getNodeLogStripsConsoleNotes() throws Exception {
    String esc = "";
    StubResponseHttpClient httpClient = new StubResponseHttpClient();
    httpClient.body = "{\"text\":\"" + esc + "[8mha:AAA==" + esc + "[0m+ mvn test\\nok\\n\"}";
    JenkinsClient client = new JenkinsClient(testConnection(), httpClient, newJaxbUnmarshaller());

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
    JenkinsClient client = new JenkinsClient(testConnection(), httpClient, newJaxbUnmarshaller());

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
    JenkinsClient client = new JenkinsClient(testConnection(), httpClient, newJaxbUnmarshaller());

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
    JenkinsClient client = new JenkinsClient(testConnection(), httpClient, newJaxbUnmarshaller());

    JenkinsPipelineGraph graph = client.getPipelineGraph("job", 43, "job#43");

    assertEquals(JenkinsPipelineGraph.SOURCE_WFAPI, graph.getSource());
    assertEquals(GraphConfidence.LINEAR_FALLBACK, graph.getConfidence());
    assertEquals(Arrays.asList("6"), graph.getNodes().get(1).getParentIds());
    assertTrue(graph.getDiagnostics().toString().contains("Blue Ocean nodes endpoint returned 404"));
  }

  @Test
  public void getPipelineGraphReturnsUnavailableOnWfapi404() throws Exception {
    NotFoundHttpClient httpClient = new NotFoundHttpClient();
    JenkinsClient client = new JenkinsClient(testConnection(), httpClient, newJaxbUnmarshaller());

    JenkinsPipelineGraph graph = client.getPipelineGraph("job", 3);

    assertFalse(graph.isPipeline());
    assertEquals(GraphConfidence.UNAVAILABLE, graph.getConfidence());
    assertTrue(graph.getNodes().isEmpty());
  }

  @Test
  public void getStageLogReturnsEmptyWhenStageNotMaterialized() throws Exception {
    NotFoundHttpClient httpClient = new NotFoundHttpClient();
    JenkinsClient client = new JenkinsClient(testConnection(), httpClient, newJaxbUnmarshaller());

    JenkinsStageLog log = client.getStageLog("job", 3, "9");

    assertEquals("", log.getText());
  }

  @Test
  public void getCrumbParsesFieldAndValue() throws Exception {
    StubResponseHttpClient httpClient = new StubResponseHttpClient();
    httpClient.body = "{\"crumbRequestField\":\"Jenkins-Crumb\",\"crumb\":\"abc123\"}";
    JenkinsClient client = new JenkinsClient(testConnection(), httpClient, newJaxbUnmarshaller());

    JenkinsCrumb crumb = client.getCrumb();

    assertEquals("http://jenkins/crumbIssuer/api/json", httpClient.url);
    assertTrue(crumb.isPresent());
    assertEquals("Jenkins-Crumb", crumb.getField());
    assertEquals("abc123", crumb.getValue());
  }

  @Test
  public void getCrumbReturnsDisabledOn404() throws Exception {
    NotFoundHttpClient httpClient = new NotFoundHttpClient();
    JenkinsClient client = new JenkinsClient(testConnection(), httpClient, newJaxbUnmarshaller());

    assertFalse(client.getCrumb().isPresent());
  }

  @Test
  public void getPullRequestInfoParsesBranchJobConfigXml() {
    StubResponseHttpClient httpClient = new StubResponseHttpClient();
    httpClient.body = "<flow-definition><properties>"
        + "<org.jenkinsci.plugins.workflow.multibranch.BranchJobProperty><branch>"
        + "<head class=\"org.jenkinsci.plugins.github_branch_source.PullRequestSCMHead\">"
        + "<number>1</number><sourceBranch>development</sourceBranch><target><name>master</name></target>"
        + "</head><actions><jenkins.scm.api.metadata.ContributorMetadataAction>"
        + "<contributor>john-doe</contributor>"
        + "</jenkins.scm.api.metadata.ContributorMetadataAction>"
        + "<jenkins.scm.api.metadata.ObjectMetadataAction>"
        + "<objectDisplayName>Merge development into master</objectDisplayName>"
        + "</jenkins.scm.api.metadata.ObjectMetadataAction></actions>"
        + "</branch></org.jenkinsci.plugins.workflow.multibranch.BranchJobProperty>"
        + "</properties></flow-definition>";
    JenkinsClient client = new JenkinsClient(testConnection(), httpClient, newJaxbUnmarshaller());

    var infoResult =
        client.getPullRequestInfo("multibranch-pipeline/PR-1");

    assertEquals("http://jenkins/job/multibranch-pipeline/job/PR-1/config.xml", httpClient.url);
    assertTrue(infoResult.isPresent());
    var info = infoResult.get();
    assertEquals("1", info.number());
    assertEquals("development", info.sourceBranch());
    assertEquals("master", info.targetBranch());
    assertEquals("john-doe", info.author());
    assertEquals("Merge development into master", info.title());
  }

  @Test
  public void getPullRequestInfoReturnsEmptyOn404() {
    NotFoundHttpClient httpClient = new NotFoundHttpClient();
    JenkinsClient client = new JenkinsClient(testConnection(), httpClient, newJaxbUnmarshaller());

    assertFalse(client.getPullRequestInfo("folder/job/PR-1").isPresent());
  }

  @Test
  public void getJobParametersParsesDefinitions() throws Exception {
    StubResponseHttpClient httpClient = new StubResponseHttpClient();
    httpClient.body = "{\"property\":[{\"parameterDefinitions\":["
        + "{\"name\":\"BRANCH\",\"type\":\"StringParameterDefinition\",\"defaultParameterValue\":{\"value\":\"main\"}},"
        + "{\"name\":\"ENV\",\"type\":\"ChoiceParameterDefinition\",\"defaultParameterValue\":{\"value\":\"dev\"},\"choices\":[\"dev\",\"prod\"]}"
        + "]}]}";
    JenkinsClient client = new JenkinsClient(testConnection(), httpClient, newJaxbUnmarshaller());

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
    JenkinsClient client = new JenkinsClient(testConnection(), httpClient, newJaxbUnmarshaller());

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
    JenkinsClient client = new JenkinsClient(testConnection(), httpClient, newJaxbUnmarshaller());

    assertTrue(client.getBuildParameters("job", 5).isEmpty());
  }

  @Test
  public void getBuildParametersPropagates404ForMissingBuild() throws Exception {
    NotFoundHttpClient httpClient = new NotFoundHttpClient();
    JenkinsClient client = new JenkinsClient(testConnection(), httpClient, newJaxbUnmarshaller());

    try {
      client.getBuildParameters("job", 5);
      fail("Expected the missing Jenkins build response to be propagated");
    } catch (BridgeHttpException e) {
      assertEquals(404, e.getStatusCode());
    }
    assertEquals("http://jenkins/job/job/5/api/json?tree=actions%5Bparameters%5Bname%2Cvalue%2C_class%5D%5D",
        httpClient.url);
  }

  @Test
  public void triggerBuildWithParametersPostsFormAndAttachesCrumb() throws Exception {
    TriggerHttpClient httpClient = new TriggerHttpClient();
    JenkinsClient client = new JenkinsClient(testConnection(), httpClient, newJaxbUnmarshaller());

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
    JenkinsClient client = new JenkinsClient(testConnection(), httpClient, newJaxbUnmarshaller());

    String queueUrl = client.triggerBuild("job", new LinkedHashMap<String, String>());

    assertEquals("http://jenkins/job/job/build", httpClient.postUrl);
    assertEquals("", httpClient.postBody);
    assertEquals("http://jenkins/queue/item/42/", queueUrl);
  }

  @Test
  public void triggerBuildWithCauseUsesQueryParameterAndKeepsFormBodySeparate() throws Exception {
    TriggerHttpClient httpClient = new TriggerHttpClient();
    JenkinsClient client = new JenkinsClient(testConnection(), httpClient, newJaxbUnmarshaller());
    Map<String, String> values = new LinkedHashMap<String, String>();
    values.put("BRANCH", "feature/x");

    client.triggerBuildWithQueueId("job", values, "Jenkins Bridge: promotion 42 (node secondary 1)");

    assertEquals("http://jenkins/job/job/buildWithParameters?cause=Jenkins+Bridge%3A+promotion+42+%28node+secondary+1%29",
        httpClient.postUrl);
    assertEquals("BRANCH=feature%2Fx", httpClient.postBody);
  }

  @Test
  public void triggerBuildOmitsCrumbHeaderWhenDisabled() throws Exception {
    TriggerHttpClient httpClient = new TriggerHttpClient();
    httpClient.crumbDisabled = true;
    JenkinsClient client = new JenkinsClient(testConnection(), httpClient, newJaxbUnmarshaller());

    client.triggerBuild("job", new LinkedHashMap<String, String>());

    assertTrue(httpClient.postHeaders.isEmpty());
  }

  @Test
  public void resolveQueuedBuildNumberReturnsExecutableNumber() throws Exception {
    StubResponseHttpClient httpClient = new StubResponseHttpClient();
    httpClient.body = "{\"executable\":{\"number\":42}}";
    JenkinsClient client = new JenkinsClient(testConnection(), httpClient, newJaxbUnmarshaller());

    JenkinsQueueBuildResolution resolution =
        client.resolveQueuedBuildNumber("http://jenkins/queue/item/99/");

    assertEquals("http://jenkins/queue/item/99/api/json", httpClient.url);
    assertTrue(resolution.isResolved());
    assertEquals(42, resolution.getBuildNumber());
  }

  @Test
  public void resolveQueuedBuildNumberReturnsPendingWhenExecutableIsMissing() throws Exception {
    StubResponseHttpClient httpClient = new StubResponseHttpClient();
    httpClient.body = "{\"why\":\"Waiting for next available executor\"}";
    JenkinsClient client = new JenkinsClient(testConnection(), httpClient, newJaxbUnmarshaller());

    JenkinsQueueBuildResolution resolution =
        client.resolveQueuedBuildNumber("http://jenkins/queue/item/99");

    assertEquals("http://jenkins/queue/item/99/api/json", httpClient.url);
    assertTrue(resolution.isPending());
  }

  @Test
  public void resolveQueuedBuildNumberReturnsCancelled() throws Exception {
    StubResponseHttpClient httpClient = new StubResponseHttpClient();
    httpClient.body = "{\"cancelled\":true}";
    JenkinsClient client = new JenkinsClient(testConnection(), httpClient, newJaxbUnmarshaller());

    JenkinsQueueBuildResolution resolution =
        client.resolveQueuedBuildNumber("http://jenkins/queue/item/99/");

    assertTrue(resolution.isCancelled());
  }

  private void assertJenkinsDataFailure(String body, JenkinsClientOperation operation) throws Exception {
    StubResponseHttpClient httpClient = new StubResponseHttpClient();
    httpClient.body = body;
    try {
      operation.run(new JenkinsClient(testConnection(), httpClient, newJaxbUnmarshaller()));
      fail("Expected JenkinsDataException");
    } catch (JenkinsDataException expected) {
      // Expected: malformed JSON and non-object payloads are remote-data failures.
    }
  }

  private interface JenkinsClientOperation {
    void run(JenkinsClient client) throws Exception;
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
  @Test
  public void getStageStepsReturnsJenkinsArgumentDescriptionAlongsideStepLog() throws Exception {
    RoutingHttpClient httpClient = new RoutingHttpClient();
    httpClient.responses.put("/execution/node/6/wfapi/describe",
        "{\"id\":\"6\",\"stageFlowNodes\":["
            + "{\"id\":\"7\",\"name\":\"Print Message\",\"parameterDescription\":\"Release name: demo-release\",\"status\":\"SUCCESS\",\"durationMillis\":27,"
            + "\"_links\":{\"log\":{\"href\":\"/x\"}}},"
            + "{\"id\":\"8\",\"name\":\"Write file\",\"status\":\"SUCCESS\",\"durationMillis\":49,"
            + "\"_links\":{\"log\":{\"href\":\"/y\"}}}"
            + "]}");
    httpClient.responses.put("/execution/node/7/wfapi/log", "{\"text\":\"line A\"}");
    httpClient.responses.put("/execution/node/8/wfapi/log", "{\"text\":\"line B\"}");
    JenkinsClient client = new JenkinsClient(testConnection(), httpClient, newJaxbUnmarshaller());

    List<JenkinsStageStep> steps = client.getStageSteps("job", 42, "6");

    assertEquals(2, steps.size());
    assertEquals("Print Message", steps.get(0).getName());
    assertEquals("Release name: demo-release", steps.get(0).getParameterDescription());
    assertEquals("SUCCESS", steps.get(0).getStatus());
    assertEquals(27L, steps.get(0).getDurationMillis());
    assertEquals("line A", steps.get(0).getLog());
    assertEquals("Write file", steps.get(1).getName());
    assertEquals("", steps.get(1).getParameterDescription());
    assertEquals("line B", steps.get(1).getLog());
  }

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

  private static JenkinsConnection testConnection() {
    return new JenkinsConnection("http://jenkins", "jenkins-user", "jenkins-token");
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
