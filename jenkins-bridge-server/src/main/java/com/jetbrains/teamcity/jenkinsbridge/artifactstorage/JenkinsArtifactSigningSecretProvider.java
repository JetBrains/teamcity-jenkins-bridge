package com.jetbrains.teamcity.jenkinsbridge.artifactstorage;

import jetbrains.buildServer.serverSide.BuildPromotion;
import org.jetbrains.annotations.NotNull;

/** Supplies the stable HMAC secret used for one TeamCity build type. */
public interface JenkinsArtifactSigningSecretProvider {
  @NotNull
  String secretFor(@NotNull BuildPromotion promotion);
}
