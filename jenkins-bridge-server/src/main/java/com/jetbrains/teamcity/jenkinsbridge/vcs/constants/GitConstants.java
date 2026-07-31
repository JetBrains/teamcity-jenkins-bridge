package com.jetbrains.teamcity.jenkinsbridge.vcs.constants;

public final class GitConstants {
  public static final String URL_PROP = "url";
  public static final String BRANCH_PROP = "branch";
  public static final String BRANCH_SPEC_ALL_HEADS = "+:refs/heads/*";
  public static final String BRANCH_SPEC_ALL_TAGS = "+:refs/tags/*";
  public static final String API_FIELDS = "lastBuiltRevision[SHA1,branch[name]],remoteUrls";

  private GitConstants() {
  }
}
