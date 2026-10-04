CREATE TABLE ai_workflow_proposals (
    id UUID PRIMARY KEY,
    workflow_json TEXT NOT NULL,
    validation_errors_json TEXT NOT NULL,
    model VARCHAR(128) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    approved_workflow_id UUID REFERENCES workflow_templates(id),
    version BIGINT NOT NULL DEFAULT 0
);
