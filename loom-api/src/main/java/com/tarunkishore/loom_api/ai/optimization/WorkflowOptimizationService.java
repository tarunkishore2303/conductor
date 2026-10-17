package com.tarunkishore.loom_api.ai.optimization;

import com.tarunkishore.loom_api.ai.validation.OptimizationInterpretationValidator;
import com.tarunkishore.loom_api.exception.AiOutputException;
import com.tarunkishore.loom_api.exception.AiUnavailableException;
import com.tarunkishore.loom_api.repository.AiWorkflowOptimizationRepository;

import io.micrometer.core.instrument.MeterRegistry;
import com.loom.observability.OperationMetrics;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

@Service
public class WorkflowOptimizationService {
    private final OptimizationContextService contexts;
    private final WorkflowOptimizationClient client;
    private final OptimizationAnalyzerRegistry analyzers;
    private final AiWorkflowOptimizationRepository repository;
    private final ObjectMapper mapper;
    private final MeterRegistry metrics;
    private final ConcurrentHashMap<String, CompletableFuture<Report>> inFlight =
            new ConcurrentHashMap<>();

    public WorkflowOptimizationService(
            OptimizationContextService contexts,
            OptimizationAnalyzerRegistry analyzers,
            WorkflowOptimizationClient client,
            AiWorkflowOptimizationRepository repository,
            ObjectMapper mapper,
            MeterRegistry metrics) {
        this.contexts = contexts;
        this.analyzers = analyzers;
        this.client = client;
        this.repository = repository;
        this.mapper = mapper;
        this.metrics = metrics;
    }

    // Model latency never holds a database transaction or execution-engine lock.
    public Report generate(UUID workflowId) {
        var observation = OperationMetrics.start(metrics, OperationMetrics.Operation.OPTIMIZATION);
        try {
            var facts = analyzers.analyze(contexts.collect(workflowId));
            if (facts == null || !workflowId.equals(facts.workflowId()))
                throw new AiOutputException("Invalid workflow optimization facts");
            String json = mapper.writeValueAsString(facts);
            if (json.length() > 24000)
                throw new AiOutputException("Report context exceeds the allowed size");
            String hash = hash(json);
            var cached = repository.findByWorkflowIdAndContextHash(workflowId, hash);
            if (cached.isPresent()) {
                observation.outcome(OperationMetrics.Outcome.CACHED);
                return read(cached.get());
            }
            String key = workflowId + ":" + hash;
            var promise = new CompletableFuture<Report>();
            var existing = inFlight.putIfAbsent(key, promise);
            if (existing != null) {
                observation.outcome(OperationMetrics.Outcome.COALESCED);
                return await(existing);
            }
            try {
                var lateCache = repository.findByWorkflowIdAndContextHash(workflowId, hash);
                if (lateCache.isPresent()) {
                    var report = read(lateCache.get());
                    promise.complete(report);
                    observation.outcome(OperationMetrics.Outcome.CACHED);
                    return report;
                }
                var result = facts.candidates().isEmpty()
                        ? new WorkflowOptimizationClient.Result(new WorkflowOptimizationClient.Interpretation(
                            "INSUFFICIENT_HISTORY".equals(facts.outcome())
                                ? "Insufficient settled linked execution history: " + facts.runsConsidered()
                                    + " runs; minimum sample size is " + facts.minimumSampleSize() + "."
                                : "No optimization candidates met the configured evidence thresholds.", List.of()), "deterministic")
                        : client.interpret(facts);
                OptimizationInterpretationValidator.validate(result, facts);
                var row = new AiWorkflowOptimization();
                row.setId(UUID.randomUUID());
                row.setWorkflowId(workflowId);
                row.setContextHash(hash);
                row.setFactsJson(json);
                row.setInterpretationJson(mapper.writeValueAsString(result.interpretation()));
                row.setModel(result.model());
                // PostgreSQL timestamptz stores microseconds; initial and cached responses must agree.
                row.setGeneratedAt(Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS));
                row.setOptimizationVersion(1);
                Report report;
                try {
                    report = read(repository.saveAndFlush(row));
                } catch (DataIntegrityViolationException conflict) {
                    report =
                            repository
                                    .findByWorkflowIdAndContextHash(workflowId, hash)
                                    .map(this::read)
                                    .orElseThrow(() -> conflict);
                }
                promise.complete(report);
                return report;
            } catch (RuntimeException failure) {
                promise.completeExceptionally(failure);
                throw failure;
            } finally {
                inFlight.remove(key, promise);
            }
        } catch (RuntimeException failure) {
            observation.outcome(OperationMetrics.Outcome.FAILURE);
            throw failure;
        } finally {
            observation.close();
        }
    }

    public Report get(UUID workflowId) {
        return repository
                .findFirstByWorkflowIdOrderByGeneratedAtDescIdDesc(workflowId)
                .map(this::read)
                .orElseThrow(
                        () ->
                                new NoSuchElementException(
                                        "No stored workflow optimization for this workflow"));
    }

    private Report read(AiWorkflowOptimization row) {
        try {
            var facts = mapper.readValue(row.getFactsJson(), OptimizationFacts.class);
            if (facts == null || !row.getWorkflowId().equals(facts.workflowId()))
                throw new AiOutputException("Stored workflow optimization facts are invalid");
            var interpretation =
                    mapper.readValue(
                            row.getInterpretationJson(),
                            WorkflowOptimizationClient.Interpretation.class);
            OptimizationInterpretationValidator.validate(new WorkflowOptimizationClient.Result(interpretation, row.getModel()), facts);
            return new Report(
                    row.getId(),
                    row.getWorkflowId(),
                    facts,
                    interpretation,
                    row.getModel(),
                    row.getGeneratedAt(),
                    row.getOptimizationVersion(),
                    row.getContextHash());
        } catch (tools.jackson.core.JacksonException invalid) {
            throw new AiOutputException("Stored workflow optimization is invalid");
        }
    }

    private static Report await(CompletableFuture<Report> existing) {
        try {
            return existing.get(6, TimeUnit.MINUTES);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AiUnavailableException();
        } catch (TimeoutException timeout) {
            throw new AiUnavailableException();
        } catch (ExecutionException failure) {
            if (failure.getCause() instanceof RuntimeException runtime) throw runtime;
            throw new AiUnavailableException();
        }
    }

    private static String hash(String json) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(("optimization-v1:" + json).getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 unavailable", failure);
        }
    }

    public record Report(
            UUID optimizationId,
            UUID workflowId,
            OptimizationFacts facts,
            WorkflowOptimizationClient.Interpretation interpretation,
            String model,
            Instant generatedAt,
            int optimizationVersion,
            String contextHash) {}
}
