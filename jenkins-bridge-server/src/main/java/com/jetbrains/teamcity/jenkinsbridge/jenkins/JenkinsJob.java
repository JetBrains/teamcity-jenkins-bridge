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
  private final String fullName;
  private final String url;
  private final String type;
  private final boolean importable;

  private JenkinsJob(String name, String fullName, String url, String type, boolean importable) {
    this.name = name;
    this.fullName = fullName;
    this.url = url;
    this.type = type;
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
    return new JenkinsJob(name, fullName, url, type, isImportableClass(type));
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

  public boolean isImportable() {
    return importable;
  }

  public boolean isMultibranch() {
    return isMultibranchClass(type);
  }

  public static boolean isMultibranchClass(String jenkinsClass) {
    return jenkinsClass != null && jenkinsClass.contains("MultiBranch");
  }

  private static String getString(JsonObject json, String key) {
    JsonElement element = json.get(key);
    if (element == null || element.isJsonNull()) {
      return "";
    }
    return element.getAsString();
  }
}
