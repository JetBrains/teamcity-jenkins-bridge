package com.jetbrains.teamcity.jenkinsbridge.integration;

import com.jetbrains.teamcity.jenkinsbridge.http.BridgeHttpException;
import com.jetbrains.teamcity.jenkinsbridge.jenkins.JenkinsClient;
import com.jetbrains.teamcity.jenkinsbridge.jenkins.JenkinsDataException;
import com.jetbrains.teamcity.jenkinsbridge.model.JenkinsArtifacts;
import com.jetbrains.teamcity.jenkinsbridge.model.JenkinsBuildInfo;
import com.jetbrains.teamcity.jenkinsbridge.model.JenkinsBuildParameters;
import com.jetbrains.teamcity.jenkinsbridge.model.JenkinsLogChunk;
import com.jetbrains.teamcity.jenkinsbridge.model.JenkinsStages;
import com.jetbrains.teamcity.jenkinsbridge.model.JenkinsTestReport;
import com.jetbrains.teamcity.jenkinsbridge.model.JenkinsVcsInfo;

/** Reusable scripted Jenkins boundary for orchestration integration tests. */
final class JenkinsScenarioClient extends JenkinsClient {
  private JenkinsStages stages = JenkinsStages.notPipeline();
  private JenkinsBuildParameters buildParameters = JenkinsBuildParameters.empty();
  private JenkinsLogChunk progressiveLog = new JenkinsLogChunk("", 0L, false);
  private JenkinsTestReport testReport = JenkinsTestReport.empty();
  private JenkinsArtifacts artifacts = JenkinsArtifacts.empty();
  private JenkinsVcsInfo vcsInfo = JenkinsVcsInfo.empty();

  private BridgeHttpException stagesHttpFailure;
  private JenkinsDataException stagesDataFailure;
  private BridgeHttpException buildParametersHttpFailure;
  private JenkinsDataException buildParametersDataFailure;
  private BridgeHttpException logHttpFailure;
  private BridgeHttpException testReportHttpFailure;
  private JenkinsDataException testReportDataFailure;
  private BridgeHttpException artifactsHttpFailure;
  private JenkinsDataException artifactsDataFailure;
  private BridgeHttpException vcsHttpFailure;
  private JenkinsDataException vcsDataFailure;

  JenkinsScenarioClient() {
    super(null, null, null);
  }

  JenkinsScenarioClient withStages(JenkinsStages value) {
    stages = value;
    return this;
  }

  JenkinsScenarioClient withBuildParameters(JenkinsBuildParameters value) {
    buildParameters = value;
    return this;
  }

  JenkinsScenarioClient withProgressiveLog(JenkinsLogChunk value) {
    progressiveLog = value;
    return this;
  }

  JenkinsScenarioClient withTestReport(JenkinsTestReport value) {
    testReport = value;
    return this;
  }

  JenkinsScenarioClient withArtifacts(JenkinsArtifacts value) {
    artifacts = value;
    return this;
  }

  JenkinsScenarioClient withVcsInfo(JenkinsVcsInfo value) {
    vcsInfo = value;
    return this;
  }

  JenkinsScenarioClient failStagesWith(BridgeHttpException failure) {
    stagesHttpFailure = failure;
    return this;
  }

  JenkinsScenarioClient failStagesWith(JenkinsDataException failure) {
    stagesDataFailure = failure;
    return this;
  }

  JenkinsScenarioClient failBuildParametersWith(BridgeHttpException failure) {
    buildParametersHttpFailure = failure;
    return this;
  }

  JenkinsScenarioClient failBuildParametersWith(JenkinsDataException failure) {
    buildParametersDataFailure = failure;
    return this;
  }

  JenkinsScenarioClient failLogWith(BridgeHttpException failure) {
    logHttpFailure = failure;
    return this;
  }

  JenkinsScenarioClient failTestReportWith(BridgeHttpException failure) {
    testReportHttpFailure = failure;
    return this;
  }

  JenkinsScenarioClient failTestReportWith(JenkinsDataException failure) {
    testReportDataFailure = failure;
    return this;
  }

  JenkinsScenarioClient failArtifactsWith(BridgeHttpException failure) {
    artifactsHttpFailure = failure;
    return this;
  }

  JenkinsScenarioClient failArtifactsWith(JenkinsDataException failure) {
    artifactsDataFailure = failure;
    return this;
  }

  JenkinsScenarioClient failVcsWith(BridgeHttpException failure) {
    vcsHttpFailure = failure;
    return this;
  }

  JenkinsScenarioClient failVcsWith(JenkinsDataException failure) {
    vcsDataFailure = failure;
    return this;
  }

  @Override
  public JenkinsStages getStages(String jobName, int buildNumber)
      throws BridgeHttpException, JenkinsDataException {
    throwIfPresent(stagesHttpFailure, stagesDataFailure);
    return stages;
  }

  @Override
  public JenkinsBuildParameters getBuildParameters(String jobName, int buildNumber)
      throws BridgeHttpException, JenkinsDataException {
    throwIfPresent(buildParametersHttpFailure, buildParametersDataFailure);
    return buildParameters;
  }

  @Override
  public JenkinsLogChunk getProgressiveLog(String jobName, int buildNumber, long start)
      throws BridgeHttpException {
    if (logHttpFailure != null) {
      throw logHttpFailure;
    }
    return progressiveLog;
  }

  @Override
  public JenkinsTestReport getTestReport(String jobName, int buildNumber)
      throws BridgeHttpException, JenkinsDataException {
    throwIfPresent(testReportHttpFailure, testReportDataFailure);
    return testReport;
  }

  @Override
  public JenkinsArtifacts getArtifacts(String jobName, int buildNumber)
      throws BridgeHttpException, JenkinsDataException {
    throwIfPresent(artifactsHttpFailure, artifactsDataFailure);
    return artifacts;
  }

  @Override
  public JenkinsVcsInfo getBuildVcs(String jobName, int buildNumber)
      throws BridgeHttpException, JenkinsDataException {
    throwIfPresent(vcsHttpFailure, vcsDataFailure);
    return vcsInfo;
  }

  private static void throwIfPresent(BridgeHttpException httpFailure, JenkinsDataException dataFailure)
      throws BridgeHttpException, JenkinsDataException {
    if (httpFailure != null) {
      throw httpFailure;
    }
    if (dataFailure != null) {
      throw dataFailure;
    }
  }
}
