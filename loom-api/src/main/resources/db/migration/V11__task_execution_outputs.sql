ALTER TABLE task_executions
    ADD COLUMN output_text TEXT,
    ADD COLUMN output_content_type VARCHAR(32),
    ADD CONSTRAINT chk_execution_output_pair CHECK
        ((output_text IS NULL) = (output_content_type IS NULL)),
    ADD CONSTRAINT chk_execution_output_type CHECK
        (output_content_type IS NULL OR output_content_type IN ('application/json', 'text/plain')),
    ADD CONSTRAINT chk_execution_output_complete CHECK
        (output_text IS NULL OR status = 'COMPLETE'),
    ADD CONSTRAINT chk_execution_output_size CHECK
        (output_text IS NULL OR octet_length(output_text) <= 65536);
