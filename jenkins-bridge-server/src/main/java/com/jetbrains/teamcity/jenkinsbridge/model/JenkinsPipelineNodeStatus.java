package com.jetbrains.teamcity.jenkinsbridge.model;

/**
 * Normalized status of a single Jenkins Pipeline node (stage / parallel branch), independent of the
 * whole-build {@link JenkinsVerdict}. This is the single source of the node-status taxonomy: raw WFAPI
 * stage statuses and Blue Ocean result/state pairs are normalized here, and every "is it started /
 * terminal / what TeamCity result" question is answered from this one enum so the rules cannot drift.
 */
public enum JenkinsPipelineNodeStatus {
  QUEUED,
  IN_PROGRESS,
  PAUSED_PENDING_INPUT,
  SUCCESS,
  UNSTABLE,
  FAILURE,
  ABORTED,
  NOT_EXECUTED,
  UNKNOWN;

  /** Still going: queued, running, or paused for input. Used to decide when a stage log block may close. */
  public boolean isActive() {
    return this == QUEUED || this == IN_PROGRESS || this == PAUSED_PENDING_INPUT;
  }

  /**
   * The Jenkins node has begun (or already finished). False only for {@link #QUEUED} and the
   * unclassifiable {@link #UNKNOWN} — never start a generated build for a node that has not started or
   * that we cannot classify. Fixes the old "start on any non-blank status" bug that started QUEUED nodes.
   */
  public boolean isStarted() {
    return this == IN_PROGRESS || this == PAUSED_PENDING_INPUT || isTerminal();
  }

  /**
   * Reached a known terminal result. {@link #UNKNOWN} is deliberately excluded: never finish a node we
   * cannot classify — it will resolve once Jenkins reports a real status.
   */
  public boolean isTerminal() {
    return this == SUCCESS || this == UNSTABLE || this == FAILURE || this == ABORTED || this == NOT_EXECUTED;
  }

  /**
   * TeamCity result for a generated stage-node build. {@link #NOT_EXECUTED} (a Jenkins-skipped stage)
   * maps to {@code SUCCESS} (green) — parity: Jenkins shows a skipped stage as neutral, never as a
   * failure; TeamCity has no neutral build state, so green + a "skipped" status text is the chosen
   * representation. Downstream is unaffected because chain dependencies use continuation mode RUN.
   * Non-terminal statuses have no meaningful terminal result and return {@code UNKNOWN}.
   */
  public String toTeamCityResult() {
    switch (this) {
      case SUCCESS:
      case NOT_EXECUTED:
        return "SUCCESS";
      case UNSTABLE:
        return "UNSTABLE";
      case ABORTED:
        return "ABORTED";
      case FAILURE:
        return "FAILURE";
      default:
        return "UNKNOWN";
    }
  }

  /** True for a skipped stage (Jenkins {@code NOT_EXECUTED} / Blue Ocean {@code SKIPPED}). */
  public boolean isSkipped() {
    return this == NOT_EXECUTED;
  }

  /** Normalizes a raw WFAPI/generic status string. Null, blank, or unrecognized → {@link #UNKNOWN}. */
  public static JenkinsPipelineNodeStatus from(String raw) {
    if (raw == null) {
      return UNKNOWN;
    }
    String s = raw.trim().toUpperCase();
    if (s.isEmpty()) {
      return UNKNOWN;
    }
    if (s.equals("FAILED")) {
      return FAILURE;
    }
    if (s.equals("NOT_BUILT") || s.equals("SKIPPED")) {
      return NOT_EXECUTED;
    }
    if (s.equals("PAUSED")) {
      return PAUSED_PENDING_INPUT;
    }
    if (s.equals("RUNNING")) {
      return IN_PROGRESS;
    }
    try {
      return valueOf(s);
    } catch (IllegalArgumentException e) {
      return UNKNOWN;
    }
  }

  /**
   * Normalizes Blue Ocean's separate {@code result}/{@code state} fields into one vocabulary. Prefers a
   * concrete result; otherwise maps the lifecycle state. Unrecognized → {@link #UNKNOWN} (previously an
   * empty string, which left a SKIPPED branch's generated build stuck queued forever).
   */
  public static JenkinsPipelineNodeStatus fromBlueOcean(String result, String state) {
    if (result != null) {
      String r = result.trim().toUpperCase();
      if (!r.isEmpty() && !r.equals("UNKNOWN")) {
        return from(r);
      }
    }
    if (state != null) {
      String st = state.trim().toUpperCase();
      if (st.equals("RUNNING")) {
        return IN_PROGRESS;
      }
      if (st.equals("QUEUED")) {
        return QUEUED;
      }
      if (st.equals("PAUSED")) {
        return PAUSED_PENDING_INPUT;
      }
      if (st.equals("SKIPPED") || st.equals("NOT_BUILT")) {
        return NOT_EXECUTED;
      }
    }
    return UNKNOWN;
  }
}
