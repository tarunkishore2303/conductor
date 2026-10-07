package com.tarunkishore.loom_api.ai.copilot;

import com.tarunkishore.loom_api.ai.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class CopilotServiceTest {
    CopilotClient client;
    CopilotToolRegistry tools;
    FailureAnalysisService analyses;
    CopilotService service;
    SimpleMeterRegistry metrics;
    final UUID run = UUID.randomUUID();

    @BeforeEach
    void setup() {
        client = mock(CopilotClient.class);
        tools = mock(CopilotToolRegistry.class);
        analyses = mock(FailureAnalysisService.class);
        metrics = new SimpleMeterRegistry();
        service = service(Duration.ofSeconds(5));
    }

    CopilotService service(Duration timeout) {
        return new CopilotService(client, tools, analyses, new JsonMapper(), metrics, timeout);
    }

    CopilotClient.Result action(String tool) {
        return new CopilotClient.Result(new CopilotClient.Step("TOOL", tool,
                new CopilotClient.Arguments(null, null), null, List.of()), "fake");
    }

    CopilotClient.Result answer(String text, List<String> citations) {
        return new CopilotClient.Result(new CopilotClient.Step("ANSWER", null, null, text, citations), "fake");
    }

    CopilotService.Query query(String text) { return new CopilotService.Query(text, run, null, null); }

    CopilotToolRegistry.ToolResult evidence(String tool) {
        return new CopilotToolRegistry.ToolResult(tool, true, null, "{\"recordedRetries\":2}",
                List.of(new CopilotToolRegistry.Reference("run", run, "/api/v1/jobs/" + run)));
    }

    @Test
    void scopedLookupReturnsInspectableEvidenceAndUsesOnlyReadTool() {
        when(client.step(any(), any())).thenReturn(action("getRun"), answer("Run " + run + " recorded two retries.", List.of("E1")));
        when(tools.execute(eq("getRun"), any(), any())).thenReturn(evidence("getRun"));
        var response = service.query(query("How many retries occurred?"));
        assertThat(response.readOnly()).isTrue();
        assertThat(response.toolsUsed()).containsExactly("getRun");
        assertThat(response.evidence().getFirst().references().getFirst().id()).isEqualTo(run);
        verifyNoInteractions(analyses);
    }

    @Test
    void mutationRequestDoesNotInvokeModelOrAnyTool() {
        var response = service.query(query("Retry this task."));
        assertThat(response.answer()).contains("read-only", "cannot retry");
        assertThat(response.toolsUsed()).isEmpty();
        verifyNoInteractions(client, tools, analyses);
    }

    @Test
    void invalidOrMissingScopeRejectedBeforeModelCall() {
        assertThatThrownBy(() -> service.query(new CopilotService.Query("state?", null, null, null))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.query(new CopilotService.Query("state?", run, UUID.randomUUID(), null))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.query(query("x".repeat(2001)))).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(client, tools);
    }

    @Test
    void toolFailureIsReturnedAsStructuredEvidence() {
        when(client.step(any(), any())).thenReturn(action("getFailureAnalysis"), answer("No stored analysis is available.", List.of("E1")));
        when(tools.execute(any(), any(), any())).thenReturn(new CopilotToolRegistry.ToolResult("getFailureAnalysis", false, "MISSING_EVIDENCE", "{}", List.of()));
        var response = service.query(query("Why did this run fail?"));
        assertThat(response.evidence().getFirst().errorCode()).isEqualTo("MISSING_EVIDENCE");
        assertThat(metrics.get("ai.tool.failures").counter().count()).isEqualTo(1);
    }

    @Test
    void unregisteredToolRemainsRejectedAndMetricsUseFixedTag() {
        when(client.step(any(), any())).thenReturn(action("executeSql"), answer("That tool is unavailable.", List.of("E1")));
        when(tools.execute(any(), any(), any())).thenReturn(new CopilotToolRegistry.ToolResult("executeSql", false, "UNSUPPORTED_TOOL", "{}", List.of()));
        assertThat(service.query(query("Get state")).evidence().getFirst().tool()).isEqualTo("unsupported");
        assertThat(metrics.get("ai.tool.calls").tag("tool", "unsupported").counter().count()).isEqualTo(1);
    }

    @Test
    void atMostFourToolsExecuteEvenWhenModelRequestsAnother() {
        when(client.step(any(), any())).thenReturn(action("getRun"));
        when(tools.execute(any(), any(), any())).thenReturn(evidence("getRun"));
        assertThatThrownBy(() -> service.query(query("Get state"))).isInstanceOf(AiOutputException.class).hasMessageContaining("four-tool");
        verify(tools, times(4)).execute(any(), any(), any());
        verify(client, times(5)).step(any(), any());
    }

    @Test
    void rejectsInventedIdentifierAndUnknownCitation() {
        when(client.step(any(), any())).thenReturn(action("getRun"), answer("Run " + UUID.randomUUID() + " failed.", List.of("E1")));
        when(tools.execute(any(), any(), any())).thenReturn(evidence("getRun"));
        assertThatThrownBy(() -> service.query(query("Get state"))).isInstanceOf(AiOutputException.class).hasMessageContaining("ungrounded");
        when(client.step(any(), any())).thenReturn(answer("Unknown state", List.of("E9")));
        assertThatThrownBy(() -> service.query(query("Get state"))).isInstanceOf(AiOutputException.class);
    }

    @Test
    void providerFailureAndMalformedOutputAreDomainErrors() {
        when(client.step(any(), any())).thenThrow(new AiUnavailableException());
        assertThatThrownBy(() -> service.query(query("Get state"))).isInstanceOf(AiUnavailableException.class);
        doReturn(new CopilotClient.Result(null, "fake")).when(client).step(any(), any());
        assertThatThrownBy(() -> service.query(query("Get state"))).isInstanceOf(AiOutputException.class);
    }

    @Test
    void totalDeadlineBoundsProviderWait() {
        var gate = new CountDownLatch(1);
        when(client.step(any(), any())).thenAnswer(call -> {
            try { gate.await(); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            throw new AiUnavailableException();
        });
        try {
            assertThatThrownBy(() -> service(Duration.ofMillis(50)).query(query("Get state"))).isInstanceOf(AiUnavailableException.class);
        } finally { gate.countDown(); }
    }

    @Test
    void malformedActionCannotReachReadTools() {
        when(client.step(any(), any())).thenReturn(new CopilotClient.Result(new CopilotClient.Step("EXECUTE", null, null, null, List.of()), "fake"));
        assertThatThrownBy(() -> service.query(query("Get state"))).isInstanceOf(AiOutputException.class);
        verifyNoInteractions(tools);
    }

    @Test
    void initialAnswerCannotInventFactsWithoutReadingEvidence() {
        when(client.step(any(), any())).thenReturn(
                answer("Three retries occurred and HTTP 503 caused the failure.", List.of()));
        assertThatThrownBy(() -> service.query(query("Why did this run fail?")))
                .isInstanceOf(AiOutputException.class).hasMessageContaining("retrieve execution evidence");
        verifyNoInteractions(tools, analyses);
    }

    @Test
    void allFailedReadsUseDeterministicFallbackInsteadOfModelFacts() {
        when(client.step(any(), any())).thenReturn(action("getFailureAnalysis"),
                answer("PaymentService failed after three retries in run " + UUID.randomUUID(), List.of("E1")));
        when(tools.execute(any(), any(), any())).thenReturn(new CopilotToolRegistry.ToolResult(
                "getFailureAnalysis", false, "MISSING_EVIDENCE", "{}", List.of()));
        var response = service.query(query("Why did this run fail?"));
        assertThat(response.answer()).contains("every requested read tool failed")
                .doesNotContain("PaymentService", "three retries");
        assertThat(response.evidence()).hasSize(1);
        assertThat(response.evidence().getFirst().errorCode()).isEqualTo("MISSING_EVIDENCE");
        assertThat(response.evidenceIds()).isEmpty();
        assertThat(response.model()).isNull();
        assertThat(response.readOnly()).isTrue();
    }
}
