package com.tarunkishore.loom_api.ai.validation;

import com.tarunkishore.loom_api.ai.optimization.*;
import com.tarunkishore.loom_api.exception.AiOutputException;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class OptimizationInterpretationValidatorTest {
    private final UUID workflow = UUID.randomUUID();
    private final UUID task = UUID.randomUUID();
    private OptimizationFacts facts() {
        return new OptimizationFacts(workflow, 1L, 5, List.of(), 5, "CANDIDATES", false, 0, 2592000,
                List.of(new OptimizationCandidate("O1", OptimizationType.SLOW_TASK, OptimizationSeverity.WARNING,
                        OptimizationClassification.EVIDENCE_BACKED, List.of(task.toString()), "Recorded slow task", null, "Active time only.")), List.of(), "fingerprint");
    }
    private WorkflowOptimizationClient.Result result(String overview, List<WorkflowOptimizationClient.Recommendation> recommendations) {
        return new WorkflowOptimizationClient.Result(new WorkflowOptimizationClient.Interpretation(overview, recommendations), "fake");
    }
    private WorkflowOptimizationClient.Recommendation recommendation(String id, String text) {
        return new WorkflowOptimizationClient.Recommendation(List.of(id), text, "Recorded evidence supports investigation.");
    }
    @Test void groundedCandidateAndTaskReferencesAccepted() {
        assertThatCode(() -> OptimizationInterpretationValidator.validate(result("Review " + workflow,
                List.of(recommendation("O1", "Investigate task " + task))), facts())).doesNotThrowAnyException();
    }
    @Test void unsupportedStructuredAndTextCandidateIdsRejected() {
        for (var value : List.of(result("Review", List.of(recommendation("O9", "Investigate"))),
                result("Review O9", List.of(recommendation("O1", "Investigate"))),
                result("Review", List.of(recommendation("O1", "Investigate O9"))))) {
            assertThatThrownBy(() -> OptimizationInterpretationValidator.validate(value, facts())).isInstanceOf(AiOutputException.class);
        }
    }
    @Test void duplicateCandidateCitationsRejected() {
        var recommendation = recommendation("O1", "Investigate");
        assertThatThrownBy(() -> OptimizationInterpretationValidator.validate(result("Review", List.of(recommendation, recommendation)), facts()))
                .isInstanceOf(AiOutputException.class);
    }
    @Test void unsupportedUuidRejected() {
        assertThatThrownBy(() -> OptimizationInterpretationValidator.validate(result("Unknown " + UUID.randomUUID(),
                List.of(recommendation("O1", "Investigate"))), facts())).isInstanceOf(AiOutputException.class);
    }
    @Test void emptyOversizedAndMalformedOutputsRejected() {
        for (var value : List.of(result("", List.of(recommendation("O1", "Investigate"))),
                result("x".repeat(2001), List.of(recommendation("O1", "Investigate"))),
                result("Review", List.of(recommendation("O1", ""))),
                result("Review", List.of(recommendation("O1", "x".repeat(1001)))), result("Review", List.of()))) {
            assertThatThrownBy(() -> OptimizationInterpretationValidator.validate(value, facts())).isInstanceOf(AiOutputException.class);
        }
        assertThatThrownBy(() -> OptimizationInterpretationValidator.validate(null, facts())).isInstanceOf(AiOutputException.class);
    }
}
