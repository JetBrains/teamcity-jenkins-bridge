package com.jetbrains.teamcity.jenkinsbridge.teamcity;

import com.intellij.openapi.diagnostic.Logger;
import com.jetbrains.teamcity.jenkinsbridge.model.JenkinsPullRequestInfo;
import org.jetbrains.annotations.NotNull;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

public class TeamCityBuildParameters {
  private static final Logger LOG = Logger.getInstance(TeamCityBuildParameters.class.getName());

  public static final String AGENTLESS_BUILD_PROPERTY = "teamcity.build.agentLess";
  /** Prefix reserved for bridge-internal parameters; never expose Jenkins values under it. */
  public static final String BRIDGE_PARAMETER_PREFIX = "jenkins.bridge.";
  public static final String TEAMCITY_PROMOTION_ID = BRIDGE_PARAMETER_PREFIX + "teamcity.promotion.id";

  public static final String PULL_REQUEST_AUTHOR = "teamcity.pullRequest.author";
  public static final String PULL_REQUEST_BRANCH = "teamcity.pullRequest.branch.pullrequests";
  public static final String PULL_REQUEST_NUMBER = "teamcity.pullRequest.number";
  public static final String PULL_REQUEST_SOURCE_BRANCH = "teamcity.pullRequest.source.branch";
  public static final String PULL_REQUEST_TARGET_BRANCH = "teamcity.pullRequest.target.branch";
  public static final String PULL_REQUEST_TITLE = "teamcity.pullRequest.title";
  public static final String PULL_REQUEST_URL = "teamcity.pullRequest.url";

  private TeamCityBuildParameters() {
  }

  /**
   * TeamCity pull/merge request parameters for a build.
   */
  public static Map<String, String> pullRequestParameters(@NotNull JenkinsPullRequestInfo info) {
    Map<String, String> parameters = new LinkedHashMap<>();
    parameters.put(PULL_REQUEST_AUTHOR, info.author());
    parameters.put(PULL_REQUEST_BRANCH, info.number());
    parameters.put(PULL_REQUEST_NUMBER, info.number());
    parameters.put(PULL_REQUEST_SOURCE_BRANCH, info.sourceBranch());
    parameters.put(PULL_REQUEST_TARGET_BRANCH, info.targetBranch());
    parameters.put(PULL_REQUEST_TITLE, info.title());
    parameters.put(PULL_REQUEST_URL, info.url());
    return parameters;
  }

  public static Map<String, String> mergeWithJenkinsParameters(
      Map<String, String> bridgeParameters,
      Map<String, String> jenkinsParameters
  ) {
    Map<String, String> result = new LinkedHashMap<String, String>();
    result.putAll(bridgeParameters);

    Map<String, String> safeJenkinsParameters = jenkinsParameters == null
        ? Collections.<String, String>emptyMap()
        : jenkinsParameters;
    result.putAll(withoutProtectedParameters(safeJenkinsParameters, reservedParameterNames(bridgeParameters)));
    return result;
  }

  private static Map<String, String> withoutProtectedParameters(
      Map<String, String> parameters,
      Set<String> reservedNames
  ) {
    Map<String, String> result = new LinkedHashMap<String, String>();
    for (Map.Entry<String, String> entry : parameters.entrySet()) {
      String name = entry.getKey();
      if (!reservedNames.contains(name) && !name.startsWith(BRIDGE_PARAMETER_PREFIX)) {
        result.put(entry.getKey(), entry.getValue());
      } else {
        LOG.warn("Jenkins parameter rejected because it is reserved by Jenkins Bridge: " + name);
      }
    }
    return result;
  }

  public static Set<String> reservedParameterNames(Map<String, String> bridgeParameters) {
    Set<String> names = new LinkedHashSet<String>();
    names.add(AGENTLESS_BUILD_PROPERTY);
    if (bridgeParameters != null) {
      names.addAll(bridgeParameters.keySet());
    }
    return names;
  }

}
