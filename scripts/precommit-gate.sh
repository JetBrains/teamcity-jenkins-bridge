#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

echo "[pre-commit] checking staged whitespace..."
if ! git diff --cached --check; then
  echo "[pre-commit] staged whitespace errors found" >&2
  exit 1
fi

echo "[pre-commit] checking staged conflict markers..."
if git diff --cached --diff-filter=ACM -- '*.java' '*.xml' '*.properties' \
    | rg -n '^\+.*(<<<<<<<|=======|>>>>>>>)' >/dev/null; then
  echo "[pre-commit] staged conflict markers found" >&2
  exit 1
fi

echo "[pre-commit] compiling main and test sources..."
MAVEN_REPO_ARGS=()
if [[ -n "${JENKINS_BRIDGE_MAVEN_REPO:-}" ]]; then
  MAVEN_REPO_ARGS=("-Dmaven.repo.local=${JENKINS_BRIDGE_MAVEN_REPO}")
fi
if [[ ${#MAVEN_REPO_ARGS[@]} -gt 0 ]]; then
  mvn "${MAVEN_REPO_ARGS[0]}" -pl jenkins-bridge-server \
    -DskipTests \
    -Dmaven.compiler.showWarnings=true \
    -Dmaven.compiler.compilerArgs=-Xlint:all \
    test-compile
else
  mvn -pl jenkins-bridge-server \
    -DskipTests \
    -Dmaven.compiler.showWarnings=true \
    -Dmaven.compiler.compilerArgs=-Xlint:all \
    test-compile
fi

if command -v gitleaks >/dev/null 2>&1; then
  echo "[pre-commit] scanning staged changes with gitleaks..."
  gitleaks protect --staged --redact --no-banner
else
  echo "[pre-commit] gitleaks not installed; skipping optional secret scan"
fi

echo "[pre-commit] gate passed"
