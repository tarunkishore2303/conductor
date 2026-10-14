package com.loom.ai.validation;

import com.loom.ai.exception.AiOutputValidationException;
import com.loom.ai.model.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Set;
import static org.assertj.core.api.Assertions.*;

class InterpretationValidatorsTest {
    private static final String JOB = "00000000-0000-0000-0000-000000000001";
    private static final String UNKNOWN = "00000000-0000-0000-0000-000000000099";

    @Test void failureInterpretationRequiresBoundedOperationalActions() {
        var validator = new FailureInterpretationValidator();
        assertThatCode(() -> validator.validate(new FailureInterpretation("Timeout", FailureInterpretation.Confidence.LOW,
                "Only a timeout was observed", List.of("Check dependency availability")))).doesNotThrowAnyException();
        assertThatThrownBy(() -> validator.validate(new FailureInterpretation("Timeout", FailureInterpretation.Confidence.LOW,
                "Only a timeout was observed", List.of()))).isInstanceOf(AiOutputValidationException.class);
    }

    @Test void failureInterpretationRejectsNullAndOversizedOutput() {
        var validator = new FailureInterpretationValidator();
        assertThatThrownBy(() -> validator.validate(null)).isInstanceOf(AiOutputValidationException.class);
        assertThatThrownBy(() -> validator.validate(new FailureInterpretation("x".repeat(1001), FailureInterpretation.Confidence.LOW,
                "Explanation", List.of("Check")))).isInstanceOf(AiOutputValidationException.class);
    }

    @Test void incidentSynthesisAcceptsOnlyUniqueRetrievedCitations() {
        var validator = new IncidentSynthesisValidator();
        assertThatCode(() -> validator.validate(new IncidentSynthesis("Similar timeout", List.of(JOB)), Set.of(JOB)))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> validator.validate(new IncidentSynthesis("Similar timeout", List.of(UNKNOWN)), Set.of(JOB)))
                .isInstanceOf(AiOutputValidationException.class);
        assertThatThrownBy(() -> validator.validate(new IncidentSynthesis("Similar timeout", List.of(JOB, JOB)), Set.of(JOB)))
                .isInstanceOf(AiOutputValidationException.class);
    }

    @Test void summaryInterpretationAllowsEmptyNotesButRejectsUnsupportedIdentifiers() {
        var validator = new ExecutionSummaryInterpretationValidator();
        var request = new ExecutionSummaryRequest("{}", JOB, List.of(), null);
        assertThatCode(() -> validator.validate(new ExecutionSummaryInterpretation("Run " + JOB, List.of(), List.of()), request))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> validator.validate(new ExecutionSummaryInterpretation("Run " + UNKNOWN, List.of(), List.of()), request))
                .isInstanceOf(AiOutputValidationException.class);
    }

    @Test void copilotValidatorRejectsNullOutputBeforeAccessingContext() {
        assertThatThrownBy(() -> new CopilotStepValidator().validate(null, null))
                .isInstanceOf(AiOutputValidationException.class);
    }
}
