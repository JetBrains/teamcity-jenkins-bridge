package com.jetbrains.teamcity.jenkinsbridge.persistence;

import com.google.gson.annotations.SerializedName;
import com.jetbrains.teamcity.jenkinsbridge.model.JenkinsBuildInfo;
import com.jetbrains.teamcity.jenkinsbridge.model.JenkinsPipelineGraph;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Per-build mirror record: correlates one Jenkins build with its TeamCity build and tracks how far
 * the sync has progressed. One instance per Jenkins build; persisted in {@link BridgeState}.
 */
public class BuildMirror {
  private String jenkinsBuildKey;
  private String jenkinsJob;
  private int jenkinsBuildNumber;
  private long jenkinsBuildTimestamp;
  private String jenkinsBuildUrl;
  private Long teamCityBuildId;
  private String teamCityBuildTypeId;
  // Persisted under the legacy JSON key "state" so existing state files keep deserializing.
  @SerializedName("state")
  private SyncState syncState;
  // Byte offset into the Jenkins console log already mirrored (Jenkins progressive-log X-Text-Size).
  private long lastLogOffset;
  // null = not yet determined; true = Jenkins Pipeline (mirror stages as build steps, skip flat log);
  // false = freestyle (mirror the flat progressive console log). Decided once, never flipped.
  private Boolean pipelineMode;
  // Per-stage watermarks, keyed by Jenkins stage id; only populated for Pipeline builds.
  private Map<String, StageMirror> stages;
  // Latest normalized graph/tab snapshot for this Jenkins run.
  private JenkinsPipelineGraph pipelineGraph;
  // Native TeamCity build-chain mirror for the latest eligible Pipeline graph snapshot.
  private PipelineChainMirror pipelineChain;
  private String pipelineChainMessageKey;
  // Last "current stage N/M" progress text sent to the running build; deduped so we don't re-send it every poll.
  private String lastStageProgress;
  private boolean metadataLogSent;
  private boolean summaryLogSent;
  private boolean testsSynced;
  private String testSyncError;
  private boolean artifactsSynced;
  private String artifactSyncError;
  private boolean vcsSynced;
  private List<String> vcsSyncErrors;
  private boolean jenkinsBuildParametersLoaded;
  private Map<String, String> jenkinsBuildParameters;
  private String jenkinsResult;
  private String teamCityFinishDate;
  private String lastError;
  private String createdAt;
  private String updatedAt;

  public static BuildMirror create(
      String key,
      String job,
      JenkinsBuildInfo jenkinsInfo,
      String teamCityBuildTypeId,
      String now
  ) {
    BuildMirror mirror = new BuildMirror();
    mirror.jenkinsBuildKey = key;
    mirror.jenkinsJob = job;
    mirror.jenkinsBuildNumber = jenkinsInfo.getNumber();
    mirror.jenkinsBuildTimestamp = jenkinsInfo.getTimestamp();
    mirror.jenkinsBuildUrl = jenkinsInfo.getUrl();
    mirror.teamCityBuildTypeId = teamCityBuildTypeId;
    mirror.syncState = SyncState.DISCOVERED;
    mirror.lastLogOffset = 0;
    mirror.metadataLogSent = false;
    mirror.summaryLogSent = false;
    mirror.testsSynced = false;
    mirror.artifactsSynced = false;
    mirror.vcsSynced = false;
    mirror.jenkinsBuildParametersLoaded = false;
    mirror.createdAt = now;
    mirror.updatedAt = now;
    return mirror;
  }

  public String getJenkinsBuildKey() {
    return jenkinsBuildKey;
  }

  public String getJenkinsJob() {
    return jenkinsJob;
  }

  public int getJenkinsBuildNumber() {
    return jenkinsBuildNumber;
  }

  public long getJenkinsBuildTimestamp() {
    return jenkinsBuildTimestamp;
  }

  public String getJenkinsBuildUrl() {
    return jenkinsBuildUrl;
  }

  public void setJenkinsBuildUrl(String jenkinsBuildUrl) {
    this.jenkinsBuildUrl = jenkinsBuildUrl;
  }

  public Long getTeamCityBuildId() {
    return teamCityBuildId;
  }

  public void setTeamCityBuildId(Long teamCityBuildId) {
    this.teamCityBuildId = teamCityBuildId;
  }

  public String getTeamCityBuildTypeId() {
    return teamCityBuildTypeId;
  }

  public void setTeamCityBuildTypeId(String teamCityBuildTypeId) {
    this.teamCityBuildTypeId = teamCityBuildTypeId;
  }

  public SyncState getSyncState() {
    return syncState;
  }

  public void setSyncState(SyncState syncState) {
    this.syncState = syncState;
  }

  public long getLastLogOffset() {
    return lastLogOffset;
  }

  public void setLastLogOffset(long lastLogOffset) {
    this.lastLogOffset = lastLogOffset;
  }

  @Nullable
  public Boolean getPipelineMode() {
    return pipelineMode;
  }

  public void setPipelineMode(Boolean pipelineMode) {
    this.pipelineMode = pipelineMode;
  }

  public Map<String, StageMirror> getStages() {
    if (stages == null) {
      stages = new LinkedHashMap<String, StageMirror>();
    }
    return stages;
  }

  public JenkinsPipelineGraph getPipelineGraph() {
    return pipelineGraph;
  }

  public void setPipelineGraph(JenkinsPipelineGraph pipelineGraph) {
    this.pipelineGraph = pipelineGraph;
  }

  public PipelineChainMirror getPipelineChain() {
    return pipelineChain;
  }

  public void setPipelineChain(PipelineChainMirror pipelineChain) {
    this.pipelineChain = pipelineChain;
  }

  public String getPipelineChainMessageKey() {
    return pipelineChainMessageKey;
  }

  public void setPipelineChainMessageKey(String pipelineChainMessageKey) {
    this.pipelineChainMessageKey = pipelineChainMessageKey;
  }

  public String getLastStageProgress() {
    return lastStageProgress;
  }

  public void setLastStageProgress(String lastStageProgress) {
    this.lastStageProgress = lastStageProgress;
  }

  public boolean isMetadataLogSent() {
    return metadataLogSent;
  }

  public void setMetadataLogSent(boolean metadataLogSent) {
    this.metadataLogSent = metadataLogSent;
  }

  public boolean isSummaryLogSent() {
    return summaryLogSent;
  }

  public void setSummaryLogSent(boolean summaryLogSent) {
    this.summaryLogSent = summaryLogSent;
  }

  public boolean isTestsSynced() {
    return testsSynced;
  }

  public void setTestsSynced(boolean testsSynced) {
    this.testsSynced = testsSynced;
  }

  public String getTestSyncError() {
    return testSyncError;
  }

  public void setTestSyncError(String testSyncError) {
    this.testSyncError = testSyncError;
  }

  public boolean isArtifactsSynced() {
    return artifactsSynced;
  }

  public void setArtifactsSynced(boolean artifactsSynced) {
    this.artifactsSynced = artifactsSynced;
  }

  public String getArtifactSyncError() {
    return artifactSyncError;
  }

  public void setArtifactSyncError(String artifactSyncError) {
    this.artifactSyncError = artifactSyncError;
  }

  public boolean isVcsSynced() {
    return vcsSynced;
  }

  public void setVcsSynced(boolean vcsSynced) {
    this.vcsSynced = vcsSynced;
  }

  public List<String> getVcsSyncErrors() {
    return vcsSyncErrors;
  }

  public void setVcsSyncErrors(List<String> vcsSyncErrors) {
    this.vcsSyncErrors = vcsSyncErrors;
  }

  public boolean isJenkinsBuildParametersLoaded() {
    return jenkinsBuildParametersLoaded;
  }

  public Map<String, String> getJenkinsBuildParameters() {
    if (jenkinsBuildParameters == null || jenkinsBuildParameters.isEmpty()) {
      return Collections.emptyMap();
    }
    return Collections.unmodifiableMap(jenkinsBuildParameters);
  }

  public void setJenkinsBuildParameters(Map<String, String> jenkinsBuildParameters) {
    if (jenkinsBuildParameters == null || jenkinsBuildParameters.isEmpty()) {
      this.jenkinsBuildParameters = new LinkedHashMap<String, String>();
    } else {
      this.jenkinsBuildParameters = new LinkedHashMap<String, String>(jenkinsBuildParameters);
    }
    this.jenkinsBuildParametersLoaded = true;
  }

  public String getJenkinsResult() {
    return jenkinsResult;
  }

  public void setJenkinsResult(String jenkinsResult) {
    this.jenkinsResult = jenkinsResult;
  }

  public String getTeamCityFinishDate() {
    return teamCityFinishDate;
  }

  public void setTeamCityFinishDate(String teamCityFinishDate) {
    this.teamCityFinishDate = teamCityFinishDate;
  }

  public String getLastError() {
    return lastError;
  }

  public void setLastError(String lastError) {
    this.lastError = lastError;
  }

  public String getCreatedAt() {
    return createdAt;
  }

  public String getUpdatedAt() {
    return updatedAt;
  }

  public void setUpdatedAt(String updatedAt) {
    this.updatedAt = updatedAt;
  }
}
