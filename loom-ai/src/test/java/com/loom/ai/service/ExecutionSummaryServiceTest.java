package com.loom.ai.service;

import com.loom.ai.config.AiProperties;
import com.loom.ai.model.ExecutionSummaryInterpretation;
import com.loom.ai.model.ExecutionSummaryModel;
import com.loom.ai.model.ExecutionSummaryRequest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ExecutionSummaryServiceTest {
    private static final String JOB = "00000000-0000-0000-0000-000000000001";
    private static final String TASK = "00000000-0000-0000-0000-000000000002";
    private static final String ANALYSIS = "00000000-0000-0000-0000-000000000003";
    private static final String UNKNOWN = "00000000-0000-0000-0000-000000000099";
    private final ValidatorFactory factory = Validation.buildDefaultValidatorFactory();
    private final ExecutionSummaryModel model = mock(ExecutionSummaryModel.class);
    private final SimpleMeterRegistry metrics = new SimpleMeterRegistry();
    private final ExecutionSummaryService service = new ExecutionSummaryService(model, factory.getValidator(),
            new AiProperties(false, "http://localhost:11434", "local-model", 0, Duration.ofSeconds(1), 4096), metrics);

    @AfterEach
    void closeFactory() { factory.close(); }

    @ParameterizedTest
    @ValueSource(strings = {"COMPLETE", "FAILED"})
    void interpretsTerminalRunWithoutAuthoringNumericalFacts(String status) {
        var interpretation = new ExecutionSummaryInterpretation("The recorded run reached a terminal outcome.",
                List.of("Task " + TASK + " appears in the observed timing evidence."), List.of());
        when(model.summarize(any())).thenReturn(interpretation);
        assertThat(service.summarize(request(status))).isEqualTo(interpretation);
        assertThat(metrics.get("ai.requests").counter().count()).isEqualTo(1);
    }

    @Test
    void invalidJsonEmptyAndOversizedRequestsNeverReachProvider() {
        for (String facts : List.of("", "not JSON", "[]", "{} {}", "x".repeat(24001))) {
            assertThatThrownBy(() -> service.summarize(new ExecutionSummaryRequest(facts, JOB, List.of(TASK), null)))
                    .isInstanceOf(AiInvalidRequestException.class);
        }
        assertThatThrownBy(() -> service.summarize(null)).isInstanceOf(AiInvalidRequestException.class);
        verifyNoInteractions(model);
    }

    @Test
    void mismatchedRunNonterminalStatusAndWrongTaskWhitelistAreRejected() {
        var request = request("COMPLETE");
        assertThatThrownBy(() -> service.summarize(new ExecutionSummaryRequest(request.factsJson(), UNKNOWN, List.of(TASK), null)))
                .isInstanceOf(AiInvalidRequestException.class);
        assertThatThrownBy(() -> service.summarize(request("RUNNING"))).isInstanceOf(AiInvalidRequestException.class);
        assertThatThrownBy(() -> service.summarize(new ExecutionSummaryRequest(request.factsJson(), JOB, List.of(UNKNOWN), null)))
                .isInstanceOf(AiInvalidRequestException.class);
        verifyNoInteractions(model);
    }

    @Test
    void failureAnalysisReferenceMustAppearInFacts() {
        var request = request("FAILED");
        assertThatThrownBy(() -> service.summarize(new ExecutionSummaryRequest(request.factsJson(), JOB, List.of(TASK), ANALYSIS)))
                .isInstanceOf(AiInvalidRequestException.class);
        String facts = request.factsJson().replace("\"failureAnalysisId\":null", "\"failureAnalysisId\":\"" + ANALYSIS + "\"");
        when(model.summarize(any())).thenReturn(new ExecutionSummaryInterpretation("Run " + JOB + " has recorded analysis " + ANALYSIS + ".", List.of(), List.of()));
        assertThat(service.summarize(new ExecutionSummaryRequest(facts, JOB, List.of(TASK), ANALYSIS)).overview()).contains(ANALYSIS);
    }

    @Test
    void missingOrNullTerminalStatusReturnsInvalidRequest() {
        String facts = request("COMPLETE").factsJson();
        for (String invalidFacts : List.of(facts.replace("\"status\":\"COMPLETE\",", ""),
                facts.replace("\"status\":\"COMPLETE\"", "\"status\":null"))) {
            assertThatThrownBy(() -> service.summarize(new ExecutionSummaryRequest(invalidFacts, JOB, List.of(TASK), null)))
                    .isInstanceOf(AiInvalidRequestException.class);
        }
        verifyNoInteractions(model);
    }

    @Test
    void redactionPreservesTypedJsonBeforeModel() {
        var request = request("FAILED");
        String facts = request.factsJson().replace("\"name\":\"Orders\"", "\"name\":\"password=private-value\"");
        when(model.summarize(any())).thenAnswer(invocation -> {
            ExecutionSummaryRequest sent = invocation.getArgument(0);
            var parsed = new JsonMapper().readTree(sent.factsJson());
            assertThat(parsed.get("name").stringValue()).isEqualTo("password=[REDACTED]");
            assertThat(parsed.get("jobId").stringValue()).isEqualTo(JOB);
            return new ExecutionSummaryInterpretation("Evidence is incomplete.", List.of(), List.of());
        });
        assertThat(service.summarize(new ExecutionSummaryRequest(facts, JOB, List.of(TASK), null)).overview())
                .isEqualTo("Evidence is incomplete.");
    }

    @Test
    void emptyOversizedAndUnknownIdentifierOutputsAreRejected() {
        for (var output : List.of(
                new ExecutionSummaryInterpretation("", List.of(), List.of()),
                new ExecutionSummaryInterpretation("x".repeat(2001), List.of(), List.of()),
                new ExecutionSummaryInterpretation("Overview", Collections.nCopies(6, "Event"), List.of()),
                new ExecutionSummaryInterpretation("Overview", List.of(), List.of("x".repeat(1001))),
                new ExecutionSummaryInterpretation("Run " + UNKNOWN + " completed.", List.of(), List.of()))) {
            when(model.summarize(any())).thenReturn(output);
            assertThatThrownBy(() -> service.summarize(request("COMPLETE"))).isInstanceOf(AiOutputValidationException.class);
        }
    }

    @Test
    void unavailableProviderIsSafeAndMeasured() {
        when(model.summarize(any())).thenThrow(new AiProviderUnavailableException());
        assertThatThrownBy(() -> service.summarize(request("FAILED"))).isInstanceOf(AiProviderUnavailableException.class);
        assertThat(metrics.get("ai.request.failures").counter().count()).isEqualTo(1);
    }

    private ExecutionSummaryRequest request(String status) {
        String facts = "{\"jobId\":\"" + JOB + "\",\"name\":\"Orders\",\"status\":\"" + status
                + "\",\"slowestObservedTasks\":[{\"taskId\":\"" + TASK
                + "\",\"name\":\"Validate\",\"durationMillis\":50}],\"failureAnalysisId\":null,\"retryHistoryComplete\":false}";
        return new ExecutionSummaryRequest(facts, JOB, List.of(TASK), null);
    }
}
