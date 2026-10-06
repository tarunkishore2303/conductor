package com.tarunkishore.loom_api.ai;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.tarunkishore.loom_api.repository.AiFailureAnalysisRepository;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

class FailureAnalysisServiceTest {
    final FailureContextCollector collector = mock(FailureContextCollector.class);
    final FailureAnalysisClient client = mock(FailureAnalysisClient.class);
    final AiFailureAnalysisRepository repo = mock(AiFailureAnalysisRepository.class);
    final FailureAnalysisService service =
            new FailureAnalysisService(collector, client, repo, JsonMapper.builder().build(), event -> {});
    final UUID job = UUID.randomUUID();
    final FailureContext facts =
            new FailureContext(job, "FAILED", List.of(), List.of("Logs unavailable"));
    final FailureAnalysisClient.Result result =
            new FailureAnalysisClient.Result(
                    new FailureAnalysisClient.Interpretation(
                            "Simulated failure",
                            "HIGH",
                            "Recorded demo error",
                            List.of("Review the handler")),
                    "fake");

    void setup() {
        when(collector.collect(job)).thenReturn(facts);
        when(repo.findByJobIdAndContextHash(eq(job), anyString())).thenReturn(Optional.empty());
        when(client.analyze(facts)).thenReturn(result);
        when(repo.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));
    }

    @Test
    void separatesFactsAndInterpretationAndCachesExactSnapshot() {
        setup();
        var analysis = service.analyze(job);
        assertThat(analysis.facts()).isEqualTo(facts);
        assertThat(analysis.interpretation().likelyCause()).isEqualTo("Simulated failure");
        var saved = org.mockito.ArgumentCaptor.forClass(AiFailureAnalysis.class);
        verify(repo).saveAndFlush(saved.capture());
        when(repo.findByJobIdAndContextHash(eq(job), anyString()))
                .thenReturn(Optional.of(saved.getValue()));
        assertThat(service.analyze(job).analysisId()).isEqualTo(analysis.analysisId());
        verify(client, times(1)).analyze(any());
    }

    @Test
    void malformedAndUnavailableResponsesNeverPersist() {
        setup();
        when(client.analyze(any())).thenReturn(new FailureAnalysisClient.Result(null, "fake"));
        assertThatThrownBy(() -> service.analyze(job)).isInstanceOf(AiOutputException.class);
        when(client.analyze(any())).thenThrow(new AiUnavailableException());
        assertThatThrownBy(() -> service.analyze(job)).isInstanceOf(AiUnavailableException.class);
        verify(repo, never()).saveAndFlush(any());
    }

    @Test
    void storedGetDoesNotRequireCollectorOrModel() {
        var row = new AiFailureAnalysis();
        row.setId(UUID.randomUUID());
        row.setJobId(job);
        row.setFactsJson(JsonMapper.builder().build().writeValueAsString(facts));
        row.setInterpretationJson(
                JsonMapper.builder().build().writeValueAsString(result.interpretation()));
        row.setModel("fake");
        row.setGeneratedAt(Instant.now());
        when(repo.findFirstByJobIdOrderByGeneratedAtDesc(job)).thenReturn(Optional.of(row));
        assertThat(service.get(job).facts()).isEqualTo(facts);
        verifyNoInteractions(collector, client);
    }

    @Test
    void simultaneousCallsCoalesceOneModelRequest() throws Exception {
        setup();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(client.analyze(any()))
                .thenAnswer(
                        i -> {
                            entered.countDown();
                            release.await(5, TimeUnit.SECONDS);
                            return result;
                        });
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> service.analyze(job));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            var second = executor.submit(() -> service.analyze(job));
            // Synchronize using the mocked cache query, not timing or arbitrary sleeps.
            verify(repo, timeout(3000).atLeast(3)).findByJobIdAndContextHash(eq(job), anyString());
            release.countDown();
            assertThat(first.get(5, TimeUnit.SECONDS).analysisId())
                    .isEqualTo(second.get(5, TimeUnit.SECONDS).analysisId());
        }
        verify(client, times(1)).analyze(any());
        verify(repo, times(1)).saveAndFlush(any());
    }

    @Test
    void totalSerializedContextIsBoundedBeforeProviderCall() {
        when(collector.collect(job))
                .thenReturn(
                        new FailureContext(job, "FAILED", List.of(), List.of("x".repeat(32769))));
        assertThatThrownBy(() -> service.analyze(job))
                .isInstanceOf(AiOutputException.class)
                .hasMessageContaining("bounded");
        verifyNoInteractions(client, repo);
    }
}
