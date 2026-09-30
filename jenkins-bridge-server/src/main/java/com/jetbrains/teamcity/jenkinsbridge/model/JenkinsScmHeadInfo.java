package com.jetbrains.teamcity.jenkinsbridge.model;

import com.jetbrains.teamcity.jenkinsbridge.vcs.VcsRefType;
import org.jetbrains.annotations.NotNull;

/** Primary SCM metadata serialized on a Jenkins multibranch child job. */
public record JenkinsScmHeadInfo(
    @NotNull VcsRefType refType,
    @NotNull String headName,
    @NotNull String sourceId,
    @NotNull String remoteUrl
) {
}
