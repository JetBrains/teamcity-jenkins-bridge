package com.jetbrains.teamcity.jenkinsbridge.teamcity;

import com.jetbrains.teamcity.jenkinsbridge.model.JenkinsPullRequestInfo;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class TeamCityBuildParameters {
  public static final String AGENTLESS_BUILD_PROPERTY = "teamcity.build.agentLess";
  /** Prefix reserved for bridge-internal parameters; never expose Jenkins values under it. */
  public static final String BRIDGE_PARAMETER_PREFIX = "jenkins.bridge.";

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
      Map<String, String> jenkinsParameters,
      Set<String> existingTeamCityParameterNames
  ) {
    Map<String, String> result = new LinkedHashMap<String, String>();
    result.putAll(bridgeParameters);

    Map<String, String> safeJenkinsParameters = jenkinsParameters == null
        ? Collections.<String, String>emptyMap()
        : jenkinsParameters;
    Map<String, String> visibleJenkinsParameters = withoutBridgeInternalParameters(safeJenkinsParameters);
    List<String> collisions = collisions(result.keySet(), visibleJenkinsParameters.keySet(), existingTeamCityParameterNames);
    if (!collisions.isEmpty()) {
      throw new IllegalStateException(
          "Jenkins build parameter name(s) collide with TeamCity build parameters: "
              + join(collisions));
    }

    result.putAll(visibleJenkinsParameters);
    return result;
  }

  private static Map<String, String> withoutBridgeInternalParameters(Map<String, String> parameters) {
    Map<String, String> result = new LinkedHashMap<String, String>();
    for (Map.Entry<String, String> entry : parameters.entrySet()) {
      if (!entry.getKey().startsWith(BRIDGE_PARAMETER_PREFIX)) {
        result.put(entry.getKey(), entry.getValue());
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

  private static List<String> collisions(
      Set<String> bridgeNames,
      Set<String> jenkinsNames,
      Set<String> existingTeamCityParameterNames
  ) {
    Set<String> reserved = new LinkedHashSet<String>();
    reserved.add(AGENTLESS_BUILD_PROPERTY);
    if (bridgeNames != null) {
      reserved.addAll(bridgeNames);
    }
    if (existingTeamCityParameterNames != null) {
      reserved.addAll(existingTeamCityParameterNames);
    }

    List<String> collisions = new ArrayList<String>();
    for (String name : jenkinsNames) {
      if (reserved.contains(name)) {
        collisions.add(name);
      }
    }
    Collections.sort(collisions);
    return collisions;
  }

  private static String join(List<String> values) {
    StringBuilder builder = new StringBuilder();
    for (String value : values) {
      if (builder.length() > 0) {
        builder.append(", ");
      }
      builder.append(value);
    }
    return builder.toString();
  }
}
