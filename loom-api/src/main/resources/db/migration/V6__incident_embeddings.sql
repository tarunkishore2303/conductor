CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE ai_incident_embeddings (
    id UUID PRIMARY KEY,
    analysis_id UUID NOT NULL REFERENCES ai_failure_analyses(id),
    job_id UUID NOT NULL REFERENCES jobs(id),
    task_id UUID NOT NULL REFERENCES tasks(id),
    embedding_model VARCHAR(128) NOT NULL,
    embedding vector(768) NOT NULL,
    document_text VARCHAR(4000) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_ai_incident_embedding UNIQUE (analysis_id, task_id, embedding_model)
);

CREATE INDEX idx_ai_incident_model ON ai_incident_embeddings(embedding_model);
CREATE INDEX idx_ai_incident_cosine ON ai_incident_embeddings
    USING hnsw (embedding vector_cosine_ops);
