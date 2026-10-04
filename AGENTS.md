# Conductor repository instructions

Conductor extends Project Loom. Preserve its Git history and document that origin.

## Architecture

- The orchestration engine is deterministic. AI produces proposals only.
- AI must never schedule tasks, call workers, publish execution events, execute arbitrary SQL, or bypass DAG validation.
- Manual workflow creation and AI approval must use the same WorkflowService validation and persistence path.
- Generation creates a preview, not a workflow. Persist workflows only after explicit approval; execution remains a separate request.
- Match generated capabilities to actual worker support. The current worker only simulates no-op tasks.
- Keep model-provider code in loom-ai; keep orchestration and workflow persistence in existing modules.
- Use Ollama only for project AI capabilities. Do not introduce cloud model APIs or API-key requirements.
- Scope changes incrementally. Do not add RAG, embeddings, copilot, failure analysis, optimization, or AI frontend until their phase is authorized.

## Engineering and verification

- Use Java 25, Spring Boot 4, the Gradle wrapper, and Podman with standalone Docker Compose.
- Keep Flyway migration ownership in loom-api; never edit applied migrations.
- Treat all model output as untrusted. Bound requests and context, validate structured output, and revalidate on approval.
- Never commit secrets or log full prompts/provider credentials. AI outages must not prevent normal orchestration.
- Use fake or mocked models in normal tests. Live-provider tests are optional and explicitly enabled.
- Run relevant unit and integration tests alongside features. Report verification limits honestly.
- Keep commits logically separated and follow the global Codex commit attribution instructions.
- Put project documentation in the root README.md. Do not add separate documentation files unless the user explicitly requests them.
