package com.tarunkishore.loom_api.ai.optimization.application;

import com.loom.observability.OperationMetrics;
import com.tarunkishore.loom_api.ai.optimization.client.WorkflowOptimizationClient;
import com.tarunkishore.loom_api.ai.optimization.domain.OptimizationFacts;
import com.tarunkishore.loom_api.ai.optimization.domain.OptimizationOutcome;
import com.tarunkishore.loom_api.ai.optimization.domain.OptimizationReport;
import com.tarunkishore.loom_api.ai.optimization.persistence.OptimizationReportStore;
import com.tarunkishore.loom_api.exception.AiOutputException;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Application flow only: collect, analyze, reuse, interpret and persist an advisory snapshot. */
@Service
public class WorkflowOptimizationService {
    private final OptimizationContextService contexts;
    private final OptimizationAnalyzerRegistry analyzers;
    private final WorkflowOptimizationClient client;
    private final OptimizationReportStore reports;
    private final OptimizationRequestCoalescer coalescer;
    private final MeterRegistry metrics;

    public WorkflowOptimizationService(OptimizationContextService contexts, OptimizationAnalyzerRegistry analyzers,
            WorkflowOptimizationClient client, OptimizationReportStore reports,
            OptimizationRequestCoalescer coalescer, MeterRegistry metrics) {
        this.contexts = contexts;
        this.analyzers = analyzers;
        this.client = client;
        this.reports = reports;
        this.coalescer = coalescer;
        this.metrics = metrics;
    }

    // Model latency never holds a database transaction or execution-engine lock.
    public OptimizationReport generate(UUID workflowId) {
        var observation = OperationMetrics.start(metrics, OperationMetrics.Operation.OPTIMIZATION);
        try {
            var facts = analyzers.analyze(contexts.collect(workflowId));
            if (facts == null || !workflowId.equals(facts.workflowId()))
                throw new AiOutputException("Invalid workflow optimization facts");
            var prepared = reports.prepare(facts);
            var cached = reports.find(workflowId, prepared.hash());
            if (cached.isPresent()) {
                observation.outcome(OperationMetrics.Outcome.CACHED);
                return cached.get();
            }
            return coalescer.execute(workflowId + ":" + prepared.hash(), () -> {
                var lateCache = reports.find(workflowId, prepared.hash());
                if (lateCache.isPresent()) {
                    observation.outcome(OperationMetrics.Outcome.CACHED);
                    return lateCache.get();
                }
                var interpretation = facts.candidates().isEmpty() ? deterministic(facts) : client.interpret(facts);
                return reports.save(facts, prepared, interpretation);
            }, () -> observation.outcome(OperationMetrics.Outcome.COALESCED));
        } catch (RuntimeException failure) {
            observation.outcome(OperationMetrics.Outcome.FAILURE);
            throw failure;
        } finally {
            observation.close();
        }
    }

    public OptimizationReport get(UUID workflowId) {
        return reports.latest(workflowId);
    }

    private static WorkflowOptimizationClient.Result deterministic(OptimizationFacts facts) {
        String overview = facts.outcome() == OptimizationOutcome.INSUFFICIENT_HISTORY
                ? "Insufficient settled linked execution history: " + facts.runsConsidered()
                    + " runs; minimum sample size is " + facts.minimumSampleSize() + "."
                : "No optimization candidates met the configured evidence thresholds.";
        return new WorkflowOptimizationClient.Result(
                new WorkflowOptimizationClient.Interpretation(overview, List.of()), "deterministic");
    }

}
