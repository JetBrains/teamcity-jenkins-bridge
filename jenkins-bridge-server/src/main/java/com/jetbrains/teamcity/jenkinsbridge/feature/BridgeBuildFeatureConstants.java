package com.jetbrains.teamcity.jenkinsbridge.feature;

/**
 * Stable identifiers for the "Jenkins Bridge" build feature. Shared by the feature definition, its
 * edit JSP, the parameters processor, and the mirrored-job discovery. {@link #TYPE} and the parameter
 * keys are persisted in build-config settings, so they must not change without a migration.
 */
public final class BridgeBuildFeatureConstants {
  public static final String TYPE = "jenkinsBridge";

  /**
   * Required. Id of the "Jenkins" project connection that says which Jenkins server to mirror from.
   */
  public static final String PARAM_CONNECTION_ID = "connectionId";

  /** Required. Jenkins job path (folders separated by {@code /}), e.g. {@code team/my-pipeline}. */
  public static final String PARAM_JENKINS_JOB = "jenkinsJob";

  /**
   * Read-only, informational. Absolute Jenkins job URL, composed from the selected connection and
   * the job path. The poller does not use it, it builds the URL from the connection at poll time.
   */
  public static final String PARAM_JENKINS_URL = "jenkinsUrl";

  /** Optional, informational Jenkins {@code _class} used to display the job type in import UI. */
  public static final String PARAM_JENKINS_TYPE = "jenkinsType";

  /**
   * Optional. How many of the most recent Jenkins builds to mirror the first time this configuration
   * is polled, where zero mirrors no historical build. Has no effect once the job has been polled at
   * least once (for multibranch, this happens once per branch since each branch is a job).
   */
  public static final String PARAM_RECENT_LIMIT = "recentBuildLimit";

  /** Value prefilled in the UI and used when {@link #PARAM_RECENT_LIMIT} is blank. */
  public static final int DEFAULT_RECENT_LIMIT = 1;

  /** Internal build configuration parameter holding the detected Jenkins job type. */
  public static final String INTERNAL_MULTIBRANCH_PARAM = "teamcity.internal.jenkinsBridge.multibranch";

  /**
   * TC build parameter stamped onto Jenkins-first mirror builds (the Jenkins build key,
   * e.g. {@code "myjob#42@1710000000042"}). Absent on TC-first promotions, which are created by the
   * user before Jenkins runs — those are resolved via mirror binding, not this parameter.
   */
  public static final String JENKINS_BUILD_KEY_PARAM = "jenkins.build.key";

  private BridgeBuildFeatureConstants() {
  }
}
