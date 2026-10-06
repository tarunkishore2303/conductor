package com.tarunkishore.loom_api.ai;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.tarunkishore.loom_api.repository.*;

import org.junit.jupiter.api.*;

import tools.jackson.databind.json.JsonMapper;

import java.util.*;

class IncidentIndexServiceTest {
    @Test
    void automaticIndexingDisabledByDefaultDoesNotInvokeProvider() {
        service.onAnalysisStored(new FailureAnalysisStored(id));
        verifyNoInteractions(client, analyses, vectors);
    }

    final AiFailureAnalysisRepository analyses = mock(AiFailureAnalysisRepository.class);
    final IncidentVectorRepository vectors = mock(IncidentVectorRepository.class);
    final IncidentAiClient client = mock(IncidentAiClient.class);
    final UUID id = UUID.randomUUID(), job = UUID.randomUUID(), task = UUID.randomUUID();
    final String model = "nomic-embed-text:v1.5";
    final IncidentIndexService service =
            new IncidentIndexService(
                    analyses,
                    vectors,
                    client,
                    new IncidentDocumentBuilder(),
                    JsonMapper.builder().build(),
                    model,
                    false);

    @BeforeEach
    void setup() {
        var analysis = new AiFailureAnalysis();
        analysis.setId(id);
        analysis.setJobId(job);
        var facts =
                new FailureContext(
                        job,
                        "FAILED",
                        List.of(
                                new FailureContext.TaskEvidence(
                                        task,
                                        "timeout",
                                        "DEAD_LETTERED",
                                        2,
                                        List.of(
                                                new FailureContext.Attempt(
                                                        2,
                                                        "FAILED",
                                                        null,
                                                        null,
                                                        "Timeout",
                                                        "dependency timeout",
                                                        null)),
                                        null)),
                        List.of());
        analysis.setFactsJson(JsonMapper.builder().build().writeValueAsString(facts));
        when(analyses.findById(id)).thenReturn(Optional.of(analysis));
        when(vectors.findTaskEmbeddings(id, model)).thenReturn(List.of());
        var embedding = new float[768];
        embedding[0] = 1;
        when(client.embed(anyString()))
                .thenReturn(new IncidentAiClient.Embedding(embedding, model));
        when(vectors.store(eq(id), eq(job), eq(task), eq(model), anyString(), any()))
                .thenReturn(true);
    }

    @AfterEach
    void shutdown() {
        service.close();
    }

    @Test
    void storedVectorReusedWithoutModelCall() {
        when(vectors.findTaskEmbeddings(id, model))
                .thenReturn(
                        List.of(
                                new IncidentVectorRepository.VectorRecord(
                                        UUID.randomUUID(),
                                        id,
                                        job,
                                        task,
                                        "existing",
                                        new float[768])));
        assertThat(service.index(id).insertedCount()).isZero();
        verifyNoInteractions(client);
    }

    @Test
    void newIncidentUsesActualFactsAndRejectsWrongModelOrDimension() {
        assertThat(service.index(id).insertedCount()).isEqualTo(1);
        verify(client).embed(contains("dependency timeout"));
        when(client.embed(anyString()))
                .thenReturn(new IncidentAiClient.Embedding(new float[10], model));
        assertThatThrownBy(() -> service.index(id)).isInstanceOf(AiOutputException.class);
        when(client.embed(anyString()))
                .thenReturn(new IncidentAiClient.Embedding(new float[768], "different"));
        assertThatThrownBy(() -> service.index(id)).isInstanceOf(AiOutputException.class);
        verify(vectors, times(1)).store(any(), any(), any(), anyString(), anyString(), any());
    }

    @Test
    void failedEmbeddingAllowsExplicitRetry() {
        when(client.embed(anyString())).thenThrow(new AiUnavailableException());
        assertThatThrownBy(() -> service.index(id)).isInstanceOf(AiUnavailableException.class);
        verify(vectors, never()).store(any(), any(), any(), anyString(), anyString(), any());
    }

    @Test
    void malformedStoredJsonMapsTo422Domain() {
        var analysis = new AiFailureAnalysis();
        analysis.setFactsJson("not json");
        when(analyses.findById(id)).thenReturn(Optional.of(analysis));
        assertThatThrownBy(() -> service.index(id)).isInstanceOf(AiOutputException.class);
    }
}
