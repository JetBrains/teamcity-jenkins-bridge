package com.jetbrains.teamcity.jenkinsbridge.teamcity;

import com.google.gson.JsonParser;
import com.jetbrains.teamcity.jenkinsbridge.http.BridgeHttpClient;
import com.jetbrains.teamcity.jenkinsbridge.http.BridgeHttpException;
import com.jetbrains.teamcity.jenkinsbridge.jenkins.JenkinsClient;
import com.jetbrains.teamcity.jenkinsbridge.model.JenkinsArtifact;
import com.jetbrains.teamcity.jenkinsbridge.model.JenkinsArtifacts;
import com.jetbrains.teamcity.jenkinsbridge.model.JenkinsBuildInfo;
import com.jetbrains.teamcity.jenkinsbridge.model.JenkinsStageLog;
import com.jetbrains.teamcity.jenkinsbridge.model.JenkinsStages;
import com.jetbrains.teamcity.jenkinsbridge.persistence.BuildMirror;
import com.jetbrains.teamcity.jenkinsbridge.persistence.BuildMirrorStore;
import jetbrains.buildServer.messages.BuildMessage1;
import jetbrains.buildServer.messages.DefaultMessagesInfo;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

public class TeamCityBuildMirrorServiceTest {
  private final JsonParser parser = new JsonParser();

  @Test
  public void syncStagesIsLiveAndIdempotent() throws Exception {
    CapturingStageReporter reporter = new CapturingStageReporter();
    FakeStageLogClient client = new FakeStageLogClient();
    TeamCityBuildMirrorService service = new TeamCityBuildMirrorService(
        null, null, null, null, null, null, reporter, null, null, null, new NoopStore());

    BuildMirror mirror = new BuildMirror();

    // Poll 1: stage running, first slice of log. Block opens, log appended, not closed.
    client.text = "+ mvn compile";
    service.syncStages(mirror, 1L, stages("Build", "IN_PROGRESS", 1000, 0), client);

    assertEquals(1, reporter.reports.size());
    List<BuildMessage1> first = reporter.reports.get(0);
    assertEquals(2, first.size());
    assertEquals(DefaultMessagesInfo.MSG_BLOCK_START, first.get(0).getTypeId());
    assertEquals("+ mvn compile", first.get(1).getValue().toString());
    assertTrue(mirror.getStages().get("6").isBlockOpened());

    // Poll 2: stage finished, log grew. Only the delta is appended, then the block closes.
    client.text = "+ mvn compileBUILD OK";
    service.syncStages(mirror, 1L, stages("Build", "SUCCESS", 1000, 500), client);

    assertEquals(2, reporter.reports.size());
    List<BuildMessage1> second = reporter.reports.get(1);
    assertEquals("BUILD OK", second.get(0).getValue().toString());
    assertEquals(DefaultMessagesInfo.MSG_BLOCK_END, second.get(second.size() - 1).getTypeId());
    assertTrue(mirror.getStages().get("6").isBlockClosed());

    // Poll 3: nothing changed. No messages emitted at all (idempotent).
    service.syncStages(mirror, 1L, stages("Build", "SUCCESS", 1000, 500), client);
    assertEquals(2, reporter.reports.size());
    assertEquals(21L, mirror.getStages().get("6").getLogOffset());
  }

  @Test
  public void skippedPipelineNodeFinishesRedWithoutBeingCanceled() {
    assertEquals("FAILURE", TeamCityBuildMirrorService.jenkinsResultForPipelineNodeStatus("NOT_EXECUTED"));
  }

  @Test
  public void ensureTeamCityBuildPassesSavedJenkinsParametersToQueuer() throws Exception {
    CapturingQueuer queuer = new CapturingQueuer();
    TeamCityBuildMirrorService service = new TeamCityBuildMirrorService(
        null, new NoExistingBuildClient(), queuer, null, null, null, null, null, null, null, new NoopStore());

    BuildMirror mirror = BuildMirror.create("job#4@1710000000004", "job", buildInfo(4), "buildType", "now");
    Map<String, String> parameters = new LinkedHashMap<String, String>();
    parameters.put("BRANCH", "feature/x");
    mirror.setJenkinsBuildParameters(parameters);

    service.ensureTeamCityBuild(mirror, buildInfo(4), null);

    assertEquals("job", queuer.bridgeParameters.get("jenkins.job"));
    assertEquals("job#4@1710000000004", queuer.bridgeParameters.get("jenkins.build.key"));
    assertEquals("1710000000004", queuer.bridgeParameters.get("jenkins.build.timestamp"));
    assertEquals("feature/x", queuer.jenkinsParameters.get("BRANCH"));
  }

  @Test
  public void syncArtifactsPublishesSafeJenkinsPathsUnderPrefix() throws Exception {
    CapturingArtifactPublisher publisher = new CapturingArtifactPublisher();
    CapturingLogger logger = new CapturingLogger();
    TeamCityBuildMirrorService service = new TeamCityBuildMirrorService(
        null, null, null, null, logger, null, null, publisher, null, null, new NoopStore());

    BuildMirror mirror = BuildMirror.create("job#4@1710000000004", "job", buildInfo(4), "buildType", "now");
    FakeArtifactClient client = new FakeArtifactClient();
    client.contents.put("target/app.jar", "jar-bytes");
    client.contents.put("reports/unit/report.txt", "report");

    service.syncArtifactsIfNeeded(mirror, 77L, artifacts(
        "{\"artifacts\":["
            + "{\"fileName\":\"app.jar\",\"relativePath\":\"target/app.jar\"},"
            + "{\"fileName\":\"report.txt\",\"relativePath\":\"reports/unit/report.txt\"}"
            + "]}"), client);

    assertTrue(mirror.isArtifactsSynced());
    assertEquals("jar-bytes", publisher.published.get("jenkins-artifacts/target/app.jar"));
    assertEquals("report", publisher.published.get("jenkins-artifacts/reports/unit/report.txt"));
    assertTrue(logger.texts.get(0).contains("Published artifacts: 2"));
  }

  @Test
  public void syncArtifactsMarksEmptyListSynced() throws Exception {
    CapturingArtifactPublisher publisher = new CapturingArtifactPublisher();
    CapturingLogger logger = new CapturingLogger();
    TeamCityBuildMirrorService service = new TeamCityBuildMirrorService(
        null, null, null, null, logger, null, null, publisher, null, null, new NoopStore());

    BuildMirror mirror = BuildMirror.create("job#5@1710000000005", "job", buildInfo(5), "buildType", "now");

    service.syncArtifactsIfNeeded(mirror, 77L, artifacts("{}"), new FakeArtifactClient());

    assertTrue(mirror.isArtifactsSynced());
    assertTrue(publisher.published.isEmpty());
    assertEquals(null, mirror.getArtifactSyncError());
    assertTrue(logger.texts.get(0).contains("Published artifacts: 0"));
  }

  @Test
  public void syncArtifactsRecordsFailuresAndDoesNotRetry() throws Exception {
    CapturingArtifactPublisher publisher = new CapturingArtifactPublisher();
    publisher.failPath = "jenkins-artifacts/bad.bin";
    TeamCityBuildMirrorService service = new TeamCityBuildMirrorService(
        null, null, null, null, new CapturingLogger(), null, null, publisher, null, null, new NoopStore());

    BuildMirror mirror = BuildMirror.create("job#6@1710000000006", "job", buildInfo(6), "buildType", "now");
    FakeArtifactClient client = new FakeArtifactClient();
    client.contents.put("good.bin", "good");
    client.contents.put("bad.bin", "bad");

    service.syncArtifactsIfNeeded(mirror, 77L, artifacts(
        "{\"artifacts\":["
            + "{\"fileName\":\"good.bin\",\"relativePath\":\"good.bin\"},"
            + "{\"fileName\":\"bad.bin\",\"relativePath\":\"bad.bin\"}"
            + "]}"), client);
    service.syncArtifactsIfNeeded(mirror, 77L, artifacts(
        "{\"artifacts\":[{\"fileName\":\"good.bin\",\"relativePath\":\"good.bin\"}]}"), client);

    assertTrue(mirror.isArtifactsSynced());
    assertTrue(mirror.getArtifactSyncError().contains("bad.bin"));
    assertEquals(2, client.streamCalls);
    assertEquals("good", publisher.published.get("jenkins-artifacts/good.bin"));
  }

  @Test
  public void syncArtifactsSkipsUnsafePaths() throws Exception {
    CapturingArtifactPublisher publisher = new CapturingArtifactPublisher();
    TeamCityBuildMirrorService service = new TeamCityBuildMirrorService(
        null, null, null, null, new CapturingLogger(), null, null, publisher, null, null, new NoopStore());

    BuildMirror mirror = BuildMirror.create("job#7@1710000000007", "job", buildInfo(7), "buildType", "now");

    service.syncArtifactsIfNeeded(mirror, 77L, artifacts(
        "{\"artifacts\":["
            + "{\"fileName\":\"a\",\"relativePath\":\"/absolute/a\"},"
            + "{\"fileName\":\"b\",\"relativePath\":\"../b\"},"
            + "{\"fileName\":\"c\",\"relativePath\":\"safe/c\"}"
            + "]}"), new FakeArtifactClient());

    assertEquals(1, publisher.published.size());
    assertTrue(publisher.published.containsKey("jenkins-artifacts/safe/c"));
    assertTrue(mirror.getArtifactSyncError().contains("Skipped unsafe artifact path"));
    assertEquals(null, TeamCityBuildMirrorService.teamCityArtifactPath("../bad"));
    assertEquals("jenkins-artifacts/safe/file.txt",
        TeamCityBuildMirrorService.teamCityArtifactPath("safe/file.txt"));
  }

  @Test
  public void syncArtifactMetadataRegistersArtifactListAndLogsSummary() throws Exception {
    CapturingArtifactListPublisher publisher = new CapturingArtifactListPublisher();
    CapturingLogger logger = new CapturingLogger();
    TeamCityBuildMirrorService service = new TeamCityBuildMirrorService(
        null, null, null, null, logger, null, null, publisher, null, null, new NoopStore());

    BuildMirror mirror = BuildMirror.create("job#8@1710000000008", "job", buildInfo(8), "buildType", "now");

    service.syncArtifactMetadataIfNeeded(mirror, 77L, artifacts(
        "{\"artifacts\":["
            + "{\"fileName\":\"app.jar\",\"relativePath\":\"target/app.jar\"},"
            + "{\"fileName\":\"report.txt\",\"relativePath\":\"reports/unit/report.txt\"}"
            + "]}"));

    assertTrue(mirror.isArtifactsSynced());
    assertNull(mirror.getArtifactSyncError());
    assertEquals(2, publisher.published.size());
    assertEquals("target/app.jar", publisher.published.get(0).getRelativePath());
    assertEquals("reports/unit/report.txt", publisher.published.get(1).getRelativePath());
    assertTrue(logger.texts.get(0).contains("Registered artifacts: 2"));
  }

  @Test
  public void syncArtifactMetadataTreatsMissingArtifactsAsEmptyList() throws Exception {
    CapturingArtifactListPublisher publisher = new CapturingArtifactListPublisher();
    CapturingLogger logger = new CapturingLogger();
    TeamCityBuildMirrorService service = new TeamCityBuildMirrorService(
        null, null, null, null, logger, null, null, publisher, null, null, new NoopStore());

    BuildMirror mirror = BuildMirror.create("job#9@1710000000009", "job", buildInfo(9), "buildType", "now");

    service.syncArtifactMetadataIfNeeded(mirror, 77L, artifacts("{}"));

    assertTrue(mirror.isArtifactsSynced());
    assertTrue(publisher.published.isEmpty());
    assertTrue(logger.texts.get(0).contains("Registered artifacts: 0"));
  }

  @Test
  public void syncArtifactMetadataIsIdempotentOnceSynced() throws Exception {
    CapturingArtifactListPublisher publisher = new CapturingArtifactListPublisher();
    TeamCityBuildMirrorService service = new TeamCityBuildMirrorService(
        null, null, null, null, new CapturingLogger(), null, null, publisher, null, null, new NoopStore());

    BuildMirror mirror = BuildMirror.create("job#10@1710000000010", "job", buildInfo(10), "buildType", "now");

    service.syncArtifactMetadataIfNeeded(mirror, 77L, artifacts(
        "{\"artifacts\":[{\"fileName\":\"a\",\"relativePath\":\"a\"}]}"));
    service.syncArtifactMetadataIfNeeded(mirror, 77L, artifacts(
        "{\"artifacts\":[{\"fileName\":\"a\",\"relativePath\":\"a\"},{\"fileName\":\"b\",\"relativePath\":\"b\"}]}"));

    assertEquals(1, publisher.published.size());
  }

  @Test
  public void syncArtifactMetadataRecordsFailureWhenPublishListThrows() throws Exception {
    CapturingArtifactListPublisher publisher = new CapturingArtifactListPublisher();
    publisher.fail = true;
    CapturingLogger logger = new CapturingLogger();
    TeamCityBuildMirrorService service = new TeamCityBuildMirrorService(
        null, null, null, null, logger, null, null, publisher, null, null, new NoopStore());

    BuildMirror mirror = BuildMirror.create("job#11@1710000000011", "job", buildInfo(11), "buildType", "now");

    service.syncArtifactMetadataIfNeeded(mirror, 77L, artifacts(
        "{\"artifacts\":[{\"fileName\":\"a\",\"relativePath\":\"a\"}]}"));

    assertTrue(mirror.isArtifactsSynced());
    assertTrue(mirror.getArtifactSyncError().contains("IOException"));
    assertTrue(logger.texts.get(0).contains("Failures: 1"));
  }

  private JenkinsStages stages(String name, String status, long start, long duration) {
    String json = "{\"stages\":[{\"id\":\"6\",\"name\":\"" + name + "\",\"status\":\"" + status
        + "\",\"startTimeMillis\":" + start + ",\"durationMillis\":" + duration + "}]}";
    return JenkinsStages.fromJson(parser.parse(json).getAsJsonObject());
  }

  private JenkinsArtifacts artifacts(String json) {
    return JenkinsArtifacts.fromJson(parser.parse(json).getAsJsonObject());
  }

  private JenkinsBuildInfo buildInfo(int number) {
    String json = "{\"number\":" + number + ",\"building\":true,"
        + "\"timestamp\":" + (1710000000000L + number) + ","
        + "\"url\":\"http://jenkins/job/job/" + number + "/\"}";
    return JenkinsBuildInfo.fromJson(parser.parse(json).getAsJsonObject());
  }

  private static class CapturingStageReporter extends TeamCityStageReporter {
    final List<List<BuildMessage1>> reports = new ArrayList<List<BuildMessage1>>();

    CapturingStageReporter() {
      super(null, null);
    }

    @Override
    public void report(long buildId, List<BuildMessage1> messages) {
      reports.add(new ArrayList<BuildMessage1>(messages));
    }
  }

  private static class FakeStageLogClient extends JenkinsClient {
    String text = "";

    FakeStageLogClient() {
      super(null, null);
    }

    @Override
    public JenkinsStageLog getStageLog(String jobName, int buildNumber, String stageId) {
      return JenkinsStageLog.of(text);
    }
  }

  private static class FakeArtifactClient extends JenkinsClient {
    final Map<String, String> contents = new LinkedHashMap<String, String>();
    int streamCalls;

    FakeArtifactClient() {
      super(null, null);
    }

    @Override
    public void streamArtifact(
        String jobName,
        int buildNumber,
        String relativePath,
        BridgeHttpClient.StreamHandler handler
    ) throws BridgeHttpException {
      streamCalls++;
      String text = contents.get(relativePath);
      if (text == null) {
        text = "";
      }
      try {
        handler.handle(new ByteArrayInputStream(text.getBytes("UTF-8")));
      } catch (IOException e) {
        throw new BridgeHttpException("GET", relativePath, e);
      }
    }
  }

  private static class CapturingArtifactPublisher extends TeamCityArtifactPublisher {
    final Map<String, String> published = new LinkedHashMap<String, String>();
    String failPath;

    CapturingArtifactPublisher() {
      super(null, null);
    }

    @Override
    public void publishArtifact(long buildId, String artifactPath, InputStream inputStream) throws IOException {
      if (artifactPath.equals(failPath)) {
        throw new IOException("boom");
      }
      StringBuilder text = new StringBuilder();
      int read;
      while ((read = inputStream.read()) != -1) {
        text.append((char)read);
      }
      published.put(artifactPath, text.toString());
    }
  }

  private static class CapturingArtifactListPublisher extends TeamCityArtifactPublisher {
    List<JenkinsArtifact> published = new ArrayList<>();
    boolean fail;

    CapturingArtifactListPublisher() {
      super(null, null);
    }

    @Override
    public void publishArtifactList(long buildId, List<JenkinsArtifact> artifacts) throws IOException {
      if (fail) {
        throw new IOException("boom");
      }
      published = new ArrayList<>(artifacts);
    }
  }

  private static class CapturingLogger extends TeamCityBuildLogger {
    final List<String> texts = new ArrayList<String>();

    CapturingLogger() {
      super(null, null);
    }

    @Override
    public void addBuildLog(long buildId, String text) {
      texts.add(text);
    }
  }

  private static class NoopStore extends BuildMirrorStore {
    NoopStore() {
      super(null, null);
    }

    @Override
    public void saveMirror(BuildMirror mirror) {
      // no-op: tests assert on the in-memory mirror, not on disk
    }
  }

  private static class NoExistingBuildClient extends TeamCityClient {
    NoExistingBuildClient() {
      super(null, null);
    }

    @Override
    public Long findBuildIdByJenkinsBuildKey(String jenkinsBuildKey) {
      return null;
    }
  }

  private static class CapturingQueuer extends TeamCityBuildQueuer {
    Map<String, String> bridgeParameters;
    Map<String, String> jenkinsParameters;

    CapturingQueuer() {
      super(null, null);
    }

    @Override
    public long queueAgentlessBuild(
        String buildTypeId,
        Map<String, String> properties,
        Map<String, String> jenkinsBuildParameters
    ) {
      bridgeParameters = new LinkedHashMap<String, String>(properties);
      jenkinsParameters = new LinkedHashMap<String, String>(jenkinsBuildParameters);
      return 55L;
    }
  }
}
