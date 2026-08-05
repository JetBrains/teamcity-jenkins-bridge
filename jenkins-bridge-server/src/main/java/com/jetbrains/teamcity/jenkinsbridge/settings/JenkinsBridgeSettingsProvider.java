package com.jetbrains.teamcity.jenkinsbridge.settings;

import jetbrains.buildServer.parameters.ValueResolver;
import jetbrains.buildServer.serverSide.ParametersSupport;
import jetbrains.buildServer.serverSide.ProjectManager;

/**
 * Reads the bridge's operational settings. Values come from a root project parameter first, then a
 * system property, then an environment variable, then the default.
 *
 * <p>Jenkins servers and credentials are not read here. They live on the "Jenkins" project
 * connections and are selected per build configuration by the Jenkins Bridge build feature.
 */
public class JenkinsBridgeSettingsProvider {
  private final ProjectManager projectManager;

  public JenkinsBridgeSettingsProvider(ProjectManager projectManager) {
    this.projectManager = projectManager;
  }

  public JenkinsBridgeSettings load() {
    ParametersSource rootProjectSettings = ParametersSource.from(projectManager.getRootProject());
    return new JenkinsBridgeSettings(
        readBooleanSetting(rootProjectSettings, "jenkins.bridge.enabled", "JENKINS_BRIDGE_ENABLED", true),
        readIntSetting(rootProjectSettings, "jenkins.bridge.pollSeconds", "BRIDGE_POLL_SECONDS", 10),
        readStringSetting(rootProjectSettings, "jenkins.bridge.timeZone", "TIMEZONE", "Europe/Berlin"),
        readStringSetting(rootProjectSettings, "jenkins.bridge.stateFile", "BRIDGE_STATE_FILE", ""),
        // Legacy, only used by the deprecated TeamCityClient. Never read from project parameters.
        getString("jenkins.bridge.teamCityUrl", "TEAMCITY_URL", ""),
        getString("jenkins.bridge.teamCityUser", "TEAMCITY_USER", ""),
        getString("jenkins.bridge.teamCityPassword", "TEAMCITY_PASSWORD", "")
    );
  }

  private static String readStringSetting(
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

  private static int readIntSetting(
      ParametersSource rootProjectSettings,
      String propertyName,
      String environmentName,
      int defaultValue
  ) {
    String value = readStringSetting(rootProjectSettings, propertyName, environmentName, String.valueOf(defaultValue));
    return parseInt(value, defaultValue);
  }

  private static boolean readBooleanSetting(
      ParametersSource rootProjectSettings,
      String propertyName,
      String environmentName,
      boolean defaultValue
  ) {
    String value = readStringSetting(rootProjectSettings, propertyName, environmentName, String.valueOf(defaultValue));
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
