CREATE TABLE ai_run_summaries (
    id UUID PRIMARY KEY,
    job_id UUID NOT NULL REFERENCES jobs(id),
    context_hash VARCHAR(64) NOT NULL,
    facts_json TEXT NOT NULL,
    interpretation_json TEXT NOT NULL,
    model VARCHAR(128) NOT NULL,
    generated_at TIMESTAMPTZ NOT NULL,
    summary_version INTEGER NOT NULL,
    CONSTRAINT uq_ai_run_summary_context UNIQUE(job_id, context_hash)
);
CREATE INDEX idx_ai_run_summary_latest ON ai_run_summaries(job_id, generated_at DESC);
