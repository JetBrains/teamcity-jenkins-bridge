package com.jetbrains.teamcity.jenkinsbridge.model;

import org.jetbrains.annotations.NotNull;

/** Primary multibranch source revision exposed by Jenkins SCMRevisionAction. */
public record JenkinsScmRevision(@NotNull String headName, @NotNull String hash) {
}
