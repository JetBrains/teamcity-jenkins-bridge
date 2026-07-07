Jenkins Bridge
==============

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

Configuration
-------------

Settings are read from TeamCity parameters first, then Java system properties,
then environment variables, then local defaults.

The plugin first resolves `jenkins.bridge.teamCityBuildTypeId`. After the target
build configuration is found, other settings are resolved in this order:

1. Target build configuration parameter
2. Root project parameter
3. Java system property
4. Environment variable
5. Local default

Supported settings:

| TeamCity/system property | Environment variable |
| --- | --- |
| `jenkins.bridge.enabled` | `JENKINS_BRIDGE_ENABLED` |
| `jenkins.bridge.jenkinsUrl` | `JENKINS_URL` |
| `jenkins.bridge.jenkinsUser` | `JENKINS_USER` |
| `jenkins.bridge.jenkinsToken` | `JENKINS_TOKEN` |
| `jenkins.bridge.jenkinsJob` | `JENKINS_JOB` |
| `jenkins.bridge.teamCityUrl` | `TEAMCITY_URL` |
| `jenkins.bridge.teamCityUser` | `TEAMCITY_USER` |
| `jenkins.bridge.teamCityPassword` | `TEAMCITY_PASSWORD` |
| `jenkins.bridge.teamCityBuildTypeId` | `TEAMCITY_BUILD_TYPE_ID` |
| `jenkins.bridge.pollSeconds` | `BRIDGE_POLL_SECONDS` |
| `jenkins.bridge.recentBuildLimit` | `RECENT_BUILDS_LIMIT` |
| `jenkins.bridge.timeZone` | `TIMEZONE` |
| `jenkins.bridge.stateFile` | `BRIDGE_STATE_FILE` |

TeamCity parameter references such as `%another.param%` are resolved through
TeamCity's value resolver.

State
-----

By default, mirror state is stored in TeamCity plugin data:

`<TEAMCITY_DATA_PATH>/system/pluginData/jenkins-bridge/jenkins-teamcity-mapping.json`

`recentBuildLimit` controls only cold-start backfill. After the first poll,
discovery is incremental, and every new Jenkins build after the stored watermark
is considered. If Jenkins build numbers are reset or reused, the bridge also
uses the Jenkins build timestamp to identify the run.

Build
-----

Run from the repository root:

```bash
mvn package
```

The build uses local TeamCity 2026.2-SNAPSHOT EAP Maven artifacts. They must be
available under `${user.home}/.m2/repository/TeamCity`.

The latest plugin archive is written to:

`target/jenkins-bridge.zip`

The build also keeps timestamped Git-SHA archives such as:

`target/jenkins-bridge-20260608123456-2d8bab4.zip`

Install
-------

Copy `target/jenkins-bridge.zip` into the TeamCity data directory's `plugins`
folder, then restart TeamCity.
