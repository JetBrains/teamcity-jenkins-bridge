package com.jetbrains.teamcity.jenkinsbridge.feature;

import jetbrains.buildServer.clouds.server.executors.BuildExecutorType;
import jetbrains.buildServer.serverSide.InvalidProperty;
import jetbrains.buildServer.serverSide.PropertiesProcessor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.Map;

/** TeamCity project-profile type used to select the Jenkins Bridge agentless executor. */
public class JenkinsBridgeExecutorType implements BuildExecutorType {
  public static final String EXECUTOR_TYPE = "jenkinsBridge";

  @Override
  @NotNull
  public String getExecutorType() {
    return EXECUTOR_TYPE;
  }

  @Override
  @NotNull
  public String getDisplayName() {
    return "Jenkins Bridge";
  }

  @Override
  @Nullable
  public String getEditProfileUrl() {
    return null;
  }

  @Override
  @NotNull
  public Map<String, String> getInitialParameterValues() {
    return Collections.emptyMap();
  }

  @Override
  @NotNull
  public PropertiesProcessor getPropertiesProcessor() {
    return properties -> Collections.<InvalidProperty>emptyList();
  }
}
