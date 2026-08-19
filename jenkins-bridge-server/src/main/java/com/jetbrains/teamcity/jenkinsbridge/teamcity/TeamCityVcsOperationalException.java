package com.jetbrains.teamcity.jenkinsbridge.teamcity;

/**
 * A known, retryable failure from TeamCity's VCS mutation APIs.
 *
 * <p>The TeamCity API does not expose a common base class for these unchecked operational
 * failures, so the VCS adapter translates them into this checked bridge-owned type at its
 * boundary.
 * Unexpected runtime failures are deliberately not wrapped.</p>
 */
public class TeamCityVcsOperationalException extends Exception {
    public TeamCityVcsOperationalException(Throwable cause) {
        super(cause);
    }
}
