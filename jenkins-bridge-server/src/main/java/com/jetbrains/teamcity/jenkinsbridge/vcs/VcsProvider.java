package com.jetbrains.teamcity.jenkinsbridge.vcs;

import com.jetbrains.teamcity.jenkinsbridge.vcs.constants.GenericVcsConstants;
import com.jetbrains.teamcity.jenkinsbridge.vcs.constants.GitConstants;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Maps a version control system between its Jenkins representation and its TeamCity representation.
 * <p>
 * Note: Only the Git Jenkins plugin exposes all the necessary information to create a VCS root. Mercurial does not give the repository URL,
 * while Subversion only gives the action class and Perforce gives nothing. The TFS enum variant was removed because the Jenkins plugin for TFVC
 * is deprecated and has security vulnerabilities.
 */
public enum VcsProvider {
  GIT("jetbrains.git", "hudson.plugins.git.util.BuildData", GitConstants.URL_PROP, true) {
    @Override
    @NotNull
    public Map<String, String> buildRootParameters(String repoUrl, String branchRef, VcsRefType refType) {
      Map<String, String> params = new LinkedHashMap<>();
      params.put(GitConstants.URL_PROP, repoUrl == null ? "" : repoUrl);
      if (branchRef != null && !branchRef.isEmpty()) {
        params.put(GitConstants.BRANCH_PROP, branchRef);
      }
      params.put(GenericVcsConstants.BRANCH_SPEC_PROP, refType == VcsRefType.TAGS
          ? GitConstants.BRANCH_SPEC_ALL_TAGS
          : GitConstants.BRANCH_SPEC_ALL_HEADS);
      params.put(GenericVcsConstants.AUTH_METHOD_PROP, GenericVcsConstants.AUTH_METHOD_ANONYMOUS);
      return params;
    }
  },
  SUBVERSION("svn", "hudson.scm.SubversionTagAction", null, false),
  MERCURIAL("mercurial", "hudson.plugins.mercurial.MercurialTagAction", null, false),
  PERFORCE("perforce", null, null, false); // The plugin does not expose the action class in the API

  private final String teamCityVcsName;
  private final String jenkinsActionClass; // The "_class" field value in the Jenkins API JSON that determines the VCS type
  private final String urlPropertyKey;
  private final boolean isSupported;

  VcsProvider(String teamCityVcsName, String jenkinsActionClass, String urlPropertyKey, boolean isSupported) {
    this.teamCityVcsName = teamCityVcsName;
    this.jenkinsActionClass = jenkinsActionClass;
    this.urlPropertyKey = urlPropertyKey;
    this.isSupported = isSupported;
  }

  @Nullable
  public static VcsProvider fromJenkinsClass(String jenkinsActionClass) {
    for (VcsProvider provider : values()) {
      if (Objects.equals(jenkinsActionClass, provider.jenkinsActionClass) && provider.isSupported()) {
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

  public boolean isSupported() {
    return isSupported;
  }

  /**
   * Builds the TeamCity VCS root property map for a repository. Implemented per provider.
   */
  public Map<String, String> buildRootParameters(String repoUrl, String branchRef, VcsRefType refType) {
    throw new UnsupportedOperationException("VCS provider " + name() + " is not implemented.");
  }

}
