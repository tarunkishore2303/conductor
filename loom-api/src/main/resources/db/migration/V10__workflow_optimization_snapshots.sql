CREATE TABLE ai_workflow_optimizations (
    id UUID PRIMARY KEY,
    workflow_id UUID NOT NULL REFERENCES workflow_templates(id),
    context_hash VARCHAR(64) NOT NULL,
    facts_json TEXT NOT NULL,
    interpretation_json TEXT NOT NULL,
    model VARCHAR(128) NOT NULL,
    generated_at TIMESTAMPTZ NOT NULL,
    optimization_version INTEGER NOT NULL,
    CONSTRAINT uq_ai_workflow_optimization_context UNIQUE(workflow_id, context_hash),
    CONSTRAINT ck_ai_optimization_facts_size CHECK(octet_length(facts_json) <= 96000),
    CONSTRAINT ck_ai_optimization_interpretation_size CHECK(octet_length(interpretation_json) <= 96000)
);
CREATE INDEX idx_ai_workflow_optimization_latest
    ON ai_workflow_optimizations(workflow_id, generated_at DESC, id DESC);
