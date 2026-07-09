package com.jetbrains.teamcity.jenkinsbridge.vcs;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Maps a version control system between its Jenkins representation and its TeamCity representation.
 */
public enum VcsProvider {
  GIT("jetbrains.git", "hudson.plugins.git.util.BuildData", GitConstants.URL_PROP) {
    @Override
    @NotNull
    public Map<String, String> buildRootParameters(String repoUrl, String branchRef) {
      Map<String, String> params = new LinkedHashMap<>();
      params.put(GitConstants.URL_PROP, repoUrl == null ? "" : repoUrl);
      if (branchRef != null && !branchRef.isEmpty()) {
        params.put(GitConstants.BRANCH_PROP, branchRef);
      }
      params.put(GitConstants.BRANCH_SPEC_PROP, GitConstants.BRANCH_SPEC_ALL_HEADS);
      params.put(GitConstants.AUTH_METHOD_PROP, GitConstants.AUTH_METHOD_ANONYMOUS);
      return params;
    }
  },
  SUBVERSION("svn", null, null),
  MERCURIAL("mercurial", null, null),
  PERFORCE("perforce", null, null),
  TFS("tfs", null, null);

  private final String teamCityVcsName;
  private final String jenkinsActionClass; // The "_class" field value in the Jenkins API JSON which determines the VCS type
  private final String urlPropertyKey;

  VcsProvider(String teamCityVcsName, String jenkinsActionClass, String urlPropertyKey) {
    this.teamCityVcsName = teamCityVcsName;
    this.jenkinsActionClass = jenkinsActionClass;
    this.urlPropertyKey = urlPropertyKey;
  }

  @Nullable
  public static VcsProvider fromJenkinsClass(String jenkinsActionClass) {
    if (jenkinsActionClass == null || jenkinsActionClass.isEmpty()) {
      return null;
    }
    for (VcsProvider provider : values()) {
      if (jenkinsActionClass.equals(provider.jenkinsActionClass)) {
        return provider;
      }
    }
    return null;
  }

  public String teamCityVcsName() {
    return teamCityVcsName;
  }

  /**
   * The VCS root property key that stores the repository URL, used when matching an existing root.
   * TODO: Check whether this is the same for all providers. If it is, this field can be removed.
   */
  public String urlPropertyKey() {
    return urlPropertyKey;
  }

  /**
   * Builds the TeamCity VCS root property map for a repository. Implemented per provider.
   */
  public Map<String, String> buildRootParameters(String repoUrl, String branchRef) {
    throw new UnsupportedOperationException("VCS provider " + name() + " is not implemented yet");
  }

  public static final class GitConstants {
    public static final String URL_PROP = "url";
    public static final String BRANCH_PROP = "branch";
    public static final String BRANCH_SPEC_PROP = "teamcity:branchSpec";
    public static final String BRANCH_SPEC_ALL_HEADS = "+:refs/heads/*";
    public static final String AUTH_METHOD_PROP = "authMethod";
    public static final String AUTH_METHOD_ANONYMOUS = "ANONYMOUS";

    private GitConstants() {
    }
  }
}
