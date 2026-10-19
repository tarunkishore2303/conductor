package com.tarunkishore.loom_api.ai.optimization.persistence;

import com.tarunkishore.loom_api.ai.optimization.client.WorkflowOptimizationClient;
import com.tarunkishore.loom_api.ai.optimization.domain.OptimizationFacts;
import com.tarunkishore.loom_api.ai.optimization.domain.OptimizationReport;
import com.tarunkishore.loom_api.ai.optimization.support.OptimizationContextFingerprint;
import com.tarunkishore.loom_api.ai.validation.OptimizationInterpretationValidator;
import com.tarunkishore.loom_api.exception.AiOutputException;
import com.tarunkishore.loom_api.repository.AiWorkflowOptimizationRepository;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/** Serialization, immutable snapshot storage and unique-insert races; no model calls. */
@Component
public final class OptimizationReportStore {
    // v4: explicit canonical ordering and informational parallel-execution classification.
    public static final int VERSION = 4;
    private final AiWorkflowOptimizationRepository repository;
    private final ObjectMapper mapper;
    private final OptimizationContextFingerprint fingerprints;

    public OptimizationReportStore(AiWorkflowOptimizationRepository repository, ObjectMapper mapper,
            OptimizationContextFingerprint fingerprints) {
        this.repository = repository;
        this.mapper = mapper;
        this.fingerprints = fingerprints;
    }

    public PreparedFacts prepare(OptimizationFacts facts) {
        String json = mapper.writeValueAsString(facts);
        if (json.getBytes(StandardCharsets.UTF_8).length > 24000)
            throw new AiOutputException("Report context exceeds the allowed size");
        return new PreparedFacts(json, fingerprints.reportHash(json, VERSION));
    }

    public Optional<OptimizationReport> find(UUID workflowId, String hash) {
        return repository.findByWorkflowIdAndContextHash(workflowId, hash).map(this::read);
    }

    public OptimizationReport latest(UUID workflowId) {
        return repository.findFirstByWorkflowIdOrderByGeneratedAtDescIdDesc(workflowId).map(this::read)
                .orElseThrow(() -> new NoSuchElementException("No stored workflow optimization for this workflow"));
    }

    public OptimizationReport save(OptimizationFacts facts, PreparedFacts prepared,
            WorkflowOptimizationClient.Result result) {
        OptimizationInterpretationValidator.validate(result, facts);
        String interpretationJson = mapper.writeValueAsString(result.interpretation());
        if (interpretationJson.getBytes(StandardCharsets.UTF_8).length > 96000)
            throw new AiOutputException("Optimization interpretation exceeds the storage budget");
        var row = new AiWorkflowOptimization();
        row.setId(UUID.randomUUID());
        row.setWorkflowId(facts.workflowId());
        row.setContextHash(prepared.hash());
        row.setFactsJson(prepared.json());
        row.setInterpretationJson(interpretationJson);
        row.setModel(result.model());
        row.setGeneratedAt(Instant.now().truncatedTo(ChronoUnit.MICROS));
        row.setOptimizationVersion(VERSION);
        try {
            return read(repository.saveAndFlush(row));
        } catch (DataIntegrityViolationException conflict) {
            return find(facts.workflowId(), prepared.hash()).orElseThrow(() -> conflict);
        }
    }

    public record PreparedFacts(String json, String hash) {}

    private OptimizationReport read(AiWorkflowOptimization row) {
        try {
            var facts = mapper.readValue(row.getFactsJson(), OptimizationFacts.class);
            if (facts == null || !row.getWorkflowId().equals(facts.workflowId()))
                throw new AiOutputException("Stored workflow optimization facts are invalid");
            var interpretation =
                    mapper.readValue(
                            row.getInterpretationJson(),
                            WorkflowOptimizationClient.Interpretation.class);
            OptimizationInterpretationValidator.validate(new WorkflowOptimizationClient.Result(interpretation, row.getModel()), facts);
            return new OptimizationReport(
                    row.getId(),
                    row.getWorkflowId(),
                    facts,
                    interpretation,
                    row.getModel(),
                    row.getGeneratedAt(),
                    row.getOptimizationVersion(),
                    row.getContextHash());
        } catch (JacksonException invalid) {
            throw new AiOutputException("Stored workflow optimization is invalid");
        }
    }

}
