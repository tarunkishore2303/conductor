package com.tarunkishore.loom_api.ai;

import com.tarunkishore.loom_api.repository.AiFailureAnalysisRepository;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

@Service
public class FailureAnalysisService {
    private final FailureContextCollector collector;
    private final FailureAnalysisClient client;
    private final AiFailureAnalysisRepository repository;
    private final ObjectMapper mapper;
    private final ConcurrentHashMap<String, CompletableFuture<Analysis>> inFlight =
            new ConcurrentHashMap<>();

    public FailureAnalysisService(
            FailureContextCollector collector,
            FailureAnalysisClient client,
            AiFailureAnalysisRepository repository,
            ObjectMapper mapper) {
        this.collector = collector;
        this.client = client;
        this.repository = repository;
        this.mapper = mapper;
    }

    // No transaction encloses the model request; only context reads and saves use short DB
    // transactions.
    public Analysis analyze(UUID jobId) {
        var facts = collector.collect(jobId);
        String json = mapper.writeValueAsString(facts);
        if (json.length() > 32768)
            throw new AiOutputException("Failure evidence exceeds the bounded analysis context");
        String hash = hash(json);
        var cached = repository.findByJobIdAndContextHash(jobId, hash);
        if (cached.isPresent()) return response(cached.get());
        var promise = new CompletableFuture<Analysis>();
        var existing = inFlight.putIfAbsent(jobId + ":" + hash, promise);
        if (existing != null) {
            try {
                return existing.get(6, TimeUnit.MINUTES);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AiUnavailableException();
            } catch (TimeoutException e) {
                throw new AiUnavailableException();
            } catch (ExecutionException e) {
                if (e.getCause() instanceof RuntimeException r) throw r;
                throw new AiUnavailableException();
            }
        }
        try {
            // A prior leader may have committed after our initial cache lookup.
            var newlyCached = repository.findByJobIdAndContextHash(jobId, hash);
            if (newlyCached.isPresent()) {
                var analysis = response(newlyCached.get());
                promise.complete(analysis);
                return analysis;
            }
            var result = client.analyze(facts);
            validate(result);
            var row = new AiFailureAnalysis();
            row.setId(UUID.randomUUID());
            row.setJobId(jobId);
            row.setContextHash(hash);
            row.setFactsJson(json);
            row.setInterpretationJson(mapper.writeValueAsString(result.interpretation()));
            row.setModel(result.model());
            row.setGeneratedAt(Instant.now());
            row.setAnalysisVersion(1);
            Analysis analysis;
            try {
                analysis = response(repository.saveAndFlush(row));
            } catch (DataIntegrityViolationException e) {
                analysis =
                        repository
                                .findByJobIdAndContextHash(jobId, hash)
                                .map(this::response)
                                .orElseThrow(() -> e);
            }
            promise.complete(analysis);
            return analysis;
        } catch (RuntimeException e) {
            promise.completeExceptionally(e);
            throw e;
        } finally {
            inFlight.remove(jobId + ":" + hash, promise);
        }
    }

    public Analysis get(UUID jobId) {
        return repository
                .findFirstByJobIdOrderByGeneratedAtDesc(jobId)
                .map(this::response)
                .orElseThrow(
                        () ->
                                new NoSuchElementException(
                                        "No stored failure analysis for this run"));
    }

    private Analysis response(AiFailureAnalysis row) {
        try {
            return new Analysis(
                    row.getId(),
                    row.getJobId(),
                    mapper.readValue(row.getFactsJson(), FailureContext.class),
                    mapper.readValue(
                            row.getInterpretationJson(),
                            FailureAnalysisClient.Interpretation.class),
                    row.getModel(),
                    row.getGeneratedAt(),
                    row.getAnalysisVersion(),
                    row.getContextHash());
        } catch (tools.jackson.core.JacksonException exception) {
            throw new AiOutputException("Stored failure analysis is invalid");
        }
    }

    private static void validate(FailureAnalysisClient.Result result) {
        if (result == null || result.interpretation() == null)
            throw new AiOutputException("Empty failure interpretation");
        field(result.model(), 128);
        var i = result.interpretation();
        field(i.likelyCause(), 1000);
        field(i.explanation(), 4000);
        if (!Set.of("LOW", "MEDIUM", "HIGH").contains(i.confidence() == null ? "" : i.confidence()))
            throw new AiOutputException("Invalid interpretation confidence");
        if (i.recommendedActions() == null
                || i.recommendedActions().isEmpty()
                || i.recommendedActions().size() > 5)
            throw new AiOutputException("Interpretation requires 1 to 5 recommendations");
        i.recommendedActions().forEach(s -> field(s, 1000));
    }

    private static void field(String s, int max) {
        if (s == null || s.isBlank() || s.length() > max)
            throw new AiOutputException("Invalid structured failure interpretation");
    }

    private static String hash(String json) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(("v1:" + json).getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    public record Analysis(
            UUID analysisId,
            UUID jobId,
            FailureContext facts,
            FailureAnalysisClient.Interpretation interpretation,
            String model,
            Instant generatedAt,
            int analysisVersion,
            String contextHash) {}
}
