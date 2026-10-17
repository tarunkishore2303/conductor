package com.tarunkishore.loom_api.ai.optimization;

import com.tarunkishore.loom_api.exception.*;
import com.tarunkishore.loom_api.repository.AiWorkflowOptimizationRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import tools.jackson.databind.json.JsonMapper;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class WorkflowOptimizationServiceTest {
    private final OptimizationContextService contexts = mock(OptimizationContextService.class);
    private final OptimizationAnalyzerRegistry analyzers = mock(OptimizationAnalyzerRegistry.class);
    private final WorkflowOptimizationClient client = mock(WorkflowOptimizationClient.class);
    private final AiWorkflowOptimizationRepository repository = mock(AiWorkflowOptimizationRepository.class);
    private final SimpleMeterRegistry metrics = new SimpleMeterRegistry();
    private final WorkflowOptimizationService service = new WorkflowOptimizationService(contexts, analyzers, client, repository, JsonMapper.builder().build(), metrics);
    private final UUID workflow = UUID.randomUUID();
    private final WorkflowOptimizationClient.Result result = new WorkflowOptimizationClient.Result(
            new WorkflowOptimizationClient.Interpretation("Review recorded candidate evidence.", List.of(
                    new WorkflowOptimizationClient.Recommendation(List.of("O1"), "Investigate observed duration.", "The candidate is based on recorded attempts."))), "fake");

    @BeforeEach void setup() {
        when(analyzers.analyze(any())).thenReturn(facts("first", "CANDIDATES", true));
        when(repository.findByWorkflowIdAndContextHash(eq(workflow), anyString())).thenReturn(Optional.empty());
        when(repository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(client.interpret(any())).thenReturn(result);
    }
    @AfterEach void close() { metrics.close(); }

    @Test void persistsFactsSeparatelyAndUsesLowCardinalityMetrics() {
        var report = service.generate(workflow);
        assertThat(report.facts()).isEqualTo(facts("first", "CANDIDATES", true));
        var saved = ArgumentCaptor.forClass(AiWorkflowOptimization.class);
        verify(repository).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getFactsJson()).doesNotContain("Investigate observed duration");
        assertThat(saved.getValue().getInterpretationJson()).contains("Investigate observed duration");
        metrics.getMeters().forEach(meter -> meter.getId().getTags().forEach(tag ->
                assertThat(tag.getKey()).isIn("outcome")));
    }

    @Test void identicalSnapshotReusesCacheDuringProviderOutage() {
        var first = service.generate(workflow);
        var saved = ArgumentCaptor.forClass(AiWorkflowOptimization.class);
        verify(repository).saveAndFlush(saved.capture());
        when(repository.findByWorkflowIdAndContextHash(eq(workflow), anyString())).thenReturn(Optional.of(saved.getValue()));
        when(client.interpret(any())).thenThrow(new AiUnavailableException());
        assertThat(service.generate(workflow)).isEqualTo(first);
        verify(client, times(1)).interpret(any());
        assertThat(metrics.get("ai.optimization.duration").tag("outcome", "cached").timer().count()).isEqualTo(1);
    }

    @Test void storedGetHasNoContextAnalyzerOrModelDependency() {
        var first = service.generate(workflow);
        var saved = ArgumentCaptor.forClass(AiWorkflowOptimization.class);
        verify(repository).saveAndFlush(saved.capture());
        when(repository.findFirstByWorkflowIdOrderByGeneratedAtDescIdDesc(workflow)).thenReturn(Optional.of(saved.getValue()));
        clearInvocations(contexts, analyzers, client);
        assertThat(service.get(workflow)).isEqualTo(first);
        verifyNoInteractions(contexts, analyzers, client);
        saved.getValue().setFactsJson("not JSON");
        assertThatThrownBy(() -> service.get(workflow)).isInstanceOf(AiOutputException.class);
    }

    @Test void noCandidatesAndInsufficientHistoryNeverInvokeModel() {
        for (String outcome : List.of("NO_CANDIDATES", "INSUFFICIENT_HISTORY")) {
            when(analyzers.analyze(any())).thenReturn(facts(outcome, outcome, false));
            var report = service.generate(workflow);
            assertThat(report.model()).isEqualTo("deterministic");
            assertThat(report.interpretation().recommendations()).isEmpty();
            assertThat(report.interpretation().overview()).contains(outcome.equals("INSUFFICIENT_HISTORY") ? "Insufficient" : "No optimization");
        }
        verifyNoInteractions(client);
    }

    @Test void changedEvidenceProducesNewImmutableSnapshot() {
        var first = service.generate(workflow);
        when(analyzers.analyze(any())).thenReturn(facts("second", "CANDIDATES", true));
        var second = service.generate(workflow);
        assertThat(second.contextHash()).isNotEqualTo(first.contextHash());
        assertThat(second.optimizationId()).isNotEqualTo(first.optimizationId());
        verify(client, times(2)).interpret(any());
    }

    @Test void invalidAndUnavailableInterpretationsNeverPersist() {
        when(client.interpret(any())).thenReturn(new WorkflowOptimizationClient.Result(null, "fake"));
        assertThatThrownBy(() -> service.generate(workflow)).isInstanceOf(AiOutputException.class);
        when(client.interpret(any())).thenThrow(new AiUnavailableException());
        assertThatThrownBy(() -> service.generate(workflow)).isInstanceOf(AiUnavailableException.class);
        verify(repository, never()).saveAndFlush(any());
        assertThat(metrics.get("ai.optimization.failures").counter().count()).isEqualTo(2);
    }

    @Test void insertRaceReturnsWinner() {
        AtomicReference<AiWorkflowOptimization> winner = new AtomicReference<>();
        when(repository.findByWorkflowIdAndContextHash(eq(workflow), anyString())).thenAnswer(invocation -> Optional.ofNullable(winner.get()));
        UUID winnerId = UUID.randomUUID();
        when(repository.saveAndFlush(any())).thenAnswer(invocation -> {
            AiWorkflowOptimization row = invocation.getArgument(0);
            row.setId(winnerId); winner.set(row);
            throw new DataIntegrityViolationException("concurrent insert");
        });
        assertThat(service.generate(workflow).optimizationId()).isEqualTo(winnerId);
    }

    @Test void identicalInFlightRequestsCoalesceOneModelCall() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        AtomicReference<AiWorkflowOptimization> stored = new AtomicReference<>();
        when(repository.findByWorkflowIdAndContextHash(eq(workflow), anyString())).thenAnswer(invocation -> Optional.ofNullable(stored.get()));
        when(repository.saveAndFlush(any())).thenAnswer(invocation -> { AiWorkflowOptimization row = invocation.getArgument(0); stored.set(row); return row; });
        when(client.interpret(any())).thenAnswer(invocation -> { entered.countDown(); release.await(5, TimeUnit.SECONDS); return result; });
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> service.generate(workflow));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            var second = executor.submit(() -> service.generate(workflow));
            verify(repository, timeout(3000).atLeast(3)).findByWorkflowIdAndContextHash(eq(workflow), anyString());
            release.countDown();
            assertThat(first.get(5, TimeUnit.SECONDS).optimizationId()).isEqualTo(second.get(5, TimeUnit.SECONDS).optimizationId());
        } finally { release.countDown(); }
        verify(client, times(1)).interpret(any());
        verify(repository, times(1)).saveAndFlush(any());
    }

    private OptimizationFacts facts(String fingerprint, String outcome, boolean candidate) {
        return new OptimizationFacts(workflow, 1L, List.of(), candidate ? 5 : 0, List.of(), 5, outcome, false, 0, 2592000,
                candidate ? List.of(new OptimizationCandidate("O1", OptimizationType.SLOW_TASK, OptimizationSeverity.WARNING,
                        OptimizationClassification.EVIDENCE_BACKED, List.of("task-a"), "Recorded slow task", null, "Active time only.")) : List.of(),
                List.of("No wall-clock inference."), fingerprint);
    }
}
