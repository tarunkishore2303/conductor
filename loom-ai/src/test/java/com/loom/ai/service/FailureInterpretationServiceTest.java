package com.loom.ai.service;

import com.loom.ai.config.AiProperties;
import com.loom.ai.model.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class FailureInterpretationServiceTest {
    private final ValidatorFactory factory = Validation.buildDefaultValidatorFactory();
    private final FailureInterpretationModel model = mock(FailureInterpretationModel.class);
    private final SimpleMeterRegistry metrics = new SimpleMeterRegistry();
    private final FailureInterpretationService service = new FailureInterpretationService(model, new FailureContextSanitizer(),
            factory.getValidator(), new AiProperties(false, "http://localhost:11434", "test-model", 0, Duration.ofSeconds(1), 4096), metrics);

    @AfterEach
    void closeFactory() { factory.close(); }

    @Test
    void redactsSecretsAndPreservesFactualIdentifiersBeforeCallingModel() {
        var captured = new AtomicReference<FailureFactsRequest>();
        when(model.analyze(any())).thenAnswer(invocation -> {
            captured.set(invocation.getArgument(0));
            return validInterpretation();
        });
        var facts = facts("password=private-value Bearer access-value https://user:pass@example.com/path");
        assertThat(service.analyze(facts)).isEqualTo(validInterpretation());
        var sent = captured.get();
        assertThat(sent.jobId()).isEqualTo(facts.jobId());
        assertThat(sent.tasks().getFirst().attempts().getFirst().errorMessage()).contains("[REDACTED]")
                .doesNotContain("private-value", "access-value", "user:pass");
        assertThat(facts.tasks().getFirst().attempts().getFirst().errorMessage()).contains("private-value");
        assertThat(metrics.get("ai.requests").counter().count()).isEqualTo(1);
    }

    @Test
    void rejectsMalformedFactsBeforeModelCall() {
        assertThatThrownBy(() -> service.analyze(null)).isInstanceOf(AiInvalidRequestException.class);
        assertThatThrownBy(() -> service.analyze(new FailureFactsRequest("not-an-id", "FAILED", List.of())))
                .isInstanceOf(AiInvalidRequestException.class);
        verifyNoInteractions(model);
    }

    @Test
    void rejectsOversizedCombinedContextBeforeModelCall() {
        var task = facts("x".repeat(1024)).tasks().getFirst();
        var request = new FailureFactsRequest("00000000-0000-0000-0000-000000000001", "FAILED", Collections.nCopies(30, task));
        assertThatThrownBy(() -> service.analyze(request)).isInstanceOf(AiInvalidRequestException.class)
                .hasMessageContaining("32768");
        verifyNoInteractions(model);
    }

    @Test
    void rejectsEmptyAndOversizedInterpretation() {
        when(model.analyze(any())).thenReturn(new FailureInterpretation("", FailureInterpretation.Confidence.LOW, "Explanation", List.of("Inspect")));
        assertThatThrownBy(() -> service.analyze(facts("Unknown"))).isInstanceOf(AiOutputValidationException.class);
        when(model.analyze(any())).thenReturn(new FailureInterpretation("Cause", FailureInterpretation.Confidence.LOW, "x".repeat(4001), List.of("Inspect")));
        assertThatThrownBy(() -> service.analyze(facts("Unknown"))).isInstanceOf(AiOutputValidationException.class);
    }

    @Test
    void providerUnavailableIsMeasuredAndPropagatedSafely() {
        when(model.analyze(any())).thenThrow(new AiProviderUnavailableException());
        assertThatThrownBy(() -> service.analyze(facts("Unknown"))).isInstanceOf(AiProviderUnavailableException.class);
        assertThat(metrics.get("ai.request.failures").counter().count()).isEqualTo(1);
        assertThat(metrics.get("ai.request.duration").timer().count()).isEqualTo(1);
    }

    private FailureInterpretation validInterpretation() {
        return new FailureInterpretation("Likely dependency failure", FailureInterpretation.Confidence.LOW,
                "Evidence is incomplete.", List.of("Inspect dependency health manually."));
    }

    private FailureFactsRequest facts(String error) {
        var attempt = new AttemptFailureFacts(null, "FAILED", "2026-10-05T00:00:00Z", null, "Unavailable", error, null);
        var task = new TaskFailureFacts("00000000-0000-0000-0000-000000000002", "Orders", "DEAD_LETTER", 1,
                List.of(attempt), "2026-10-05T00:01:00Z");
        return new FailureFactsRequest("00000000-0000-0000-0000-000000000001", "FAILED", List.of(task));
    }
}
