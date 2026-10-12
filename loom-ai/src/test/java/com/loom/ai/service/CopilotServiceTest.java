package com.loom.ai.service;

import com.loom.ai.config.AiProperties;
import com.loom.ai.model.CopilotModel;
import com.loom.ai.model.CopilotRequest;
import com.loom.ai.model.CopilotStep;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class CopilotServiceTest {
    private static final String JOB = "00000000-0000-0000-0000-000000000001";
    private static final String TASK = "00000000-0000-0000-0000-000000000002";
    private static final String UNKNOWN = "00000000-0000-0000-0000-000000000099";
    private final ValidatorFactory factory = Validation.buildDefaultValidatorFactory();
    private final CopilotModel model = mock(CopilotModel.class);
    private final SimpleMeterRegistry metrics = new SimpleMeterRegistry();
    private final CopilotService service = new CopilotService(model, factory.getValidator(),
            new AiProperties(false, "http://localhost:11434", "local-model", 0, Duration.ofSeconds(1), 4096), metrics);

    @AfterEach
    void closeFactory() { factory.close(); }

    @Test
    void proposesOneReadOnlyToolWithoutExecutingAnything() {
        var step = new CopilotStep("TOOL", "getFailureAnalysis", null, null, List.of());
        when(model.step(any())).thenReturn(step);
        assertThat(service.step(request("Why did this run fail?", List.of("getFailureAnalysis"), List.of()))).isEqualTo(step);
        verify(model, times(1)).step(any());
        assertThat(metrics.get("ai.requests").counter().count()).isEqualTo(1);
    }

    @Test
    void validatesTaskAndSimilarityArguments() {
        var request = request("Inspect", List.of("getTaskAttempts", "getSimilarIncidents"), List.of());
        when(model.step(any())).thenReturn(new CopilotStep("TOOL", "getTaskAttempts", new CopilotStep.Arguments(TASK, null), null, List.of()));
        assertThat(service.step(request).arguments().taskId()).isEqualTo(TASK);
        when(model.step(any())).thenReturn(new CopilotStep("TOOL", "getSimilarIncidents", new CopilotStep.Arguments(null, 5), null, List.of()));
        assertThat(service.step(request).arguments().topK()).isEqualTo(5);
        when(model.step(any())).thenReturn(new CopilotStep("TOOL", "getSimilarIncidents", new CopilotStep.Arguments(null, 6), null, List.of()));
        assertThatThrownBy(() -> service.step(request)).isInstanceOf(AiOutputValidationException.class);
    }

    @Test
    void rejectsUnsupportedUnavailableAndMutatingTools() {
        for (String tool : List.of("deleteRun", "getWorkflow", "executeSql")) {
            when(model.step(any())).thenReturn(new CopilotStep("TOOL", tool, null, null, List.of()));
            assertThatThrownBy(() -> service.step(request("Inspect", List.of("getRun"), List.of())))
                    .isInstanceOf(AiOutputValidationException.class);
        }
    }

    @Test
    void toolSelectionCannotCiteFutureEvidenceBeforeAnyToolExecutes() {
        when(model.step(any())).thenReturn(new CopilotStep("TOOL", "getRun", null, null, List.of("E1")));
        assertThatThrownBy(() -> service.step(request("How many retries?", List.of("getRun"), List.of())))
                .isInstanceOf(AiOutputValidationException.class).hasMessage("AI cited unknown evidence.");
    }

    @Test
    void finalCallWithNoAllowedToolsCanOnlyAnswer() {
        var request = request("Inspect", List.of(), List.of());
        when(model.step(any())).thenReturn(answer("The requested evidence is unavailable.", List.of()));
        assertThat(service.step(request).action()).isEqualTo("ANSWER");
        when(model.step(any())).thenReturn(new CopilotStep("TOOL", "getRun", null, null, List.of()));
        assertThatThrownBy(() -> service.step(request)).isInstanceOf(AiOutputValidationException.class);
    }

    @Test
    void returnsAnswerWithCitedSuccessfulEvidenceAndBackedIdentifiers() {
        when(model.step(any())).thenReturn(answer("Run " + JOB + " failed.", List.of("E1")));
        var result = service.step(request("What happened?", List.of(), List.of(success("E1", "{\"jobId\":\"" + JOB + "\",\"status\":\"FAILED\"}"))));
        assertThat(result.evidenceIds()).containsExactly("E1");
    }

    @Test
    void rejectsUnknownEvidenceAndUnbackedIdentifiers() {
        var request = request("Inspect", List.of(), List.of(success("E1", "{}")));
        when(model.step(any())).thenReturn(answer("Failed.", List.of("E2")));
        assertThatThrownBy(() -> service.step(request)).isInstanceOf(AiOutputValidationException.class);
        when(model.step(any())).thenReturn(answer("Run " + UNKNOWN + " failed.", List.of("E1")));
        assertThatThrownBy(() -> service.step(request)).isInstanceOf(AiOutputValidationException.class);
        when(model.step(any())).thenReturn(answer("Failed.", List.of()));
        assertThatThrownBy(() -> service.step(request)).isInstanceOf(AiOutputValidationException.class);
    }

    @Test
    void failedEvidenceCannotBackExecutionIdentifiersOrReplaceSuccessfulCitation() {
        var failed = new CopilotRequest.Evidence("E2", "getTasks", false, "NOT_FOUND", "{}", List.of());
        var request = request("Inspect", List.of(), List.of(success("E1", "{}"), failed));
        when(model.step(any())).thenReturn(answer("Unknown.", List.of("E2")));
        assertThatThrownBy(() -> service.step(request)).isInstanceOf(AiOutputValidationException.class);
        when(model.step(any())).thenReturn(answer("Run " + JOB + " failed.", List.of("E2")));
        assertThatThrownBy(() -> service.step(request("Inspect", List.of(), List.of(failed))))
                .isInstanceOf(AiOutputValidationException.class);
    }

    @Test
    void mutationRequestCanExplainReadOnlyBoundaryButCannotClaimExecution() {
        var request = request("Retry this run", List.of("getRun"), List.of());
        when(model.step(any())).thenReturn(answer("I can only inspect and explain. Use the normal explicit retry operation.", List.of()));
        assertThat(service.step(request).answer()).contains("only inspect");
        when(model.step(any())).thenReturn(answer("I retried the run.", List.of()));
        assertThatThrownBy(() -> service.step(request)).isInstanceOf(AiOutputValidationException.class);
    }

    @Test
    void sanitizesQuestionAndEvidenceBeforeProvider() {
        var request = request("Inspect password=question-secret", List.of("getRun"),
                List.of(success("E1", "{\"token\":\"evidence-secret\"}")));
        when(model.step(any())).thenAnswer(invocation -> {
            CopilotRequest sent = invocation.getArgument(0);
            assertThat(sent.question()).contains("[REDACTED]").doesNotContain("question-secret");
            assertThat(sent.evidence().getFirst().factsJson()).contains("[REDACTED]").doesNotContain("evidence-secret");
            return answer("The evidence is incomplete.", List.of("E1"));
        });
        assertThat(service.step(request).action()).isEqualTo("ANSWER");
    }

    @Test
    void rejectsInvalidScopeDuplicateEvidenceAndOversizedFactsBeforeProvider() {
        assertThatThrownBy(() -> service.step(new CopilotRequest("Inspect", new CopilotRequest.Scope(JOB, TASK, null), List.of(), List.of())))
                .isInstanceOf(AiInvalidRequestException.class);
        var evidence = success("E1", "{}");
        assertThatThrownBy(() -> service.step(request("Inspect", List.of(), List.of(evidence, evidence))))
                .isInstanceOf(AiInvalidRequestException.class);
        assertThatThrownBy(() -> service.step(request("Inspect", List.of(), List.of(success("E1", "x".repeat(4097))))))
                .isInstanceOf(AiInvalidRequestException.class);
        verifyNoInteractions(model);
    }

    @Test
    void providerFailureIsSafeAndMeasured() {
        when(model.step(any())).thenThrow(new AiProviderUnavailableException());
        assertThatThrownBy(() -> service.step(request("Inspect", List.of("getRun"), List.of())))
                .isInstanceOf(AiProviderUnavailableException.class);
        assertThat(metrics.get("ai.request.failures").counter().count()).isEqualTo(1);
    }

    private CopilotRequest request(String question, List<String> tools, List<CopilotRequest.Evidence> evidence) {
        return new CopilotRequest(question, new CopilotRequest.Scope(JOB, null, null), tools, evidence);
    }

    private CopilotRequest.Evidence success(String id, String facts) {
        return new CopilotRequest.Evidence(id, "getRun", true, null, facts,
                List.of(new CopilotRequest.Reference("JOB", JOB, "/api/v1/jobs/" + JOB)));
    }

    private CopilotStep answer(String text, List<String> ids) {
        return new CopilotStep("ANSWER", null, null, text, ids);
    }
}
