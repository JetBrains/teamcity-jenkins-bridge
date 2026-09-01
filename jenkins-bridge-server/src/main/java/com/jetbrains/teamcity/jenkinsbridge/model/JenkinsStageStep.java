package com.jetbrains.teamcity.jenkinsbridge.model;

/**
 * One step inside a Jenkins Pipeline stage (a {@code stageFlowNode} from WFAPI): its Jenkins display
 * name, stored argument description, normalized status, duration, and console log. Backs the
 * per-stage step/log panel in the Pipeline Graph tab (G3b).
 */
public class JenkinsStageStep {
  private final String id;
  private final String name;
  private final String parameterDescription;
  private final String status;
  private final long durationMillis;
  private final String log;

  public JenkinsStageStep(
      String id, String name, String parameterDescription, String status, long durationMillis, String log) {
    this.id = id == null ? "" : id;
    this.name = name == null ? "" : name;
    this.parameterDescription = parameterDescription == null ? "" : parameterDescription;
    this.status = status == null ? "" : status;
    this.durationMillis = durationMillis;
    this.log = log == null ? "" : log;
  }

  public String getId() {
    return id;
  }

  public String getName() {
    return name;
  }

  /** The Jenkins-supplied step arguments ({@code ArgumentsAction}), when WFAPI exposes them. */
  public String getParameterDescription() {
    return parameterDescription;
  }

  public String getStatus() {
    return status;
  }

  public long getDurationMillis() {
    return durationMillis;
  }

  public String getLog() {
    return log;
  }
}
