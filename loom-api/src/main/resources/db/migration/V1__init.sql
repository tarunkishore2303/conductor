CREATE TABLE jobs (
    id              UUID PRIMARY KEY,
    name            VARCHAR(255)    NOT NULL,
    status          VARCHAR(50)     NOT NULL,
    failure_policy  VARCHAR(50)     NOT NULL,
    created_at      TIMESTAMPTZ     NOT NULL,
    updated_at      TIMESTAMPTZ     NOT NULL,
    version         BIGINT          NOT NULL DEFAULT 0
);

CREATE TABLE tasks (
    id          UUID PRIMARY KEY,
    job_id      UUID            NOT NULL REFERENCES jobs(id),
    name        VARCHAR(255)    NOT NULL,
    status      VARCHAR(50)     NOT NULL,
    max_retries INT             NOT NULL DEFAULT 3,
    retry_count INT             NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ     NOT NULL,
    updated_at  TIMESTAMPTZ     NOT NULL,
    version     BIGINT          NOT NULL DEFAULT 0
);

CREATE TABLE task_dependencies (
    task_id             UUID NOT NULL REFERENCES tasks(id),
    depends_on_task_id  UUID NOT NULL REFERENCES tasks(id),
    PRIMARY KEY (task_id, depends_on_task_id)
);

CREATE TABLE task_executions (
    id           UUID PRIMARY KEY,
    task_id      UUID        NOT NULL REFERENCES tasks(id),
    execution_id UUID        NOT NULL UNIQUE,
    status       VARCHAR(50) NOT NULL,
    started_at   TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ
);

CREATE INDEX idx_tasks_job_id     ON tasks(job_id);
CREATE INDEX idx_tasks_status     ON tasks(status);
CREATE INDEX idx_executions_task  ON task_executions(task_id);
