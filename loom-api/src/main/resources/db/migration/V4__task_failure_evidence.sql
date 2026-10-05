ALTER TABLE task_executions
    ADD COLUMN attempt_number INT,
    ADD COLUMN error_type VARCHAR(255),
    ADD COLUMN error_message VARCHAR(2048),
    ADD COLUMN retry_scheduled_at TIMESTAMPTZ;

ALTER TABLE tasks ADD COLUMN dead_lettered_at TIMESTAMPTZ;

ALTER TABLE task_executions ADD CONSTRAINT chk_execution_attempt
    CHECK (attempt_number IS NULL OR attempt_number >= 0);
