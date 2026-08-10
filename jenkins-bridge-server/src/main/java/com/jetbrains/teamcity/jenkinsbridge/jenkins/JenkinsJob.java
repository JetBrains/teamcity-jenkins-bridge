package com.jetbrains.teamcity.jenkinsbridge.jenkins;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * A Jenkins job (or container) as returned by the {@code jobs[...]} listing. {@code fullName} is the
 * folder-qualified path (e.g. {@code team/my-pipeline}) and is what gets stored as the mirror's
 * {@code jenkinsJob} parameter. Folders and multibranch parents are listed but not importable (they
 * have no builds of their own).
 */
public class JenkinsJob {
  private final String name;
  public static final String WORKFLOW_MULTIBRANCH_PROJECT_CLASS =
      "org.jenkinsci.plugins.workflow.multibranch.WorkflowMultiBranchProject";
  private final String fullName;
  private final String url;
  private final String type;
  private final String displayType;
  private final boolean importable;

  private JenkinsJob(String name, String fullName, String url, String type, String displayType,
      boolean importable) {
    this.name = name;
    this.fullName = fullName;
    this.url = url;
    this.type = type;
    this.displayType = displayType;
    this.importable = importable;
  }

  public static JenkinsJob fromJson(JsonObject json) {
    String name = getString(json, "name");
    String fullName = getString(json, "fullName");
    if (fullName.isEmpty()) {
      fullName = name;
    }
    String url = getString(json, "url");
    String type = getString(json, "_class");
    return new JenkinsJob(name, fullName, url, type, displayType(type), isImportableClass(type));
  }

  /**
   * A listing entry is importable unless it is a container (folder / multibranch / organization
   * folder). Unknown classes default to importable, so new buildable job plugins still work.
   */
  static boolean isImportableClass(String jenkinsClass) {
    if (jenkinsClass == null || jenkinsClass.isEmpty()) {
      return true;
    }
    return !(jenkinsClass.contains("Folder")
        || jenkinsClass.contains("MultiBranch")
        || jenkinsClass.contains("OrganizationFolder"));
  }

  public String getName() {
    return name;
  }

  public String getFullName() {
    return fullName;
  }

  public String getUrl() {
    return url;
  }

  public String getType() {
    return type;
  }

  /** User-facing equivalent of Jenkins' internal {@code _class} value. */
  public String getDisplayType() {
    return displayType;
  }

  public static String displayType(String jenkinsClass) {
    if (jenkinsClass == null || jenkinsClass.isEmpty()) {
      return "Unknown";
    }
    if (jenkinsClass.contains("FreeStyleProject")) {
      return "Freestyle project";
    }
    if (jenkinsClass.contains("WorkflowMultiBranchProject")) {
      return "Multibranch Pipeline";
    }
    if (jenkinsClass.contains("WorkflowJob")) {
      return "Pipeline";
    }
    if (jenkinsClass.contains("OrganizationFolder")) {
      return "Organization folder";
    }
    if (jenkinsClass.endsWith("Folder") || jenkinsClass.contains(".Folder")) {
      return "Folder";
    }
    if (jenkinsClass.contains("MavenModuleSet")) {
      return "Maven project";
    }
    return jenkinsClass.substring(jenkinsClass.lastIndexOf('.') + 1);
  }

  public boolean isImportable() {
    return importable;
  }

  public boolean isMultibranch() {
    return isMultibranchClass(type);
  }

  public static boolean isMultibranchClass(String jenkinsClass) {
    return WORKFLOW_MULTIBRANCH_PROJECT_CLASS.equals(jenkinsClass);
  }

  private static String getString(JsonObject json, String key) {
    JsonElement element = json.get(key);
    if (element == null || element.isJsonNull()) {
      return "";
    }
    return element.getAsString();
  }
}
