# Jenkins Bridge

## Overview

An internal TeamCity server plugin. It mirrors Jenkins pipeline builds accurately into TeamCity, so Jenkins users can see their builds, stages, tests, artifacts, and VCS info inside TeamCity with no changes needed on the Jenkins side.

## Purpose

Many teams already run Jenkins pipelines. This plugin lets those teams get TeamCity's build and test visualization features on top of their existing Jenkins jobs, so they can try TeamCity's capabilities without friction or migration cost.

## Tech stack

- Java 21, built with Maven.
- TeamCity server SDK (server-api, common-api, server-core, server-web-api).
- Spring-based servlet controllers and dependency injection.
- GSON for JSON.
- Tests use JUnit 4 and Mockito.

## Main parts of the plugin

See [REPOSITORY_STRUCTURE.md](agent_docs/REPOSITORY_STRUCTURE.md).

## Code practices

See [CODE_PRACTICES.md](agent_docs/CODE_PRACTICES.md) before writing code.
