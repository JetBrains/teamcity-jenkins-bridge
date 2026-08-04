package com.jetbrains.teamcity.jenkinsbridge.settings;

import jetbrains.buildServer.parameters.ValueResolver;
import jetbrains.buildServer.serverSide.ParametersSupport;
import jetbrains.buildServer.serverSide.ProjectManager;

import static com.jetbrains.teamcity.jenkinsbridge.util.Utilities.findBuildType;

public class JenkinsBridgeSettingsProvider {
  private final ProjectManager projectManager;

  public JenkinsBridgeSettingsProvider(ProjectManager projectManager) {
    this.projectManager = projectManager;
  }

  public JenkinsBridgeSettings load() {
    ParametersSource rootProjectSettings = ParametersSource.from(projectManager.getRootProject());
    String teamCityBuildTypeId = readRootProjectSetting(
        rootProjectSettings,
        "jenkins.bridge.teamCityBuildTypeId",
        "TEAMCITY_BUILD_TYPE_ID",
        "TestTc_JenkinsTcTest"
    );

    ParametersSource buildTypeSettings = ParametersSource.from(findBuildType(teamCityBuildTypeId, projectManager));
    return JenkinsBridgeSettings.builder()
        .enabled(readBooleanSetting(buildTypeSettings, rootProjectSettings, "jenkins.bridge.enabled", "JENKINS_BRIDGE_ENABLED", true))
        .jenkinsUrl(readStringSetting(buildTypeSettings, rootProjectSettings, "jenkins.bridge.jenkinsUrl", "JENKINS_URL", "http://localhost:8080"))
        .jenkinsUser(readStringSetting(buildTypeSettings, rootProjectSettings, "jenkins.bridge.jenkinsUser", "JENKINS_USER", "Ahmed"))
        .jenkinsToken(readStringSetting(buildTypeSettings, rootProjectSettings, "jenkins.bridge.jenkinsToken", "JENKINS_TOKEN", ""))
        .jenkinsJob(readStringSetting(buildTypeSettings, rootProjectSettings, "jenkins.bridge.jenkinsJob", "JENKINS_JOB", "tc-test"))
        .teamCityUrl(readStringSetting(buildTypeSettings, rootProjectSettings, "jenkins.bridge.teamCityUrl", "TEAMCITY_URL", "http://localhost:8111/bs/httpAuth"))
        .teamCityUser(readStringSetting(buildTypeSettings, rootProjectSettings, "jenkins.bridge.teamCityUser", "TEAMCITY_USER", "Ahmed"))
        .teamCityPassword(readStringSetting(buildTypeSettings, rootProjectSettings, "jenkins.bridge.teamCityPassword", "TEAMCITY_PASSWORD", "test"))
        .teamCityBuildTypeId(teamCityBuildTypeId)
        .pollSeconds(readIntSetting(buildTypeSettings, rootProjectSettings, "jenkins.bridge.pollSeconds", "BRIDGE_POLL_SECONDS", 10))
        .recentBuildLimit(readIntSetting(buildTypeSettings, rootProjectSettings, "jenkins.bridge.recentBuildLimit", "RECENT_BUILDS_LIMIT", 1))
        .pendingTriggerTimeoutMinutes(readIntSetting(buildTypeSettings, rootProjectSettings,
            "jenkins.bridge.pendingTriggerTimeoutMinutes", "PENDING_TRIGGER_TIMEOUT_MINUTES", 1440))
        .stateFile(readStringSetting(buildTypeSettings, rootProjectSettings, "jenkins.bridge.stateFile", "BRIDGE_STATE_FILE", ""))
        .build();
  }

  private static String readRootProjectSetting(
      ParametersSource rootProjectSettings,
      String propertyName,
      String environmentName,
      String defaultValue
  ) {
    String value = rootProjectSettings == null ? null : rootProjectSettings.get(propertyName);
    if (JenkinsBridgeSettings.isNotBlank(value)) {
      return value;
    }

    return getString(propertyName, environmentName, defaultValue);
  }

  // Fallback order: build configuration parameter, root project parameter, system property, environment variable, default.
  private static String readStringSetting(
      ParametersSource buildTypeSettings,
      ParametersSource rootProjectSettings,
      String propertyName,
      String environmentName,
      String defaultValue
  ) {
    String value = buildTypeSettings == null ? null : buildTypeSettings.get(propertyName);
    if (JenkinsBridgeSettings.isNotBlank(value)) {
      return value;
    }

    value = rootProjectSettings == null ? null : rootProjectSettings.get(propertyName);
    if (JenkinsBridgeSettings.isNotBlank(value)) {
      return value;
    }

    return getString(propertyName, environmentName, defaultValue);
  }

  private static int readIntSetting(
      ParametersSource buildTypeSettings,
      ParametersSource rootProjectSettings,
      String propertyName,
      String environmentName,
      int defaultValue
  ) {
    String value = readStringSetting(buildTypeSettings, rootProjectSettings, propertyName, environmentName, String.valueOf(defaultValue));
    return parseInt(value, defaultValue);
  }

  private static boolean readBooleanSetting(
      ParametersSource buildTypeSettings,
      ParametersSource rootProjectSettings,
      String propertyName,
      String environmentName,
      boolean defaultValue
  ) {
    String value = readStringSetting(buildTypeSettings, rootProjectSettings, propertyName, environmentName, String.valueOf(defaultValue));
    return parseBoolean(value);
  }

  private static String getString(String propertyName, String environmentName, String defaultValue) {
    String value = System.getProperty(propertyName);
    if (JenkinsBridgeSettings.isNotBlank(value)) {
      return value;
    }

    value = System.getenv(environmentName);
    if (JenkinsBridgeSettings.isNotBlank(value)) {
      return value;
    }

    return defaultValue;
  }

  private static int parseInt(String value, int defaultValue) {
    try {
      return Integer.parseInt(value);
    } catch (NumberFormatException e) {
      return defaultValue;
    }
  }

  private static boolean parseBoolean(String value) {
    return "true".equalsIgnoreCase(value) || "1".equals(value) || "yes".equalsIgnoreCase(value);
  }

  private static class ParametersSource {
    private final ParametersSupport parametersSupport;

    private ParametersSource(ParametersSupport parametersSupport) {
      this.parametersSupport = parametersSupport;
    }

    private static ParametersSource from(ParametersSupport parametersSupport) {
      return parametersSupport == null ? null : new ParametersSource(parametersSupport);
    }

    private String get(String name) {
      String value = parametersSupport.getParametersProvider().get(name);
      if (!JenkinsBridgeSettings.isNotBlank(value)) {
        return value;
      }

      ValueResolver resolver = parametersSupport.getValueResolver();
      return resolver == null ? value : resolver.resolve(value).getResult();
    }
  }
}
