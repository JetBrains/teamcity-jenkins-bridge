# Jenkins Bridge

Jenkins Bridge is a TeamCity server-side plugin that mirrors Jenkins builds into
agentless TeamCity builds.

For each Jenkins run, the plugin can:

- Create or restore the matching TeamCity build
- Stream Jenkins console output into the TeamCity build log
- Map Jenkins results such as SUCCESS, FAILURE, UNSTABLE, ABORTED, and NOT_BUILT to the TeamCity build outcome
- Import JUnit-style test results from Jenkins `testReport/api/json`
- Automatically register or reuse VCS roots and detect changes
- Copy Jenkins build parameters into TeamCity custom build parameters
- Automatically use Jenkins as artifact storage and display all published artifacts
- Persist mirror state in the internal TeamCity database for faster restart recovery

Pipeline support is also available for stage/log mirroring. Native TeamCity
build-chain mirroring is experimental and should be runtime-validated before it
is presented as a stable demo feature.

## Connecting to Jenkins

A Jenkins server is configured as a project connection, under Project Settings >
Integrations > Connections > Add Connection > Jenkins. The connection includes the
display name, the Jenkins URL, the username, and that user's API token. A project
can hold several Jenkins connections, and connections are inherited by
subprojects.

To mirror a Jenkins job, visit the "Jenkins Jobs Sync" tab in the project admin
view. This screen will let you automatically create build configurations which have the
"Jenkins Bridge" build feature, letting the plugin know they are mirror targets.

## Server settings

The remaining settings are server-wide TeamCity internal properties. Configure
them in TeamCity's data-directory properties configuration. Changes require a
server restart to take effect.

| TeamCity internal property                    | Default               |
|-----------------------------------------------|-----------------------|
| `jenkins.bridge.enabled`                      | `true`                |
| `jenkins.bridge.pollSeconds`                  | `10`                  |
| `jenkins.bridge.pendingTriggerTimeoutMinutes` | `1440`                |

## State

By default, the mirror state is stored in TeamCity's internal database.

The build feature's "No. of builds to import on first sync" setting controls only
defaults to 1 (only the newest Jenkins build). After the
first poll, discovery is incremental, and every new Jenkins build after the
stored watermark is considered. If Jenkins build numbers are reset or reused, the
bridge also uses the Jenkins build timestamp to identify the run.

## Build

Run unit tests from the repository root:

```bash
mvn test
```

### Pre-commit gate

Enable the repository-local hook once per checkout:

```bash
git config core.hooksPath .githooks
```

The hook checks staged whitespace and conflict markers, then runs
`mvn -pl jenkins-bridge-server test-compile` with compiler warnings enabled. It deliberately
targets the Java server module: the root `build` module only assembles the plugin archive and
requires the separate Maven `replacer` plugin setup. Archive packaging remains a separate
`mvn package` check. The hook also runs an optional `gitleaks` staged secret scan when `gitleaks`
is installed. Set `JENKINS_BRIDGE_MAVEN_REPO` when Maven should use an isolated local repository.

`mvn verify` additionally runs the database-backed TeamCity integration tests. Those
fixture tests require Java 21 because the current TeamCity test server still uses
the legacy Security Manager; the Maven profile enables the required Java 21 flag
automatically.

The default TeamCity API version is `2026.3-SNAPSHOT`, and Maven looks for its
artifacts under `${user.home}/.m2/repository/TeamCity`. Both values are
configurable, so plugin developers can build against a published TeamCity
version or a local TeamCity source build.

For a local TeamCity source build, point Maven at its `local-repo` directory:

```bash
mvn package \
  -Dteamcity-version=2026.3-SNAPSHOT \
  -Dteamcity-repository-url=file:///path/to/TeamCity/local-repo
```

If the selected TeamCity repository does not contain `license-protected` at the
same version, override that test-only dependency separately:

```bash
mvn test \
  -Dteamcity-version=2026.3-DSL-eap1-SNAPSHOT \
  -Dteamcity-license-version=2026.2-SNAPSHOT
```

### Persistent local Maven settings

Instead of passing these properties on every command, define them in a personal
Maven profile in `${user.home}/.m2/settings.xml`. Do not commit this file because
the repository path is machine-specific:

```xml
<settings>
  <profiles>
    <profile>
      <id>teamcity-local</id>
      <properties>
        <teamcity-version>2026.3-SNAPSHOT</teamcity-version>
        <teamcity-repository-url>file:///path/to/TeamCity/local-repo</teamcity-repository-url>
        <teamcity-license-version>2026.3-SNAPSHOT</teamcity-license-version>
      </properties>
    </profile>
  </profiles>
  <activeProfiles>
    <activeProfile>teamcity-local</activeProfile>
  </activeProfiles>
</settings>
```

After that, the normal command uses the configured version and repository:

```bash
mvn package
```

For a repository where `license-protected` is only available from another
TeamCity line, change only `teamcity-license-version` in this personal profile.

The latest plugin archive is written to:

`target/jenkins-bridge.zip`

The build also keeps timestamped Git-SHA archives such as:

`target/jenkins-bridge-20260608123456-2d8bab4.zip`

### Live trigger-failure checks

For a disposable TeamCity/Jenkins test job, add the custom TeamCity run parameter
`jenkins.bridge.test.failureMode` with one of these values:

- `before-jenkins`: records native `Failed to start build` without calling Jenkins.
- `after-jenkins`: calls Jenkins, then intentionally records the TeamCity promotion as failed to start;
  use only with a disposable Jenkins job because Jenkins may accept the run.

The parameter is consumed by the bridge and is not forwarded to Jenkins. Remove it for normal runs.

## Install

Copy `target/jenkins-bridge.zip` into the TeamCity data directory's `plugins`
folder, then restart TeamCity.
