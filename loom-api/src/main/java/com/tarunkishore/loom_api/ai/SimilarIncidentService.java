package com.tarunkishore.loom_api.ai;

import com.tarunkishore.loom_api.ai.validation.IncidentSynthesisValidator;
import com.tarunkishore.loom_api.exception.AiOutputException;

import com.tarunkishore.loom_api.repository.*;

import io.micrometer.core.instrument.MeterRegistry;
import com.loom.observability.OperationMetrics;

import com.tarunkishore.loom_api.config.IncidentProperties;
import org.springframework.stereotype.Service;

import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.*;

@Service
public class SimilarIncidentService {
    private final AiFailureAnalysisRepository analyses;
    private final IncidentVectorRepository vectors;
    private final IncidentAiClient client;
    private final IncidentDocumentBuilder documents;
    private final ObjectMapper mapper;
    private final String model;
    private final double threshold;
    private final String chatModel;
    private final MeterRegistry metrics;
    private final Map<String, IncidentAiClient.SynthesisResult> synthesisCache =
            Collections.synchronizedMap(
                    new LinkedHashMap<>(100, 0.75f, true) {
                        @Override
                        protected boolean removeEldestEntry(
                                Map.Entry<String, IncidentAiClient.SynthesisResult> entry) {
                            return size() > 100;
                        }
                    });

    public SimilarIncidentService(
            AiFailureAnalysisRepository analyses,
            IncidentVectorRepository vectors,
            IncidentAiClient client,
            IncidentDocumentBuilder documents,
            ObjectMapper mapper,
            IncidentProperties properties,
            MeterRegistry metrics) {
        this.analyses = analyses;
        this.vectors = vectors;
        this.client = client;
        this.documents = documents;
        this.mapper = mapper;
        this.model = properties.embeddingModel();
        this.threshold = properties.incidentSimilarityThreshold();
        this.chatModel = properties.chatModel();
        this.metrics = metrics;
    }

    public Retrieval retrieve(UUID jobId, int topK) {
        var observation = OperationMetrics.start(metrics, OperationMetrics.Operation.INCIDENT_SEARCH);
        try {
            return doRetrieve(jobId, topK);
        } catch (RuntimeException exception) {
            observation.outcome(OperationMetrics.Outcome.FAILURE);
            throw exception;
        } finally {
            observation.close();
        }
    }

    private Retrieval doRetrieve(UUID jobId, int topK) {
        if (topK < 1 || topK > 5)
            throw new IllegalArgumentException("topK must be between 1 and 5");
        var current =
                analyses.findFirstByJobIdOrderByGeneratedAtDesc(jobId)
                        .orElseThrow(
                                () ->
                                        new NoSuchElementException(
                                                "No stored failure analysis for this run"));
        var currentDocuments = documents.build(readFacts(current));
        if (currentDocuments.size() > 5)
            throw new AiOutputException(
                    "Incident retrieval supports at most five failed tasks per analysis");
        var queryVectors = vectors.findTaskEmbeddings(current.getId(), model);
        var expectedTasks = new HashSet<UUID>();
        currentDocuments.forEach(d -> expectedTasks.add(d.taskId()));
        var indexedTasks = new HashSet<UUID>();
        queryVectors.forEach(v -> indexedTasks.add(v.taskId()));
        if (queryVectors.size() != currentDocuments.size() || !expectedTasks.equals(indexedTasks))
            throw new IllegalStateException(
                    "Analysis is not fully indexed for the configured model; explicitly index"
                            + " analysis "
                            + current.getId());
        var deduplicated = new HashMap<UUID, IncidentVectorRepository.Match>();
        for (var query : queryVectors) {
            for (var match : vectors.search(jobId, model, query.embedding(), topK, threshold)) {
                if (match.jobId().equals(jobId)) continue;
                deduplicated.merge(
                        match.id(), match, (a, b) -> a.similarity() >= b.similarity() ? a : b);
            }
        }
        var matches =
                deduplicated.values().stream()
                        .sorted(
                                Comparator.comparingDouble(
                                                IncidentVectorRepository.Match::similarity)
                                        .reversed()
                                        .thenComparing(IncidentVectorRepository.Match::id))
                        .limit(topK)
                        .map(this::reference)
                        .toList();
        String document = boundedCurrentContext(currentDocuments);
        return new Retrieval(
                current.getId(),
                jobId,
                model,
                threshold,
                document.substring(0, Math.min(document.length(), 4000)),
                matches,
                matches.isEmpty()
                        ? "No historical incidents meet the configured similarity threshold"
                        : "Retrieved historical incident evidence",
                List.of(
                        "Workflow names are unavailable because jobs do not retain template"
                                + " linkage."));
    }

    public SynthesisResponse synthesize(UUID jobId, int topK) {
        var retrieved = retrieve(jobId, topK);
        if (retrieved.matches().isEmpty())
            return new SynthesisResponse(
                    retrieved,
                    new IncidentAiClient.Synthesis(
                            "No matching historical incidents were retrieved.", List.of()),
                    null);
        var evidence =
                retrieved.matches().stream()
                        .map(
                                m ->
                                        new IncidentAiClient.EvidenceMatch(
                                                m.incidentId(),
                                                m.jobId(),
                                                m.taskId(),
                                                m.documentText(),
                                                m.similarity()))
                        .toList();
        var request =
                new IncidentAiClient.SynthesisRequest(
                        retrieved.currentAnalysisId(), retrieved.currentIncident(), evidence);
        String key = cacheKey(request);
        var response = synthesisCache.get(key);
        if (response == null) {
            response = client.synthesize(request);
            IncidentSynthesisValidator.validate(response, evidence);
            synthesisCache.put(key, response);
        }
        IncidentSynthesisValidator.validate(response, evidence);
        return new SynthesisResponse(retrieved, response.synthesis(), response.model());
    }

    static String boundedCurrentContext(List<IncidentDocumentBuilder.Document> documents) {
        int perTask = (4000 - 2 * (documents.size() - 1)) / documents.size();
        return String.join(
                "\n\n",
                documents.stream()
                        .map(
                                document ->
                                        document.documentText()
                                                .substring(
                                                        0,
                                                        Math.min(
                                                                perTask,
                                                                document.documentText().length())))
                        .toList());
    }

    private String cacheKey(IncidentAiClient.SynthesisRequest request) {
        try {
            byte[] bytes =
                    (model + ":" + chatModel + ":" + mapper.writeValueAsString(request))
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8);
            return HexFormat.of()
                    .formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private Match reference(IncidentVectorRepository.Match match) {
        var analysis =
                analyses.findById(match.analysisId())
                        .orElseThrow(
                                () ->
                                        new AiOutputException(
                                                "Historical analysis reference unavailable"));
        var facts = readFacts(analysis);
        documents.build(facts);
        var task =
                facts.tasks().stream()
                        .filter(t -> t.taskId().equals(match.taskId()))
                        .findFirst()
                        .orElseThrow(
                                () ->
                                        new AiOutputException(
                                                "Historical task reference unavailable"));
        var failureTimestamp =
                task.attempts().stream()
                        .filter(a -> a.errorType() != null || a.errorMessage() != null)
                        .map(FailureContext.Attempt::completedAt)
                        .filter(Objects::nonNull)
                        .max(Comparator.naturalOrder())
                        .orElse(null);
        return new Match(
                match.id(),
                match.analysisId(),
                match.jobId(),
                match.taskId(),
                task.name(),
                failureTimestamp,
                analysis.getGeneratedAt(),
                match.document(),
                match.similarity(),
                "/api/v1/jobs/" + match.jobId(),
                "/api/v1/ai/failure-analyses/" + match.analysisId());
    }

    private FailureContext readFacts(AiFailureAnalysis analysis) {
        try {
            return mapper.readValue(analysis.getFactsJson(), FailureContext.class);
        } catch (tools.jackson.core.JacksonException e) {
            throw new AiOutputException("Stored incident evidence is invalid");
        }
    }

    public record Match(
            UUID incidentId,
            UUID analysisId,
            UUID jobId,
            UUID taskId,
            String taskName,
            Instant failureTimestamp,
            Instant generatedAt,
            String documentText,
            double similarity,
            String runUrl,
            String analysisUrl) {}

    public record Retrieval(
            UUID currentAnalysisId,
            UUID jobId,
            String embeddingModel,
            double threshold,
            String currentIncident,
            List<Match> matches,
            String message,
            List<String> unavailableEvidence) {}

    public record SynthesisResponse(
            Retrieval retrieval, IncidentAiClient.Synthesis synthesis, String model) {}
}
