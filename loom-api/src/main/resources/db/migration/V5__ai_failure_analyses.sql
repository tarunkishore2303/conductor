CREATE TABLE ai_failure_analyses (
    id UUID PRIMARY KEY,
    job_id UUID NOT NULL REFERENCES jobs(id),
    context_hash VARCHAR(64) NOT NULL,
    facts_json TEXT NOT NULL,
    interpretation_json TEXT NOT NULL,
    model VARCHAR(128) NOT NULL,
    generated_at TIMESTAMPTZ NOT NULL,
    analysis_version INTEGER NOT NULL,
    CONSTRAINT uq_ai_failure_context UNIQUE(job_id, context_hash)
);
CREATE INDEX idx_ai_failure_job_generated ON ai_failure_analyses(job_id, generated_at DESC);
