CREATE TABLE workflow_templates (
    id                      UUID PRIMARY KEY,
    name                    VARCHAR(255)    NOT NULL,
    description             TEXT,
    dag_definition_json     TEXT            NOT NULL,
    default_failure_policy  VARCHAR(50)     NOT NULL,
    created_at              TIMESTAMPTZ     NOT NULL,
    updated_at              TIMESTAMPTZ     NOT NULL,
    version                 BIGINT          NOT NULL DEFAULT 0
);
