-- Historical tasks remain NOOP. Runtime configuration is immutable task definition input,
-- not an output store; bounded TEXT keeps older template JSON and event envelopes compatible.
ALTER TABLE tasks ADD COLUMN task_type VARCHAR(32) NOT NULL DEFAULT 'NOOP';
ALTER TABLE tasks ADD COLUMN task_config_json TEXT NOT NULL DEFAULT '{}';
ALTER TABLE tasks ADD CONSTRAINT tasks_supported_type
    CHECK (task_type IN ('NOOP', 'DELAY', 'HTTP_REQUEST', 'TRANSFORM'));
ALTER TABLE tasks ADD CONSTRAINT tasks_config_size
    CHECK (octet_length(task_config_json) <= 65536);
