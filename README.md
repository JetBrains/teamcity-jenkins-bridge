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

The remaining settings are server-wide. They are read from a root project
parameter first, then a Java system property, then an environment variable, then
the default.

| TeamCity/system property                      | Environment variable              | Default               |
|-----------------------------------------------|-----------------------------------|-----------------------|
| `jenkins.bridge.enabled`                      | `JENKINS_BRIDGE_ENABLED`          | `true`                |
| `jenkins.bridge.pollSeconds`                  | `BRIDGE_POLL_SECONDS`             | `10`                  |
| `jenkins.bridge.pendingTriggerTimeoutMinutes` | `PENDING_TRIGGER_TIMEOUT_MINUTES` | `1440`                |
| `jenkins.bridge.stateFile` (deprecated)       | `BRIDGE_STATE_FILE`               | plugin data directory |

TeamCity parameter references such as `%another.param%` are resolved through
TeamCity's value resolver.

## State

By default, the mirror state is stored in TeamCity's internal database.

The build feature's "No. of builds to import on first sync" setting controls only
defaults to 1 (only the newest Jenkins build). After the
first poll, discovery is incremental, and every new Jenkins build after the
stored watermark is considered. If Jenkins build numbers are reset or reused, the
bridge also uses the Jenkins build timestamp to identify the run.

## Build

Run from the repository root:

```bash
mvn package
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

## Install

Copy `target/jenkins-bridge.zip` into the TeamCity data directory's `plugins`
folder, then restart TeamCity.
