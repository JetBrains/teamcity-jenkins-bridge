# Jenkins Bridge

Jenkins Bridge is a TeamCity server-side plugin that mirrors Jenkins builds into
agentless TeamCity builds.

For each Jenkins run, the plugin can:

- create or restore the matching TeamCity build;
- stream Jenkins console output into the TeamCity build log;
- map Jenkins results such as SUCCESS, FAILURE, UNSTABLE, ABORTED, and NOT_BUILT
  to the TeamCity build outcome;
- import JUnit-style test results from Jenkins `testReport/api/json`;
- copy Jenkins build parameters into TeamCity custom build parameters;
- automatically use Jenkins as artifact storage and display all published artifacts;
- persist mirror state in TeamCity plugin data for restart recovery.

Pipeline support is also available for stage/log mirroring. Native TeamCity
build-chain mirroring is experimental and should be runtime-validated before it
is presented as a stable demo feature.

## Connecting to Jenkins

A Jenkins server is configured as a project connection, under Project Settings >
Integrations > Connections > Add Connection > Jenkins. The connection holds the
display name, the Jenkins URL, the username, and that user's API token. A project
can hold several Jenkins connections, and connections are inherited by
subprojects.

To mirror a Jenkins job, add the "Jenkins Bridge" build feature to a build
configuration and pick the connection plus the Jenkins job path. The build
configuration that hosts the feature is the mirror target.

## Server settings

The remaining settings are server-wide. They are read from a root project
parameter first, then a Java system property, then an environment variable, then
the default.

| TeamCity/system property                | Environment variable     | Default               |
|-----------------------------------------|--------------------------|-----------------------|
| `jenkins.bridge.enabled`                | `JENKINS_BRIDGE_ENABLED` | `true`                |
| `jenkins.bridge.pollSeconds`            | `BRIDGE_POLL_SECONDS`    | `10`                  |
| `jenkins.bridge.timeZone`               | `TIMEZONE`               | `Europe/Berlin`       |
| `jenkins.bridge.stateFile` (deprecated) | `BRIDGE_STATE_FILE`      | plugin data directory |

TeamCity parameter references such as `%another.param%` are resolved through
TeamCity's value resolver.

## State

By default, the mirror state is stored in TeamCity's internal database.

The build feature's "No. of builds to import on first sync" setting controls only
cold-start backfill, and defaults to 1 (only the newest Jenkins build). After the
first poll, discovery is incremental, and every new Jenkins build after the
stored watermark is considered. If Jenkins build numbers are reset or reused, the
bridge also uses the Jenkins build timestamp to identify the run.

## Build

Run from the repository root:

```bash
mvn package
```

The build uses local TeamCity 2026.3-SNAPSHOT EAP Maven artifacts. They must be
available under `${user.home}/.m2/repository/TeamCity`.

The latest plugin archive is written to:

`target/jenkins-bridge.zip`

The build also keeps timestamped Git-SHA archives such as:

`target/jenkins-bridge-20260608123456-2d8bab4.zip`

## Install

Copy `target/jenkins-bridge.zip` into the TeamCity data directory's `plugins`
folder, then restart TeamCity.
