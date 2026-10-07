package com.tarunkishore.loom_api.ai.copilot;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class CopilotToolRegistryTest {
    private final CopilotReadService reads = mock(CopilotReadService.class);
    private final CopilotToolRegistry registry = new CopilotToolRegistry(reads, JsonMapper.builder().build());
    private final UUID jobId = UUID.randomUUID();
    private final CopilotToolRegistry.Scope scope = new CopilotToolRegistry.Scope(jobId, null, null);

    @Test
    void rejectsUnknownToolsAndScopeEscapeBeforeCallingReads() {
        assertThat(registry.execute("executeSql", null, scope).errorCode()).isEqualTo("UNSUPPORTED_TOOL");
        assertThat(registry.execute("getWorkflow", null, scope).errorCode()).isEqualTo("OUT_OF_SCOPE");
        assertThat(registry.execute("getRun", new CopilotToolRegistry.Arguments(UUID.randomUUID(), null),
                scope).errorCode()).isEqualTo("INVALID_ARGUMENT");
        assertThat(registry.execute("getSimilarIncidents", new CopilotToolRegistry.Arguments(null, 6),
                scope).errorCode()).isEqualTo("INVALID_ARGUMENT");
        assertThat(registry.execute("getTaskAttempts", null, scope).errorCode()).isEqualTo("INVALID_ARGUMENT");
        verifyNoInteractions(reads);
    }

    @Test
    void workflowScopeOnlyPermitsWorkflowRead() {
        var scope = new CopilotToolRegistry.Scope(null, UUID.randomUUID(), null);
        assertThat(CopilotToolRegistry.namesFor(scope)).containsExactly("getWorkflow");
        assertThat(registry.execute("getTasks", null, scope).errorCode()).isEqualTo("OUT_OF_SCOPE");
        verifyNoInteractions(reads);
    }

    @Test
    void missingEvidenceIsStructuredAndHasNoFabricatedFacts() {
        when(reads.getRun(jobId)).thenThrow(new NoSuchElementException("secret exception message"));
        var result = registry.execute("getRun", null, scope);
        assertThat(result.success()).isFalse();
        assertThat(result.errorCode()).isEqualTo("MISSING_EVIDENCE");
        assertThat(result.factsJson()).isEqualTo("{}");
        assertThat(result.references()).isEmpty();
    }

    @Test
    void oversizedEvidenceFailsExplicitlyWithoutSilentTruncation() {
        when(reads.getTasks(jobId, false)).thenReturn(new CopilotReadService.TaskFacts(jobId,
                List.of(new CopilotReadService.TaskState(UUID.randomUUID(), "x".repeat(5000),
                        "PENDING", 1, null))));
        var result = registry.execute("getTasks", null, scope);
        assertThat(result.errorCode()).isEqualTo("CONTEXT_LIMIT");
        assertThat(result.references()).isEmpty();
    }

    @Test
    void successfulRunReadProducesCodeOwnedReference() {
        when(reads.getRun(jobId)).thenReturn(new CopilotReadService.RunFacts(jobId, "orders", "FAILED",
                null, null, Map.of("DEAD_LETTERED", 1L), 2, 1, true, null, null, null, 0, List.of(), false,
                List.of("logs unavailable")));
        var result = registry.execute("getRun", null, scope);
        assertThat(result.success()).isTrue();
        assertThat(result.factsJson()).contains("recordedRetries", "logs unavailable");
        assertThat(result.references()).containsExactly(new CopilotToolRegistry.Reference(
                "run", jobId, "/api/v1/jobs/" + jobId));
    }

    @Test
    void taskScopeErrorIsReportedWithoutReadingAnotherRun() {
        UUID taskId = UUID.randomUUID();
        when(reads.getTaskAttempts(jobId, taskId))
                .thenThrow(new CopilotReadService.ToolReadException("OUT_OF_SCOPE", "wrong run"));
        assertThat(registry.execute("getTaskAttempts", new CopilotToolRegistry.Arguments(taskId, null),
                scope).errorCode()).isEqualTo("OUT_OF_SCOPE");
    }

    @Test
    void runAndTaskFactsHaveReferencesForBothIds() {
        UUID taskId = UUID.randomUUID();
        when(reads.getTasks(jobId, true)).thenReturn(new CopilotReadService.TaskFacts(jobId,
                List.of(new CopilotReadService.TaskState(taskId, "failed", "DEAD_LETTERED", 1, null))));
        var result = registry.execute("getFailedTasks", null, scope);
        assertThat(result.success()).isTrue();
        assertThat(result.references()).containsExactly(
                new CopilotToolRegistry.Reference("run", jobId, "/api/v1/jobs/" + jobId),
                new CopilotToolRegistry.Reference("task", taskId, "/api/v1/jobs/" + jobId));
    }

    @Test
    void longestTaskIdsAreCodeOwnedReferences() {
        UUID taskId = UUID.randomUUID();
        when(reads.getRun(jobId)).thenReturn(new CopilotReadService.RunFacts(jobId, "orders", "FAILED",
                null, null, Map.of("DEAD_LETTERED", 1L), 2, 1, true, null, null, null, 100,
                List.of(new CopilotReadService.ObservedTaskDuration(taskId, "slow", 100, 1.0, 2, true)),
                true, List.of()));
        var result = registry.execute("getRun", null, scope);
        assertThat(result.success()).isTrue();
        assertThat(result.references()).extracting(CopilotToolRegistry.Reference::id)
                .containsExactly(jobId, taskId);
    }
}
