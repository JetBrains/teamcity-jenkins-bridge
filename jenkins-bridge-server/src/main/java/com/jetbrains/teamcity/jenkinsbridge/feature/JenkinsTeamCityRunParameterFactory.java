package com.jetbrains.teamcity.jenkinsbridge.feature;

import com.google.gson.Gson;
import com.jetbrains.teamcity.jenkinsbridge.model.JenkinsParameterDefinition;
import com.jetbrains.teamcity.jenkinsbridge.model.JenkinsJobParameters;
import jetbrains.buildServer.serverSide.SBuildType;
import com.jetbrains.teamcity.jenkinsbridge.teamcity.TeamCityBuildParameters;
import jetbrains.buildServer.serverSide.Parameter;
import jetbrains.buildServer.serverSide.parameters.ParameterFactory;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Converts Jenkins job parameter definitions into TeamCity build-configuration parameters that are
 * prompted in the normal TeamCity Run dialog.
 */
public final class JenkinsTeamCityRunParameterFactory {
  private static final Gson SNAPSHOT_GSON = new Gson();
  private static final String PROMPT_TEXT_SPEC = "text display='prompt'";
  private static final String BOOLEAN_SPEC =
      "checkbox checkedValue='true' uncheckedValue='false' display='prompt'";
  private JenkinsTeamCityRunParameterFactory() {
  }

  //  reject parameters that we don't want to import that could cause problems for use, for example jenkins bridge parameters.
  // we should reserve theese paramets to written in TeamCity not from Jenkins.
  public static boolean canImport(JenkinsParameterDefinition definition) {
    if (definition == null) {
      return false;
    }
    String name = definition.getName();
    if (name.trim().isEmpty()) {
      return false;
    }

    String lowerName = name.toLowerCase(Locale.ROOT);
    return !BridgeBuildFeatureConstants.JENKINS_BUILD_KEY_PARAM.equals(name)
        && !TeamCityBuildParameters.AGENTLESS_BUILD_PROPERTY.equals(name)
        && !lowerName.startsWith("teamcity.")
        && !lowerName.startsWith("jenkins.build.")
        && !lowerName.startsWith(TeamCityBuildParameters.BRIDGE_PARAMETER_PREFIX);
  }

  @NotNull
  public static Parameter create(@NotNull ParameterFactory parameterFactory,
                                 @NotNull JenkinsParameterDefinition definition) {
    String name = definition.getName();
    String value = defaultValue(definition);
    String spec = spec(definition);
    return parameterFactory.createTypedParameter(name, value, spec);
  }

  /** Returns a stable JSON representation of the Jenkins definitions used to render TeamCity. */
  @NotNull
  public static String snapshot(@NotNull JenkinsJobParameters definitions) {
    return SNAPSHOT_GSON.toJson(definitions.getParameters());
  }

  /** Returns an exact, readable representation suitable for trigger diagnostics. */
  @NotNull
  public static String describe(@NotNull JenkinsJobParameters definitions) {
    return snapshot(definitions);
  }

  /** Returns a diagnostic for submitted values that are invalid for current Jenkins choices. */
  @NotNull
  public static String invalidChoiceValues(@NotNull JenkinsJobParameters definitions,
                                           @NotNull Map<String, String> values) {
    StringBuilder invalid = new StringBuilder();
    for (JenkinsParameterDefinition definition : definitions.getParameters()) {
      if (definition.getChoices().isEmpty()) {
        continue;
      }
      String value = values.get(definition.getName());
      if (value != null && !definition.getChoices().contains(value)) {
        if (invalid.length() > 0) {
          invalid.append(", ");
        }
        invalid.append(definition.getName()).append("=").append(value)
            .append(" (allowed: ").append(definition.getChoices()).append(")");
      }
    }
    return invalid.toString();
  }

  /**
   * Reconciles the supported Jenkins definitions into a generated TeamCity build configuration.
   * Existing definitions are replaced so changed defaults, types, and choices appear in the next
   * Run Custom Build dialog.
   */
  @NotNull
  public static SynchronizationResult synchronize(@NotNull ParameterFactory parameterFactory,
                                                 @NotNull SBuildType buildType,
                                                 @NotNull JenkinsJobParameters definitions,
                                                 @Nullable Set<String> previouslyImported) {
    Set<String> currentNames = new HashSet<String>();
    for (JenkinsParameterDefinition definition : definitions.getParameters()) {
      if (canImport(definition)) {
        currentNames.add(definition.getName());
      }
    }

    Set<String> oldNames = previouslyImported == null
        ? java.util.Collections.<String>emptySet() : previouslyImported;
    for (String name : oldNames) {
      if (!currentNames.contains(name)) {
        buildType.removeParameter(name);
      }
    }

    int synchronizedCount = 0;
    for (JenkinsParameterDefinition definition : definitions.getParameters()) {
      if (!canImport(definition)) {
        continue;
      }
      String name = definition.getName();
      if (buildType.getParametersProvider().get(name) != null) {
        buildType.removeParameter(name);
      }
      buildType.addParameter(create(parameterFactory, definition));
      synchronizedCount++;
    }
    return new SynchronizationResult(currentNames, synchronizedCount,
        !oldNames.equals(currentNames) || synchronizedCount > 0);
  }

  public static final class SynchronizationResult {
    private final Set<String> importedNames;
    private final int synchronizedCount;
    private final boolean changed;

    private SynchronizationResult(Set<String> importedNames, int synchronizedCount, boolean changed) {
      this.importedNames = new HashSet<String>(importedNames);
      this.synchronizedCount = synchronizedCount;
      this.changed = changed;
    }

    @NotNull
    public Set<String> getImportedNames() {
      return new HashSet<String>(importedNames);
    }

    public int getSynchronizedCount() {
      return synchronizedCount;
    }

    public boolean isChanged() {
      return changed;
    }
  }

  static String defaultValue(JenkinsParameterDefinition definition) {
    if (isBoolean(definition)) {
      return Boolean.parseBoolean(definition.getDefaultValue()) ? "true" : "false";
    }

    List<String> choices = definition.getChoices();
    if (!choices.isEmpty() && !choices.contains(definition.getDefaultValue())) {
      return choices.get(0);
    }
    return definition.getDefaultValue();
  }

  static String spec(JenkinsParameterDefinition definition) {
    if (isBoolean(definition)) {
      return BOOLEAN_SPEC;
    }

    List<String> choices = definition.getChoices();
    if (!choices.isEmpty()) {
      StringBuilder spec = new StringBuilder("select display='prompt'");
      for (int i = 0; i < choices.size(); i++) {
        String encoded = quote(choices.get(i));
        spec.append(" data_").append(i + 1).append("='").append(encoded).append("'");
        spec.append(" label_").append(i + 1).append("='").append(encoded).append("'");
      }
      return spec.toString();
    }

    return PROMPT_TEXT_SPEC;
  }

  private static boolean isBoolean(JenkinsParameterDefinition definition) {
    return definition.getType().contains("Boolean");
  }

  private static String quote(String value) {
    StringBuilder result = new StringBuilder();
    String safeValue = value == null ? "" : value;
    for (int i = 0; i < safeValue.length(); i++) {
      char ch = safeValue.charAt(i);
      switch (ch) {
        case '|':
          result.append("||");
          break;
        case '\'':
          result.append("|'");
          break;
        case '\n':
          result.append("|n");
          break;
        case '\r':
          result.append("|r");
          break;
        case '[':
          result.append("|[");
          break;
        case ']':
          result.append("|]");
          break;
        default:
          result.append(ch);
      }
    }
    return result.toString();
  }
}
