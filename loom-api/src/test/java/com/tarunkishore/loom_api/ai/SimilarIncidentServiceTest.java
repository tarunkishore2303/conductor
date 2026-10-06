package com.tarunkishore.loom_api.ai;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.tarunkishore.loom_api.repository.*;

import org.junit.jupiter.api.*;

import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.*;

class SimilarIncidentServiceTest {
    @Test
    void boundedCurrentContextIncludesEveryTask() {
        var docs =
                List.of(
                        new IncidentDocumentBuilder.Document(
                                UUID.randomUUID(), "Task: first\n" + "a".repeat(3990)),
                        new IncidentDocumentBuilder.Document(
                                UUID.randomUUID(), "Task: second\n" + "b".repeat(3990)),
                        new IncidentDocumentBuilder.Document(
                                UUID.randomUUID(), "Task: third\n" + "c".repeat(3990)));
        assertThat(SimilarIncidentService.boundedCurrentContext(docs))
                .hasSizeLessThanOrEqualTo(4000)
                .contains("Task: first", "Task: second", "Task: third");
    }

    @Test
    void repeatedValidatedSynthesisUsesCacheEvenWhenProviderFails() {
        match();
        when(client.synthesize(any()))
                .thenReturn(
                        new IncidentAiClient.SynthesisResult(
                                new IncidentAiClient.Synthesis(
                                        "Cached evidence explanation", List.of(incident)),
                                "fake"));
        var first = service.synthesize(job, 3);
        when(client.synthesize(any())).thenThrow(new AiUnavailableException());
        assertThat(service.synthesize(job, 3).synthesis()).isEqualTo(first.synthesis());
        verify(client, times(1)).synthesize(any());
    }

    @Test
    void boundedCacheEvictsOldestExplanation() {
        when(client.synthesize(any()))
                .thenAnswer(
                        invocation -> {
                            var request =
                                    (IncidentAiClient.SynthesisRequest) invocation.getArgument(0);
                            return new IncidentAiClient.SynthesisResult(
                                    new IncidentAiClient.Synthesis(
                                            "Retrieved evidence",
                                            List.of(request.matches().getFirst().incidentId())),
                                    "fake");
                        });
        match();
        service.synthesize(job, 3);
        for (int i = 0; i < 100; i++) {
            when(vectors.search(eq(job), eq(model), any(), eq(3), eq(0.93)))
                    .thenReturn(
                            List.of(
                                    new IncidentVectorRepository.Match(
                                            UUID.randomUUID(),
                                            oldAnalysis,
                                            oldJob,
                                            oldTask,
                                            "historical evidence",
                                            0.97)));
            service.synthesize(job, 3);
        }
        match();
        service.synthesize(job, 3);
        verify(client, times(102)).synthesize(any());
    }

    final AiFailureAnalysisRepository analyses = mock(AiFailureAnalysisRepository.class);
    final IncidentVectorRepository vectors = mock(IncidentVectorRepository.class);
    final IncidentAiClient client = mock(IncidentAiClient.class);
    final UUID current = UUID.randomUUID(), job = UUID.randomUUID(), task = UUID.randomUUID();
    final UUID oldAnalysis = UUID.randomUUID(),
            oldJob = UUID.randomUUID(),
            oldTask = UUID.randomUUID(),
            incident = UUID.randomUUID();
    final String model = "nomic-embed-text:v1.5";
    final SimilarIncidentService service =
            new SimilarIncidentService(
                    analyses,
                    vectors,
                    client,
                    new IncidentDocumentBuilder(),
                    JsonMapper.builder().build(),
                    model,
                    0.93,
                    "fake",
                    new io.micrometer.core.instrument.simple.SimpleMeterRegistry());

    AiFailureAnalysis analysis(UUID id, UUID jobId, UUID taskId) {
        var row = new AiFailureAnalysis();
        row.setId(id);
        row.setJobId(jobId);
        row.setGeneratedAt(Instant.now());
        row.setFactsJson(
                JsonMapper.builder()
                        .build()
                        .writeValueAsString(
                                new FailureContext(
                                        jobId,
                                        "FAILED",
                                        List.of(
                                                new FailureContext.TaskEvidence(
                                                        taskId,
                                                        "timeout",
                                                        "DEAD_LETTERED",
                                                        2,
                                                        List.of(),
                                                        null)),
                                        List.of())));
        return row;
    }

    @BeforeEach
    void setup() {
        when(analyses.findFirstByJobIdOrderByGeneratedAtDesc(job))
                .thenReturn(Optional.of(analysis(current, job, task)));
        when(analyses.findById(oldAnalysis))
                .thenReturn(Optional.of(analysis(oldAnalysis, oldJob, oldTask)));
        var embedding = new float[768];
        embedding[0] = 1;
        when(vectors.findTaskEmbeddings(current, model))
                .thenReturn(
                        List.of(
                                new IncidentVectorRepository.VectorRecord(
                                        UUID.randomUUID(),
                                        current,
                                        job,
                                        task,
                                        "current evidence",
                                        embedding)));
        when(vectors.search(eq(job), eq(model), any(), eq(3), eq(0.93))).thenReturn(List.of());
    }

    void match() {
        when(vectors.search(eq(job), eq(model), any(), eq(3), eq(0.93)))
                .thenReturn(
                        List.of(
                                new IncidentVectorRepository.Match(
                                        incident,
                                        oldAnalysis,
                                        oldJob,
                                        oldTask,
                                        "historical evidence",
                                        0.97)));
    }

    @Test
    void noMatchesDoesNotCallModel() {
        assertThat(service.synthesize(job, 3).synthesis().explanation()).contains("No matching");
        verifyNoInteractions(client);
    }

    @Test
    void retrievalUsesActualReferencesAndDoesNotCallModel() {
        match();
        var result = service.retrieve(job, 3);
        assertThat(result.matches()).hasSize(1);
        assertThat(result.matches().getFirst().jobId()).isEqualTo(oldJob);
        assertThat(result.matches().getFirst().analysisUrl()).endsWith(oldAnalysis.toString());
        assertThat(result.matches().getFirst().taskName()).isEqualTo("timeout");
        verifyNoInteractions(client);
    }

    @Test
    void modelCannotCiteUnretrievedIncidents() {
        match();
        when(client.synthesize(any()))
                .thenReturn(
                        new IncidentAiClient.SynthesisResult(
                                new IncidentAiClient.Synthesis(
                                        "An invented citation", List.of(UUID.randomUUID())),
                                "fake"));
        assertThatThrownBy(() -> service.synthesize(job, 3))
                .isInstanceOf(AiOutputException.class)
                .hasMessageContaining("unretrieved");
        when(client.synthesize(any()))
                .thenReturn(
                        new IncidentAiClient.SynthesisResult(
                                new IncidentAiClient.Synthesis(
                                        "Related retrieved incident", List.of(incident)),
                                "fake"));
        assertThat(service.synthesize(job, 3).synthesis().citedIncidentIds())
                .containsExactly(incident);
    }

    @Test
    void unindexedAndInvalidLimitsRejected() {
        when(vectors.findTaskEmbeddings(current, model)).thenReturn(List.of());
        assertThatThrownBy(() -> service.retrieve(job, 3))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("index");
        assertThatThrownBy(() -> service.retrieve(job, 6))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void malformedStoredContextIsSafeDomainError() {
        var row = analysis(current, job, task);
        row.setFactsJson("{broken");
        when(analyses.findFirstByJobIdOrderByGeneratedAtDesc(job)).thenReturn(Optional.of(row));
        assertThatThrownBy(() -> service.retrieve(job, 3)).isInstanceOf(AiOutputException.class);
    }

    @Test
    void excludesCurrentRunEvenIfRepositoryReturnsIt() {
        when(vectors.search(eq(job), eq(model), any(), eq(3), eq(0.93)))
                .thenReturn(
                        List.of(
                                new IncidentVectorRepository.Match(
                                        incident, current, job, task, "self", 1.0)));
        assertThat(service.retrieve(job, 3).matches()).isEmpty();
    }
}
