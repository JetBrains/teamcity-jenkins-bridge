package com.jetbrains.teamcity.jenkinsbridge.teamcity;

import com.jetbrains.teamcity.jenkinsbridge.model.JenkinsVerdict;
import jetbrains.buildServer.BuildProblemData;
import jetbrains.buildServer.messages.DefaultMessagesInfo;
import jetbrains.buildServer.serverSide.RunningBuildEx;
import jetbrains.buildServer.serverSide.impl.BuildAgentMessagesQueue;
import jetbrains.buildServer.serverSide.impl.RunningBuildState;

import java.util.Collections;
import java.util.Date;

public class TeamCityBuildFinisher {
  private static final String FINISH_REQUEST_MESSAGE = "Build finish request received via Jenkins Bridge";
  // Build problem type shared by all Jenkins-result problems; the per-result identity is appended below.
  private static final String JENKINS_RESULT_PROBLEM_TYPE = "jenkinsBuildResult";

  private final TeamCityRunningBuildLocator buildLocator;
  private final BuildAgentMessagesQueue buildAgentMessagesQueue;

  public TeamCityBuildFinisher(
      TeamCityRunningBuildLocator buildLocator,
      BuildAgentMessagesQueue buildAgentMessagesQueue
  ) {
    this.buildLocator = buildLocator;
    this.buildAgentMessagesQueue = buildAgentMessagesQueue;
  }

  public void finishBuild(long buildId, Date finishTime, String jenkinsResult) {
    RunningBuildEx runningBuild = buildLocator.findRunningBuild(buildId);
    if (runningBuild == null) {
      // Build is not in a runnable state (e.g. already finished) — nothing to finish.
      return;
    }
    finishRunningBuild(runningBuild, finishTime, jenkinsResult);
  }

  private void finishRunningBuild(RunningBuildEx runningBuild, Date finishTime, String jenkinsResult) {
    // The verdict (build problem / interruption / status text) must be applied while the build is
    // still running, i.e. before buildFinished(...) finalizes it.
    applyJenkinsVerdict(runningBuild, jenkinsResult);

    try {
      buildAgentMessagesQueue.processMessages(
          runningBuild,
          Collections.singletonList(
              DefaultMessagesInfo.createTextMessage(FINISH_REQUEST_MESSAGE).updateTags(DefaultMessagesInfo.TAG_SERVER)
          )
      );
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted while logging TeamCity build finish request", e);
    } catch (BuildAgentMessagesQueue.BuildMessagesQueueFullException e) {
      throw new IllegalStateException("TeamCity build messages queue is full", e);
    }

    // buildFailedOnAgent must stay false: per the TeamCity API it only exists for backwards
    // compatibility with old agents and otherwise produces a generic "Unknown build problem".
    // The real verdict is carried by the build problem / interruption applied above.
    buildAgentMessagesQueue.buildFinished(runningBuild, finishTime, false);
  }

  /**
   * Maps the Jenkins result onto a TeamCity outcome.
   * <li>FAILURE/UNKNOWN become build problems (failed build) while ABORTED/NOT_BUILT become
   * interruptions (canceled build).
   * <li>UNSTABLE is marked as successful, since an unstable build in Jenkins does not propagate failure.
   * It has a custom status text.
   * <li>SUCCESS is left untouched, so TeamCity keeps its own status text.
   * <br>
   * <p>
   * NOTE: The status <em>badge</em> is not updated by the call to .setCustomStatusText(). That requires a change in
   * the core (props of StatusBadge.tsx).
   */
  private void applyJenkinsVerdict(RunningBuildEx runningBuild, String jenkinsResult) {
    JenkinsVerdict verdict = JenkinsVerdict.fromResult(jenkinsResult);
    switch (verdict) {
      case SUCCESS:
        break;
      case UNSTABLE:
        // Needs "Common Failure Conditions > at least one test failed" to be off in the build configuration settings to register "Unstable" as a successful status.
        runningBuild.setCustomStatusText("Unstable");
        break;
      case FAILURE:
        runningBuild.setCustomStatusText("Failure");
        addJenkinsResultProblem(runningBuild, "failure", "Jenkins failed this build");
        break;
      case ABORTED:
        runningBuild.setInterrupted(RunningBuildState.INTERRUPTED_BY_USER, null, "Aborted in Jenkins");
        break;
      case NOT_BUILT:
        // TODO: Find a way to represent it as "Skipped" in TeamCity, instead of just canceled.
        runningBuild.setInterrupted(RunningBuildState.INTERRUPTED_BY_SYSTEM, null, "Failed to start in Jenkins");
        break;
      case UNKNOWN:
      default:
        if (jenkinsResult == null) {
          break; // Still running
        }
        addJenkinsResultProblem(runningBuild, "unknown", "Unknown Jenkins build status: " + jenkinsResult);
        runningBuild.setCustomStatusText(jenkinsResult);
    }
  }

  private void addJenkinsResultProblem(RunningBuildEx runningBuild, String resultId, String description) {
    // Stable identity so retried finish attempts dedupe instead of stacking duplicate problems.
    runningBuild.addBuildProblem(
        BuildProblemData.createBuildProblem("jenkinsResult_" + resultId, JENKINS_RESULT_PROBLEM_TYPE, description));
  }
}
