# Conductor runtime upgrade

Conductor preserves the history of
[Project Loom](https://github.com/tarunkishore2303/project-loom).
The existing `loom-*` modules and event contracts remain the orchestration foundation.

## Runtime baseline

| Component | Before | After |
| --- | --- | --- |
| Java | 21 target | 25 toolchain and container runtime |
| Spring Boot | 3.2.4 | 4.1.1 |
| Gradle | 8.8 | 9.8.0, checksum verified |
| Dependency management plugin | 1.1.4 | 1.1.7 |
| Redisson starter | 3.27.2 | 4.8.0 |
| springdoc | 2.4.0 | 3.1.1 |
| JSON | Jackson 2 | Jackson 3 |
| Local container engine | Docker documented | Podman |

Spring Boot's BOM manages Testcontainers and other platform dependencies. The
previous Testcontainers BOM override has been removed. Its version 2 module names,
PostgreSQL container API, and Confluent Kafka container class are used in tests.

## Decisions

- **Java toolchain:** selects JDK 25 for compilation and tests rather than just
  setting source/target compatibility. A local JDK 25 installation is required;
  containers use Temurin 25.
- **Boot 4 starters:** MVC, Kafka, and Flyway use their focused starters so their
  auto-configuration remains available after Boot's modularization. PostgreSQL's
  Flyway database support is an explicit runtime dependency.
- **Jackson 3:** workflow JSON and Kafka serializers migrate together, retaining
  existing topic names and event fields. This avoids a temporary Jackson 2
  compatibility module and duplicate mapper configuration.
- **Separate test suites:** `build` includes unit tests; `integrationTest` runs
  the container-backed tests explicitly. CI must invoke both. Test JVMs use UTC
  so host timezone aliases do not affect PostgreSQL connections.
- **Compose client and engine:** standalone Docker Compose interprets `compose.yml`
  and connects to Podman's Docker-compatible API. Docker Desktop and the Docker
  daemon are not required. `podman-compose` 1.6.0 dropped the Dockerfile argument
  for Windows drive-letter build contexts, so it is not the selected provider.
- **Container build context:** each service includes every module's build
  descriptor because Gradle 9 rejects missing included project directories.
  Only that service and `loom-common` source are copied. The Gradle distribution
  download has a shared cache layer and bounded retries.

## Verification commands

```powershell
.\gradlew.bat build
.\gradlew.bat integrationTest
.\scripts\compose.ps1 config
.\scripts\compose.ps1 up --build -d
.\scripts\compose.ps1 ps
```

After startup, submit a DAG through `/api/v1/jobs` and poll `/api/v1/jobs/{id}`.
Check `/actuator/health`, `/api-docs`, and `/monitor/stats` as part of the runtime
smoke test. Passing unit tests alone does not establish container startup or
distributed execution correctness.

This upgrade does not resolve the previously audited durable-retry, outbox,
cancellation, or DLQ replay gaps. Those remain separate implementation tasks.

## Verified on Windows with Podman

- All modules compile and package with JDK 25 and Spring Boot 4.1.1.
- Unit suite: 13 behavior tests plus the existing placeholder test pass.
- API Testcontainers suite: 8 tests pass; worker suite: 3 tests pass.
- All Compose service images build, and the local stack starts successfully.
- Flyway applies both existing migrations and Hibernate validates the schema.
- A three-task linear workflow reaches `COMPLETE` through the running API,
  Kafka, reactive scheduler, and worker.
- API health reports `UP`; OpenAPI and Prometheus endpoints respond successfully.
- Monitor reports one completed job and three completed tasks after the smoke run.

This is a baseline upgrade smoke test, not a concurrency or fault-recovery test.

## References

- [Spring Boot system requirements](https://docs.spring.io/spring-boot/system-requirements.html)
- [Spring Boot 4 migration guide](https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-4.0-Migration-Guide)
- [Gradle Java compatibility](https://docs.gradle.org/current/userguide/compatibility.html)
- [springdoc compatibility](https://springdoc.org/faq.html)
- [Redisson Spring integration](https://redisson.pro/docs/integration-with-spring/)
