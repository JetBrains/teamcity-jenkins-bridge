# Repository Structure

The following is a list of the main topics that form the plugin's implementation:

- **Settings and features**: global plugin configuration, and a TeamCity build feature that marks a build configuration as a Jenkins mirror target.
- **Jenkins client**: talks to the Jenkins REST API to discover jobs, builds, pipeline graph, stages, tests, artifacts, and VCS info.
- **Models**: Java representations of Jenkins JSON responses.
- **Persistence**: tracks per build sync state (log offset, stage progress, pipeline chain state), stored via TeamCity's `CustomDataStorage`.
- **Polling service**: a background scheduled job that polls Jenkins (every 10s by default) and feeds discovered/changed builds into the sync pipeline.
- **TeamCity integration**: the core sync logic. Starts and finishes TeamCity builds, streams Jenkins console logs, reports stages and tests, syncs build parameters and numbers, and publishes VCS info.
- **Pipeline chain mirroring**: represents a multi-stage Jenkins pipeline as a chain of TeamCity builds.
- **VCS mirroring**: maps Jenkins VCS info onto TeamCity VCS attachments. Only Git is possible to implement at this point.
- **Artifact storage**: registers Jenkins as a TeamCity external artifact storage provider, so TeamCity does not need to store or serve Jenkins build artifacts.
- **Web UI**: project level tabs and controllers for job discovery/import, and build page extensions showing Jenkins build results and pipeline graphs.
