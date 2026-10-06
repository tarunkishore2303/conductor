package com.loom.ai.service;

import com.loom.ai.config.AiProperties;
import com.loom.ai.model.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class IncidentSynthesisServiceTest {
    private static final String ANALYSIS = "00000000-0000-0000-0000-000000000001";
    private static final String INCIDENT = "00000000-0000-0000-0000-000000000002";
    private static final String JOB = "00000000-0000-0000-0000-000000000003";
    private static final String TASK = "00000000-0000-0000-0000-000000000004";
    private final ValidatorFactory factory = Validation.buildDefaultValidatorFactory();
    private final IncidentSynthesisModel model = mock(IncidentSynthesisModel.class);
    private final SimpleMeterRegistry metrics = new SimpleMeterRegistry();
    private final IncidentSynthesisService service = new IncidentSynthesisService(model, factory.getValidator(),
            new AiProperties(false, "http://localhost:11434", "local-model", 0, Duration.ofSeconds(1), 4096), metrics);

    @AfterEach
    void closeFactory() { factory.close(); }

    @Test
    void emptyMatchesNeverInvokeModelOrClaimHistoricalSimilarity() {
        var result = service.synthesize(new IncidentSynthesisRequest(ANALYSIS, "Current error", List.of()));
        assertThat(result.citedIncidentIds()).isEmpty();
        assertThat(result.explanation()).contains("No similar historical incidents");
        verifyNoInteractions(model);
    }

    @Test
    void validCitationsAreReturnedAndEvidenceIsSanitized() {
        var request = request("password=secret", .91);
        when(model.synthesize(any())).thenAnswer(invocation -> {
            IncidentSynthesisRequest evidence = invocation.getArgument(0);
            assertThat(evidence.matches().getFirst().documentText()).isEqualTo("password=[REDACTED]");
            return new IncidentSynthesis("The retrieved incident suggests a similar transient failure.", List.of(INCIDENT));
        });
        assertThat(service.synthesize(request).citedIncidentIds()).containsExactly(INCIDENT);
        assertThat(metrics.get("ai.requests").counter().count()).isEqualTo(1);
    }

    @Test
    void rejectsInventedEmptyAndDuplicateCitations() {
        for (var citations : List.of(List.of(ANALYSIS), List.<String>of(), List.of(INCIDENT, INCIDENT))) {
            when(model.synthesize(any())).thenReturn(new IncidentSynthesis("Claim", citations));
            assertThatThrownBy(() -> service.synthesize(request("Evidence", .91))).isInstanceOf(AiOutputValidationException.class);
        }
        assertThat(metrics.get("ai.request.failures").counter().count()).isEqualTo(3);
    }

    @Test
    void directSynthesisRequestRedactsQuotedSecretsInCurrentAndHistoricalEvidence() {
        var request = new IncidentSynthesisRequest(ANALYSIS, "{\"token\":\"current-private-value\"}",
                List.of(new IncidentMatch(INCIDENT, JOB, TASK, "{\"password\":\"historical-private-value\"}", .91)));
        when(model.synthesize(any())).thenAnswer(invocation -> {
            IncidentSynthesisRequest evidence = invocation.getArgument(0);
            assertThat(evidence.currentIncident()).contains("[REDACTED]").doesNotContain("current-private-value");
            assertThat(evidence.matches().getFirst().documentText()).contains("[REDACTED]").doesNotContain("historical-private-value");
            return new IncidentSynthesis("Retrieved evidence may describe a similar failure.", List.of(INCIDENT));
        });
        assertThat(service.synthesize(request).citedIncidentIds()).containsExactly(INCIDENT);
    }

    @Test
    void rejectsInvalidScoreAndDuplicateInputIdsBeforeModel() {
        assertThatThrownBy(() -> service.synthesize(request("Evidence", Double.NaN))).isInstanceOf(AiInvalidRequestException.class);
        assertThatThrownBy(() -> service.synthesize(request("Evidence", 1.01))).isInstanceOf(AiInvalidRequestException.class);
        var match = request("Evidence", .9).matches().getFirst();
        assertThatThrownBy(() -> service.synthesize(new IncidentSynthesisRequest(ANALYSIS, "Current", List.of(match, match))))
                .isInstanceOf(AiInvalidRequestException.class);
        verifyNoInteractions(model);
    }

    @Test
    void providerOutageIsSafeAndMeasured() {
        when(model.synthesize(any())).thenThrow(new AiProviderUnavailableException());
        assertThatThrownBy(() -> service.synthesize(request("Evidence", .9))).isInstanceOf(AiProviderUnavailableException.class);
        assertThat(metrics.get("ai.request.failures").counter().count()).isEqualTo(1);
    }

    private IncidentSynthesisRequest request(String text, double similarity) {
        return new IncidentSynthesisRequest(ANALYSIS, "Current error", List.of(new IncidentMatch(INCIDENT, JOB, TASK, text, similarity)));
    }
}
