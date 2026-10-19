-- Explicit future-only identity. Never infer historical linkage from names or configuration.
ALTER TABLE jobs ADD COLUMN workflow_template_id UUID REFERENCES workflow_templates(id);
ALTER TABLE tasks ADD COLUMN logical_id TEXT;
CREATE INDEX idx_jobs_workflow_history ON jobs (workflow_template_id, created_at DESC, id);
