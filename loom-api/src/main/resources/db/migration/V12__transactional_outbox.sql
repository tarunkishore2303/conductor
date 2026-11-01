ALTER TABLE tasks ADD COLUMN execution_generation INT NOT NULL DEFAULT 0
    CHECK (execution_generation >= 0);
ALTER TABLE task_executions ADD COLUMN execution_generation INT NOT NULL DEFAULT 0
    CHECK (execution_generation >= 0);

CREATE TABLE outbox_events (
    id UUID PRIMARY KEY,
    dedupe_key VARCHAR(255) NOT NULL UNIQUE,
    topic VARCHAR(100) NOT NULL CHECK (topic IN ('job-created','task-queue','task-results')),
    event_key VARCHAR(255) NOT NULL,
    event_type VARCHAR(100) NOT NULL CHECK (event_type IN ('JobCreatedEvent','TaskEvent','TaskResultEvent')),
    payload_json TEXT NOT NULL CHECK (octet_length(payload_json) BETWEEN 1 AND 32768),
    available_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    claimed_by VARCHAR(128),
    claimed_until TIMESTAMPTZ,
    publish_attempts INT NOT NULL DEFAULT 0 CHECK (publish_attempts >= 0),
    last_attempt_at TIMESTAMPTZ,
    published_at TIMESTAMPTZ,
    CHECK ((claimed_by IS NULL) = (claimed_until IS NULL))
);
CREATE INDEX idx_outbox_due ON outbox_events (available_at, claimed_until, created_at, id)
    WHERE published_at IS NULL;
