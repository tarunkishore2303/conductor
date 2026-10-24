# Contributing to Conductor

## Requirements

- Java 25; use the checked-in Gradle wrapper, not a global Gradle install.
- Docker or Podman with a Docker-compatible API for integration tests.
- Python 3.13 (standard library only).
- Local Ollama is optional and is not required for automated tests.

Windows/Podman setup remains documented in the root [README](README.md).
Linux CI uses Docker defaults; do not copy Windows named-pipe or rootless
Podman/Ryuk overrides into GitHub Actions.

## Before opening a PR

Run from the repository root:

```bash
./gradlew build :loom-api:integrationTest :loom-worker:integrationTest --no-daemon
python -m unittest discover -s scripts/tests -q
git diff --check
docker compose --profile ai --profile demo config --quiet
COMPOSE_BAKE=false docker compose --parallel 2 --profile ai build loom-api loom-ai loom-worker-1 loom-scheduler loom-monitor
```

The API and worker currently contain all container-backed integration tests.
On Windows, use `gradlew.bat` and `scripts/compose.ps1` for Podman/Compose.
CI runs the same suites with fake AI clients, without downloading models.

## Invariants

- Never modify or remove a Flyway migration that has shipped/merged. Add
  V12, V13, etc. instead. CI rejects changes to migrations present in the base.
- AI is local through Ollama only. Do not add cloud model/API dependencies.
- AI never gains scheduling, worker, SQL, or deterministic execution authority.
  Proposals require deterministic validation, preview, and explicit approval.
- Never commit secrets, environment files, captured task outputs, or model files.
- Keep changes focused and report verification limits honestly.

## Recommended repository protection

Require a PR before merging to `main` and require these stable CI checks:
`Build & Unit Tests`, `Integration Tests`, `Python Tests`, and `Container Build`.
Consider requiring branches to be up to date and review conversations resolved.
These are recommendations; the workflow does not configure branch protection.
