package com.loom.ai.service;

import com.loom.ai.config.AiProperties;
import com.loom.ai.exception.AiInvalidRequestException;
import com.loom.ai.exception.AiOutputValidationException;
import com.loom.ai.exception.AiProviderUnavailableException;
import com.loom.ai.model.OptimizationInterpretation;
import com.loom.ai.model.WorkflowOptimizationModel;
import com.loom.ai.model.WorkflowOptimizationRequest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class WorkflowOptimizationServiceTest {
    private static final String WORKFLOW = "00000000-0000-0000-0000-000000000001";
    private static final String TASK = "00000000-0000-0000-0000-000000000002";
    private final ValidatorFactory factory = Validation.buildDefaultValidatorFactory();
    private final WorkflowOptimizationModel model = mock(WorkflowOptimizationModel.class);
    private final SimpleMeterRegistry metrics = new SimpleMeterRegistry();
    private final WorkflowOptimizationService service = new WorkflowOptimizationService(model, factory.getValidator(),
            new AiProperties(false, "http://localhost:11434", "local-model", 0, Duration.ofSeconds(1), 4096), metrics);

    @AfterEach void close() { factory.close(); metrics.close(); }

    @Test void interpretsOnlyExistingCandidates() {
        var result = output("Recorded evidence warrants investigation.", "O1");
        when(model.interpret(any())).thenReturn(result);
        assertThat(service.interpret(request())).isEqualTo(result);
        assertThat(metrics.get("ai.requests").counter().count()).isEqualTo(1);
    }

    @Test void invalidSchemaAndMalformedFactsNeverInvokeModel() {
        for (String facts : List.of("", "[]", "not-json", "{} {}", "x".repeat(24001), "{}")) {
            assertThatThrownBy(() -> service.interpret(new WorkflowOptimizationRequest(facts, WORKFLOW, List.of("O1"))))
                    .isInstanceOf(AiInvalidRequestException.class);
        }
        assertThatThrownBy(() -> service.interpret(null)).isInstanceOf(AiInvalidRequestException.class);
        verifyNoInteractions(model);
    }

    @Test void multibyteFactsRespectUtf8ContextBudgetBeforeInference() {
        String facts = request().factsJson().replace("SLOW_TASK", "\u6f22".repeat(9000));
        assertThat(facts.length()).isLessThan(24000);
        assertThat(facts.getBytes(StandardCharsets.UTF_8).length).isGreaterThan(24000);
        assertThatThrownBy(() -> service.interpret(
                new WorkflowOptimizationRequest(facts, WORKFLOW, List.of("O1"))))
                .isInstanceOf(AiInvalidRequestException.class);
        verifyNoInteractions(model);
    }

    @Test void exactUniqueCandidateWhitelistAndWorkflowRequired() {
        for (var ids : List.of(List.<String>of(), List.of("O2"), List.of("O1", "O1"), List.of("O1", "O2"))) {
            assertThatThrownBy(() -> service.interpret(new WorkflowOptimizationRequest(request().factsJson(), WORKFLOW, ids)))
                    .isInstanceOf(AiInvalidRequestException.class);
        }
        assertThatThrownBy(() -> service.interpret(new WorkflowOptimizationRequest(request().factsJson(), TASK, List.of("O1"))))
                .isInstanceOf(AiInvalidRequestException.class);
        String duplicate = request().factsJson().replace("}]", "},{\"candidateId\":\"O1\"}]");
        assertThatThrownBy(() -> service.interpret(new WorkflowOptimizationRequest(duplicate, WORKFLOW, List.of("O1"))))
                .isInstanceOf(AiInvalidRequestException.class);
        verifyNoInteractions(model);
    }

    @Test void rejectsEmptyAndOversizedInterpretation() {
        for (var result : List.of(output("", "O1"), output("x".repeat(2001), "O1"),
                new OptimizationInterpretation("Overview", List.of()),
                new OptimizationInterpretation("Overview", List.of(new OptimizationInterpretation.Recommendation(List.of("O1"), "", "Evidence"))),
                new OptimizationInterpretation("Overview", List.of(new OptimizationInterpretation.Recommendation(List.of("O1"), "Investigate", "x".repeat(1001)))))) {
            when(model.interpret(any())).thenReturn(result);
            assertThatThrownBy(() -> service.interpret(request())).isInstanceOf(AiOutputValidationException.class);
        }
        when(model.interpret(any())).thenReturn(null);
        assertThatThrownBy(() -> service.interpret(request())).isInstanceOf(AiOutputValidationException.class);
    }

    @Test void rejectsInventedCandidateInStructuredAndTextReferences() {
        for (var result : List.of(output("Overview", "O9"), output("Investigate O9", "O1"),
                new OptimizationInterpretation("Overview", List.of(new OptimizationInterpretation.Recommendation(List.of("O1"), "Investigate O9", "Evidence"))))) {
            when(model.interpret(any())).thenReturn(result);
            assertThatThrownBy(() -> service.interpret(request())).isInstanceOf(AiOutputValidationException.class);
        }
    }

    @Test void rejectsDuplicateCitationsAcrossRecommendations() {
        var recommendation = output("Overview", "O1").recommendations().getFirst();
        when(model.interpret(any())).thenReturn(new OptimizationInterpretation("Overview", List.of(recommendation, recommendation)));
        assertThatThrownBy(() -> service.interpret(request())).isInstanceOf(AiOutputValidationException.class);
    }

    @Test void onlyEvidenceBackedUuidsAllowed() {
        when(model.interpret(any())).thenReturn(output("Task " + TASK + " has recorded timing evidence.", "O1"));
        assertThat(service.interpret(request()).overview()).contains(TASK);
        when(model.interpret(any())).thenReturn(output("Unknown 00000000-0000-0000-0000-000000000099", "O1"));
        assertThatThrownBy(() -> service.interpret(request())).isInstanceOf(AiOutputValidationException.class);
    }

    @Test void sanitizesFactsBeforeInference() {
        var request = request();
        String facts = request.factsJson().replace("\"SLOW_TASK\"", "\"password=fixture-value\"");
        when(model.interpret(any())).thenAnswer(invocation -> {
            WorkflowOptimizationRequest sent = invocation.getArgument(0);
            assertThat(sent.factsJson()).contains("password=[REDACTED]").doesNotContain("fixture-value");
            return output("Overview", "O1");
        });
        service.interpret(new WorkflowOptimizationRequest(facts, WORKFLOW, List.of("O1")));
    }

    @Test void contradictoryParallelismAdviceAndUnsupportedPerformancePromisesFailClosed() {
        for (String text : List.of("Consider enabling parallel execution.", "Implement retries with backoff.",
                "Significantly improve workflow performance.", "This will be 50% faster.")) {
            when(model.interpret(any())).thenReturn(new OptimizationInterpretation("Review", List.of(
                    new OptimizationInterpretation.Recommendation(List.of("O1"), text, "Recorded evidence"))));
            assertThatThrownBy(() -> service.interpret(request())).isInstanceOf(AiOutputValidationException.class);
        }
    }
    @Test void unavailableAndMalformedProviderResponsesRemainControlledAndMeasured() {
        when(model.interpret(any())).thenThrow(new AiProviderUnavailableException());
        assertThatThrownBy(() -> service.interpret(request())).isInstanceOf(AiProviderUnavailableException.class);
        doThrow(new AiOutputValidationException("Invalid structured output")).when(model).interpret(any());
        assertThatThrownBy(() -> service.interpret(request())).isInstanceOf(AiOutputValidationException.class);
        assertThat(metrics.get("ai.request.failures").counter().count()).isEqualTo(2);
    }

    private WorkflowOptimizationRequest request() {
        return new WorkflowOptimizationRequest("{\"workflowId\":\"" + WORKFLOW + "\",\"candidates\":[{\"candidateId\":\"O1\",\"type\":\"SLOW_TASK\",\"taskId\":\"" + TASK + "\"}]}", WORKFLOW, List.of("O1"));
    }
    private OptimizationInterpretation output(String overview, String id) {
        return new OptimizationInterpretation(overview, List.of(new OptimizationInterpretation.Recommendation(List.of(id),
                "Investigate the observed execution behavior.", "The recorded candidate warrants human review.")));
    }
}
