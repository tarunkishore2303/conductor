# Project Loom

Distributed async job orchestration engine — a production-grade mini-Airflow built with Spring Boot, Kafka, Redis, and PostgreSQL.

Submit a DAG of tasks via REST. Loom validates the graph, persists it, and executes tasks across horizontally-scaled workers with distributed locking, exponential backoff retry, dead-letter queuing, and real-time observability.

---

## Architecture

```
┌──────────────┐   POST /jobs    ┌─────────────────────┐
│    Client    │ ──────────────► │     loom-api         │  :8080
└──────────────┘                 │  DAG validation      │
                                 │  Job persistence      │
                                 │  Flyway migrations    │
                                 └─────────┬─────────────┘
                                           │ job-created (Kafka)
                                           ▼
                                 ┌─────────────────────┐
                                 │   loom-scheduler     │
                                 │  Reactive R2DBC      │
                                 │  Root task detection  │
                                 │  Downstream unblock   │
                                 │  Optimistic locking   │
                                 └─────────┬─────────────┘
                                           │ task-queue (Kafka)
                              ┌────────────┴────────────┐
                              ▼                          ▼
                    ┌──────────────────┐      ┌──────────────────┐
                    │  loom-worker-1   │      │  loom-worker-2   │  :8081 / :8082
                    │  Redisson lock   │      │  Redisson lock   │
                    │  Idempotency     │      │  Idempotency     │
                    │  Retry + DLQ     │      │  Retry + DLQ     │
                    └──────────────────┘      └──────────────────┘
                                           │ task-results (Kafka)
                                           ▼
                                 ┌─────────────────────┐
                                 │    loom-monitor      │  :8083
                                 │  Read-only stats     │
                                 │  DLQ browser         │
                                 └─────────────────────┘
```

### Services

| Service | Role | Port | Stack |
|---|---|---|---|
| `loom-common` | Shared entities, DTOs, Kafka events | — | JPA, Jackson |
| `loom-api` | REST API, DAG validation, job persistence | 8080 | Spring MVC, JPA, Flyway, Kafka producer |
| `loom-scheduler` | DAG execution engine, task scheduling | — | WebFlux, R2DBC, Kafka consumer |
| `loom-worker` | Task execution, retry, DLQ | 8081 / 8082 | Spring MVC, JPA, Redisson, Kafka |
| `loom-monitor` | Read-only stats and DLQ browser | 8083 | Spring MVC, JPA |

### Infrastructure

| Component | Purpose |
|---|---|
| PostgreSQL 15 | Persistent store — jobs, tasks, executions, workflow templates |
| Kafka + ZooKeeper | Event bus — `job-created`, `task-queue`, `task-results` |
| Redis | Distributed locks (Redisson) preventing duplicate task execution |
| Prometheus | Metrics scraping from all services |
| Grafana | Metrics dashboards |

---

## Key Design Decisions

- **DAG validation** — cycle detection (DFS) and unknown-dependency checks before any DB write
- **Post-commit Kafka publish** — `TransactionSynchronization.afterCommit()` ensures scheduler only sees persisted jobs
- **Reactive scheduler** — R2DBC + WebFlux for non-blocking DAG traversal under load
- **Distributed locking** — Redisson `tryLock` prevents two workers racing on the same task
- **Idempotent execution** — deterministic `executionId = UUID.nameUUIDFromBytes(taskId + attemptNumber)` guards against duplicate processing on retry
- **Exponential backoff** — retry delay = `2^attemptNumber` seconds, up to `maxRetries`
- **FAIL_FAST policy** — on max retries exhausted, cancels all PENDING tasks and marks job FAILED
- **Optimistic locking** — `version` column on both `jobs` and `tasks` prevents lost updates across concurrent workers

---

## Getting Started

### Prerequisites

- Docker + Docker Compose
- Java 21 (for local builds only)

### Run

```bash
./gradlew build -x test
docker-compose up --build
```

Services start in dependency order:
1. PostgreSQL, ZooKeeper, Kafka, Redis
2. loom-api (runs Flyway migrations)
3. loom-scheduler, loom-worker-1, loom-worker-2, loom-monitor
4. Prometheus, Grafana

### URLs

| Service | URL |
|---|---|
| API | http://localhost:8080 |
| Swagger UI | http://localhost:8080/swagger-ui.html |
| Monitor | http://localhost:8083/monitor/stats |
| Prometheus | http://localhost:9090 |
| Grafana | http://localhost:3000 (admin / admin) |

---

## API Reference

### Submit a Job

```bash
POST /api/v1/jobs
```

```json
{
  "name": "etl-pipeline",
  "failurePolicy": "FAIL_FAST",
  "tasks": [
    { "taskId": "a", "name": "Extract",   "dependsOn": [],         "maxRetries": 3 },
    { "taskId": "b", "name": "Transform", "dependsOn": ["a"],      "maxRetries": 3 },
    { "taskId": "c", "name": "Load",      "dependsOn": ["b"],      "maxRetries": 3 }
  ]
}
```

`failurePolicy` values:
- `FAIL_FAST` — first dead-lettered task cancels all pending tasks, job → FAILED
- `CONTINUE` — other tasks keep running; job → FAILED only when all terminal

### Other Endpoints

| Method | Path | Description |
|---|---|---|
| `GET` | `/api/v1/jobs/{jobId}` | Get job + task statuses |
| `DELETE` | `/api/v1/jobs/{jobId}` | Cancel job (→ CANCELLING) |
| `POST` | `/api/v1/workflows` | Create reusable workflow template |
| `GET` | `/api/v1/workflows/{templateId}` | Get workflow template |
| `POST` | `/api/v1/workflows/{templateId}/jobs` | Submit job from template |
| `GET` | `/api/v1/dlq` | List dead-lettered tasks |
| `POST` | `/api/v1/dlq/{taskId}/retry` | Re-queue a dead-lettered task |
| `GET` | `/monitor/stats` | Aggregated job + task counts by status |
| `GET` | `/monitor/dlq` | Paginated dead-letter queue view |

### Job + Task Statuses

```
Job:   CREATED → RUNNING → COMPLETE | FAILED | CANCELLED
Task:  PENDING → RUNNING → COMPLETE | FAILED → DEAD_LETTERED | CANCELLED
```

### Example — Diamond DAG

```bash
curl -s -X POST http://localhost:8080/api/v1/jobs \
  -H "Content-Type: application/json" \
  -d '{
    "name": "diamond",
    "failurePolicy": "CONTINUE",
    "tasks": [
      { "taskId": "a", "name": "Ingest",    "dependsOn": [],         "maxRetries": 2 },
      { "taskId": "b", "name": "Branch-1",  "dependsOn": ["a"],      "maxRetries": 2 },
      { "taskId": "c", "name": "Branch-2",  "dependsOn": ["a"],      "maxRetries": 2 },
      { "taskId": "d", "name": "Aggregate", "dependsOn": ["b", "c"], "maxRetries": 2 }
    ]
  }' | jq .
```

---

## Observability

All services expose Prometheus metrics at `/actuator/prometheus`.

Key metrics:

| Metric | Description |
|---|---|
| `loom_jobs_submitted_total` | Jobs submitted, tagged by `failurePolicy` |
| `loom_tasks_completed_total` | Tasks completed, tagged by `taskName` |
| `loom_tasks_failed_total` | Tasks failed, tagged by `taskName` |
| `loom_task_execution_duration_seconds` | Task duration histogram, tagged by `taskName` + `outcome` |

---

## Development

### Run Tests

```bash
# Unit tests only
./gradlew :loom-api:test --tests "com.tarunkishore.loom_api.service.DAGValidatorTest"
./gradlew :loom-scheduler:test --tests "com.loom.scheduler.service.DAGSchedulerTest"
./gradlew :loom-worker:test --tests "com.loom.worker.service.TaskExecutorServiceTest"

# Integration tests (requires Docker)
./gradlew test
```

### Plugging in Real Task Logic

`loom-worker` ships a `NoOpTaskHandler` that sleeps 50ms. Implement `TaskHandler` and register it as a Spring bean to replace it:

```java
@Component
public class MyTaskHandler implements TaskHandler {
    @Override
    public void execute(TaskEvent event) throws Exception {
        // real logic here
    }
}
```

### Module Structure

```
project-loom/
├── loom-common/       # Shared models, DTOs, Kafka events
├── loom-api/          # REST API + Flyway migrations
├── loom-scheduler/    # Reactive DAG execution engine
├── loom-worker/       # Task executor (scale horizontally)
├── loom-monitor/      # Read-only observability service
├── docker-compose.yml
└── prometheus.yml
```

---

## Tech Stack

- **Java 21** / Spring Boot 3.2
- **Spring Data JPA** (loom-api, loom-worker, loom-monitor)
- **Spring Data R2DBC + WebFlux** (loom-scheduler)
- **Spring Kafka** — producer + consumer across all services
- **Redisson** — Redis-backed distributed locking
- **Flyway** — versioned DB migrations owned by loom-api
- **Micrometer + Prometheus + Grafana** — metrics pipeline
- **springdoc-openapi** — auto-generated Swagger UI
- **Testcontainers** — integration tests with real Postgres + Kafka
