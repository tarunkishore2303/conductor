package com.tarunkishore.loom_api.ai.summary;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.tarunkishore.loom_api.ai.*;
import com.tarunkishore.loom_api.repository.AiRunSummaryRepository;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.junit.jupiter.api.*;

import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

class ExecutionSummaryServiceTest {
    final ExecutionSummaryContextService contexts = mock(ExecutionSummaryContextService.class);
    final ExecutionSummaryClient client = mock(ExecutionSummaryClient.class);
    final AiRunSummaryRepository repository = mock(AiRunSummaryRepository.class);
    final SimpleMeterRegistry metrics = new SimpleMeterRegistry();
    final ExecutionSummaryService service =
            new ExecutionSummaryService(
                    contexts, client, repository, JsonMapper.builder().build(), metrics);
    final UUID job = UUID.randomUUID(), analysis = UUID.randomUUID();
    final ExecutionSummaryClient.Result result =
            new ExecutionSummaryClient.Result(
                    new ExecutionSummaryClient.Interpretation(
                            "Recorded execution finished",
                            List.of("Recorded tasks finished"),
                            List.of("No live dependency data")),
                    "fake");

    ExecutionSummaryFacts facts(String fingerprint) {
        return new ExecutionSummaryFacts(
                job,
                "demo",
                "COMPLETE",
                Instant.parse("2026-10-07T10:00:00Z"),
                null,
                null,
                null,
                1,
                1,
                0,
                0,
                0,
                0,
                1,
                0,
                true,
                10,
                true,
                List.of(),
                analysis,
                fingerprint,
                List.of());
    }

    @BeforeEach
    void setup() {
        when(contexts.collect(job)).thenReturn(facts("first"));
        when(repository.findByJobIdAndContextHash(eq(job), anyString()))
                .thenReturn(Optional.empty());
        when(client.summarize(any())).thenReturn(result);
        when(repository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void immutableFactsAndAnalysisReferencePersistSeparatelyFromInterpretation() {
        var summary = service.generate(job);
        assertThat(summary.facts()).isEqualTo(facts("first"));
        assertThat(summary.facts().failureAnalysisId()).isEqualTo(analysis);
        assertThat(summary.interpretation().overview()).isEqualTo("Recorded execution finished");
        var saved = org.mockito.ArgumentCaptor.forClass(AiRunSummary.class);
        verify(repository).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getFactsJson()).doesNotContain("Recorded execution finished");
    }

    @Test
    void exactSnapshotUsesCacheDuringProviderOutage() {
        var saved = org.mockito.ArgumentCaptor.forClass(AiRunSummary.class);
        var first = service.generate(job);
        verify(repository).saveAndFlush(saved.capture());
        when(repository.findByJobIdAndContextHash(eq(job), anyString()))
                .thenReturn(Optional.of(saved.getValue()));
        when(client.summarize(any())).thenThrow(new AiUnavailableException());
        assertThat(service.generate(job).summaryId()).isEqualTo(first.summaryId());
        verify(client, times(1)).summarize(any());
        assertThat(metrics.get("ai.summary.duration").tag("outcome", "cached").timer().count())
                .isEqualTo(1);
    }

    @Test
    void changedEvidenceCreatesDifferentSummaryHash() {
        var first = service.generate(job);
        when(contexts.collect(job)).thenReturn(facts("second"));
        var second = service.generate(job);
        assertThat(second.contextHash()).isNotEqualTo(first.contextHash());
        verify(client, times(2)).summarize(any());
    }

    @Test
    void malformedAndUnavailableResponsesNeverPersist() {
        when(client.summarize(any())).thenReturn(new ExecutionSummaryClient.Result(null, "fake"));
        assertThatThrownBy(() -> service.generate(job)).isInstanceOf(AiOutputException.class);
        when(client.summarize(any())).thenThrow(new AiUnavailableException());
        assertThatThrownBy(() -> service.generate(job)).isInstanceOf(AiUnavailableException.class);
        verify(repository, never()).saveAndFlush(any());
        assertThat(metrics.get("ai.summary.failures").counter().count()).isEqualTo(2);
    }

    @Test
    void foreignUuidCannotBeClaimedAsEvidence() {
        when(client.summarize(any()))
                .thenReturn(
                        new ExecutionSummaryClient.Result(
                                new ExecutionSummaryClient.Interpretation(
                                        "Unrelated run " + UUID.randomUUID(), List.of(), List.of()),
                                "fake"));
        assertThatThrownBy(() -> service.generate(job))
                .isInstanceOf(AiOutputException.class)
                .hasMessageContaining("unsupported");
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void storedGetDoesNotReadExecutionStateOrCallModel() {
        var saved = org.mockito.ArgumentCaptor.forClass(AiRunSummary.class);
        var first = service.generate(job);
        verify(repository).saveAndFlush(saved.capture());
        clearInvocations(contexts, client);
        when(repository.findFirstByJobIdOrderByGeneratedAtDesc(job))
                .thenReturn(Optional.of(saved.getValue()));
        assertThat(service.get(job).summaryId()).isEqualTo(first.summaryId());
        verifyNoInteractions(contexts, client);
        saved.getValue().setFactsJson("not json");
        assertThatThrownBy(() -> service.get(job)).isInstanceOf(AiOutputException.class);
    }

    @Test
    void concurrentRequestsCoalesceOneProviderCall() throws Exception {
        var persisted = new java.util.concurrent.atomic.AtomicReference<AiRunSummary>();
        when(repository.findByJobIdAndContextHash(eq(job), anyString()))
                .thenAnswer(invocation -> Optional.ofNullable(persisted.get()));
        when(repository.saveAndFlush(any()))
                .thenAnswer(
                        invocation -> {
                            AiRunSummary row = invocation.getArgument(0);
                            persisted.set(row);
                            return row;
                        });
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(client.summarize(any()))
                .thenAnswer(
                        invocation -> {
                            entered.countDown();
                            release.await(5, TimeUnit.SECONDS);
                            return result;
                        });
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> service.generate(job));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            var second = executor.submit(() -> service.generate(job));
            verify(repository, timeout(3000).atLeast(3))
                    .findByJobIdAndContextHash(eq(job), anyString());
            release.countDown();
            assertThat(first.get(5, TimeUnit.SECONDS).summaryId())
                    .isEqualTo(second.get(5, TimeUnit.SECONDS).summaryId());
        }
        verify(client, times(1)).summarize(any());
        verify(repository, times(1)).saveAndFlush(any());
    }
}
