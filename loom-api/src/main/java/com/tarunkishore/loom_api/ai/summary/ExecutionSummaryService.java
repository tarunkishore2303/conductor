package com.tarunkishore.loom_api.ai.summary;

import com.tarunkishore.loom_api.ai.AiOutputException;
import com.tarunkishore.loom_api.ai.AiUnavailableException;
import com.tarunkishore.loom_api.repository.AiRunSummaryRepository;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Pattern;

@Service
public class ExecutionSummaryService {
    private static final Logger log = LoggerFactory.getLogger(ExecutionSummaryService.class);
    private static final Pattern UUID_TEXT =
            Pattern.compile(
                    "(?i)\\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\b");
    private final ExecutionSummaryContextService contexts;
    private final ExecutionSummaryClient client;
    private final AiRunSummaryRepository repository;
    private final ObjectMapper mapper;
    private final MeterRegistry metrics;
    private final ConcurrentHashMap<String, CompletableFuture<Summary>> inFlight =
            new ConcurrentHashMap<>();

    public ExecutionSummaryService(
            ExecutionSummaryContextService contexts,
            ExecutionSummaryClient client,
            AiRunSummaryRepository repository,
            ObjectMapper mapper,
            MeterRegistry metrics) {
        this.contexts = contexts;
        this.client = client;
        this.repository = repository;
        this.mapper = mapper;
        this.metrics = metrics;
    }

    // Model latency never holds a database transaction or execution-engine lock.
    public Summary generate(UUID jobId) {
        var timer = Timer.start(metrics);
        var correlation = UUID.randomUUID();
        String outcome = "success";
        metrics.counter("ai.summary.requests").increment();
        try {
            var facts = contexts.collect(jobId);
            if (facts == null || !jobId.equals(facts.jobId()))
                throw new AiOutputException("Invalid execution summary facts");
            String json = mapper.writeValueAsString(facts);
            if (json.length() > 24000)
                throw new AiOutputException("Summary context exceeds the allowed size");
            String hash = hash(json);
            var cached = repository.findByJobIdAndContextHash(jobId, hash);
            if (cached.isPresent()) {
                outcome = "cached";
                return read(cached.get());
            }
            String key = jobId + ":" + hash;
            var promise = new CompletableFuture<Summary>();
            var existing = inFlight.putIfAbsent(key, promise);
            if (existing != null) {
                outcome = "coalesced";
                return await(existing);
            }
            try {
                var lateCache = repository.findByJobIdAndContextHash(jobId, hash);
                if (lateCache.isPresent()) {
                    var summary = read(lateCache.get());
                    promise.complete(summary);
                    outcome = "cached";
                    return summary;
                }
                var result = client.summarize(facts);
                validate(result, facts);
                var row = new AiRunSummary();
                row.setId(UUID.randomUUID());
                row.setJobId(jobId);
                row.setContextHash(hash);
                row.setFactsJson(json);
                row.setInterpretationJson(mapper.writeValueAsString(result.interpretation()));
                row.setModel(result.model());
                // PostgreSQL timestamptz stores microseconds; initial and cached responses must agree.
                row.setGeneratedAt(Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS));
                row.setSummaryVersion(1);
                Summary summary;
                try {
                    summary = read(repository.saveAndFlush(row));
                } catch (DataIntegrityViolationException conflict) {
                    summary =
                            repository
                                    .findByJobIdAndContextHash(jobId, hash)
                                    .map(this::read)
                                    .orElseThrow(() -> conflict);
                }
                promise.complete(summary);
                return summary;
            } catch (RuntimeException failure) {
                promise.completeExceptionally(failure);
                throw failure;
            } finally {
                inFlight.remove(key, promise);
            }
        } catch (RuntimeException failure) {
            outcome = "failure";
            metrics.counter("ai.summary.failures").increment();
            throw failure;
        } finally {
            long nanos = timer.stop(metrics.timer("ai.summary.duration", "outcome", outcome));
            log.info(
                    "AI operation=execution_summary correlationId={} outcome={} durationMs={}",
                    correlation,
                    outcome,
                    nanos / 1_000_000);
        }
    }

    public Summary get(UUID jobId) {
        return repository
                .findFirstByJobIdOrderByGeneratedAtDesc(jobId)
                .map(this::read)
                .orElseThrow(
                        () ->
                                new NoSuchElementException(
                                        "No stored execution summary for this run"));
    }

    private Summary read(AiRunSummary row) {
        try {
            var facts = mapper.readValue(row.getFactsJson(), ExecutionSummaryFacts.class);
            if (facts == null || !row.getJobId().equals(facts.jobId()))
                throw new AiOutputException("Stored execution summary facts are invalid");
            var interpretation =
                    mapper.readValue(
                            row.getInterpretationJson(),
                            ExecutionSummaryClient.Interpretation.class);
            validate(new ExecutionSummaryClient.Result(interpretation, row.getModel()), facts);
            return new Summary(
                    row.getId(),
                    row.getJobId(),
                    facts,
                    interpretation,
                    row.getModel(),
                    row.getGeneratedAt(),
                    row.getSummaryVersion(),
                    row.getContextHash());
        } catch (tools.jackson.core.JacksonException invalid) {
            throw new AiOutputException("Stored execution summary is invalid");
        }
    }

    private static Summary await(CompletableFuture<Summary> existing) {
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

    private static void validate(
            ExecutionSummaryClient.Result result, ExecutionSummaryFacts facts) {
        if (result == null || result.interpretation() == null)
            throw new AiOutputException("Empty execution summary interpretation");
        field(result.model(), 128);
        field(result.interpretation().overview(), 2000);
        var allowed = new HashSet<UUID>();
        allowed.add(facts.jobId());
        if (facts.failureAnalysisId() != null) allowed.add(facts.failureAnalysisId());
        if (facts.slowestObservedTasks() == null)
            throw new AiOutputException("Invalid summary task evidence");
        facts.slowestObservedTasks().forEach(task -> allowed.add(task.taskId()));
        references(result.interpretation().overview(), allowed);
        for (var list :
                List.of(
                        result.interpretation().notableEvents() == null
                                ? List.<String>of()
                                : result.interpretation().notableEvents(),
                        result.interpretation().operationalNotes() == null
                                ? List.<String>of()
                                : result.interpretation().operationalNotes())) {
            if (list.size() > 5)
                throw new AiOutputException("Execution summary lists exceed the allowed size");
            for (var text : list) {
                field(text, 1000);
                references(text, allowed);
            }
        }
        if (result.interpretation().notableEvents() == null
                || result.interpretation().operationalNotes() == null)
            throw new AiOutputException("Execution summary lists must be present");
    }

    private static void field(String value, int max) {
        if (value == null || value.isBlank() || value.length() > max)
            throw new AiOutputException("Invalid structured execution summary");
    }

    private static void references(String text, Set<UUID> allowed) {
        var matcher = UUID_TEXT.matcher(text);
        while (matcher.find())
            if (!allowed.contains(UUID.fromString(matcher.group())))
                throw new AiOutputException("Execution summary references unsupported evidence");
    }

    private static String hash(String json) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(("v1:" + json).getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 unavailable", failure);
        }
    }

    public record Summary(
            UUID summaryId,
            UUID jobId,
            ExecutionSummaryFacts facts,
            ExecutionSummaryClient.Interpretation interpretation,
            String model,
            Instant generatedAt,
            int summaryVersion,
            String contextHash) {}
}
