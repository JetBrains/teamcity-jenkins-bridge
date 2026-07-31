package com.jetbrains.teamcity.jenkinsbridge.vcs;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public enum VcsRefType {
  HEADS,
  TAGS;

  /**
   * @param headClass The Jenkins head class from config.xml.
   */
  @NotNull
  public static VcsRefType fromHeadClass(@Nullable String headClass) {
    return headClass != null && headClass.contains("TagSCMHead") ? TAGS : HEADS;
  }
}
