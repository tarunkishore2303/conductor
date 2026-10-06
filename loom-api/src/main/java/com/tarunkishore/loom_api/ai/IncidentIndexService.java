package com.tarunkishore.loom_api.ai;

import com.tarunkishore.loom_api.repository.*;

import jakarta.annotation.PreDestroy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import tools.jackson.databind.ObjectMapper;

import java.util.*;
import java.util.concurrent.*;

@Service
public class IncidentIndexService {
    private static final Logger log = LoggerFactory.getLogger(IncidentIndexService.class);
    private final AiFailureAnalysisRepository analyses;
    private final IncidentVectorRepository vectors;
    private final IncidentAiClient client;
    private final IncidentDocumentBuilder documents;
    private final ObjectMapper mapper;
    private final String model;
    private final boolean autoIndexEnabled;
    private final ThreadPoolExecutor executor =
            new ThreadPoolExecutor(
                    1,
                    1,
                    0,
                    TimeUnit.SECONDS,
                    new ArrayBlockingQueue<>(8),
                    Thread.ofPlatform().daemon(true).name("incident-index-", 0).factory(),
                    new ThreadPoolExecutor.AbortPolicy());
    private final ConcurrentHashMap<UUID, CompletableFuture<IndexResult>> inFlight =
            new ConcurrentHashMap<>();

    public IncidentIndexService(
            AiFailureAnalysisRepository analyses,
            IncidentVectorRepository vectors,
            IncidentAiClient client,
            IncidentDocumentBuilder documents,
            ObjectMapper mapper,
            @Value("${conductor.ai.embedding-model:nomic-embed-text:v1.5}") String model,
            @Value("${conductor.ai.incident-auto-index-enabled:false}") boolean autoIndexEnabled) {
        if (model == null || model.isBlank() || model.length() > 128)
            throw new IllegalArgumentException("Invalid embedding model");
        this.analyses = analyses;
        this.vectors = vectors;
        this.client = client;
        this.documents = documents;
        this.mapper = mapper;
        this.model = model;
        this.autoIndexEnabled = autoIndexEnabled;
    }

    @EventListener
    public void onAnalysisStored(FailureAnalysisStored event) {
        if (!autoIndexEnabled) return;
        try {
            executor.execute(
                    () -> {
                        try {
                            index(event.analysisId());
                        } catch (RuntimeException e) {
                            log.warn(
                                    "Incident indexing failed analysisId={} errorType={}; explicit"
                                            + " retry is available",
                                    event.analysisId(),
                                    e.getClass().getSimpleName());
                        }
                    });
        } catch (RejectedExecutionException e) {
            log.warn(
                    "Incident indexing queue full analysisId={}; explicit retry is available",
                    event.analysisId());
        }
    }

    public IndexResult index(UUID analysisId) {
        var promise = new CompletableFuture<IndexResult>();
        var existing = inFlight.putIfAbsent(analysisId, promise);
        if (existing != null) {
            try {
                return existing.get(6, TimeUnit.MINUTES);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AiUnavailableException();
            } catch (ExecutionException e) {
                if (e.getCause() instanceof RuntimeException r) throw r;
                throw new AiUnavailableException();
            } catch (TimeoutException e) {
                throw new AiUnavailableException();
            }
        }
        try {
            var analysis =
                    analyses.findById(analysisId)
                            .orElseThrow(() -> new NoSuchElementException("Analysis not found"));
            var context = mapper.readValue(analysis.getFactsJson(), FailureContext.class);
            var found = vectors.findTaskEmbeddings(analysisId, model);
            var indexed = new HashSet<UUID>();
            found.forEach(v -> indexed.add(v.taskId()));
            int inserted = 0;
            var built = documents.build(context);
            // At most five embedding calls per indexing request; bound total provider work.
            if (built.size() > 5)
                throw new AiOutputException(
                        "Incident indexing supports at most five failed tasks per analysis");
            for (var document : built) {
                if (indexed.contains(document.taskId())) continue;
                var embedding = client.embed(document.documentText());
                validate(embedding);
                if (vectors.store(
                        analysisId,
                        analysis.getJobId(),
                        document.taskId(),
                        model,
                        document.documentText(),
                        embedding.embedding())) inserted++;
            }
            var result = new IndexResult(analysisId, model, built.size(), inserted);
            promise.complete(result);
            return result;
        } catch (tools.jackson.core.JacksonException e) {
            var invalid = new AiOutputException("Stored incident evidence is invalid");
            promise.completeExceptionally(invalid);
            throw invalid;
        } catch (RuntimeException e) {
            promise.completeExceptionally(e);
            throw e;
        } finally {
            inFlight.remove(analysisId, promise);
        }
    }

    private void validate(IncidentAiClient.Embedding result) {
        if (result == null
                || !model.equals(result.model())
                || result.embedding() == null
                || result.embedding().length != 768)
            throw new AiOutputException("Invalid embedding model or dimensions");
        double norm = 0;
        for (float value : result.embedding()) {
            if (!Float.isFinite(value))
                throw new AiOutputException("Embedding contains non-finite values");
            norm += (double) value * value;
        }
        if (norm == 0) throw new AiOutputException("Embedding vector must not be zero");
    }

    @PreDestroy
    public void close() {
        executor.shutdownNow();
    }

    public record IndexResult(
            UUID analysisId, String embeddingModel, int taskCount, int insertedCount) {}
}
