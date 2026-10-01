package com.jetbrains.teamcity.jenkinsbridge.model;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.jetbrains.annotations.NotNull;

/**
 * Result of {@code wfapi/describe}: the ordered Pipeline stages of one build, plus a flag for
 * whether the build is a Pipeline at all. A {@code 404} from Jenkins (freestyle job, or the
 * Pipeline Stage View plugin is absent) yields {@link #notPipeline()}.
 */
public class JenkinsStages {
  private final boolean pipeline;
  private final List<JenkinsStage> stages;

  private JenkinsStages(boolean pipeline, List<JenkinsStage> stages) {
    this.pipeline = pipeline;
    this.stages = stages;
  }

  /** Sentinel for a build with no Pipeline stage data (freestyle / plugin absent). */
  public static JenkinsStages notPipeline() {
    return new JenkinsStages(false, Collections.<JenkinsStage>emptyList());
  }

  /** Pipeline Graph View identified a run but WFAPI has no stage list. */
  public static JenkinsStages emptyPipeline() {
    return new JenkinsStages(true, Collections.<JenkinsStage>emptyList());
  }

  public static JenkinsStages fromJson(JsonObject json) {
    JsonArray stagesJson = json.getAsJsonArray("stages");
    List<JenkinsStage> stages = new ArrayList<JenkinsStage>();
    if (stagesJson != null) {
      for (JsonElement stageJson : stagesJson) {
        if (stageJson != null && stageJson.isJsonObject()) {
          stages.add(JenkinsStage.fromJson(stageJson.getAsJsonObject()));
        }
      }
    }
    // A 200 response is a Pipeline even when the stages array is still empty (build just started).
    return new JenkinsStages(true, Collections.unmodifiableList(stages));
  }

  public boolean isPipeline() {
    return pipeline;
  }

  @NotNull
  public List<JenkinsStage> getStages() {
    return stages;
  }
}
