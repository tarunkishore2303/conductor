package com.loom.ai.service;

import com.loom.ai.config.AiProperties;
import com.loom.ai.model.GeneratedTaskProposal;
import com.loom.ai.model.GeneratedWorkflowProposal;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class WorkflowGenerationServiceTest {
    private final ProposalShapeValidator validator = new ProposalShapeValidator();
    private final AiProperties properties = new AiProperties(false, "http://localhost:11434", "model", 0, Duration.ofSeconds(1), 100);

    @Test
    void returnsValidProposalAndMeasuresSuccess() {
        var metrics = new SimpleMeterRegistry();
        var proposal = proposal("NOOP", List.of());
        var service = new WorkflowGenerationService(prompt -> proposal, validator, properties, metrics);
        assertThat(service.generate("orders")).isEqualTo(proposal);
        assertThat(metrics.get("ai.requests").counter().count()).isEqualTo(1);
        assertThat(metrics.get("ai.request.duration").timer().count()).isEqualTo(1);
    }
    @Test
    void rejectsUnsupportedTypes() {
        assertThatThrownBy(() -> validator.validate(proposal("SCRIPT", List.of())))
                .isInstanceOf(AiOutputValidationException.class);
    }
    @Test
    void rejectsNullAndOversizedOutput() {
        assertThatThrownBy(() -> validator.validate(null)).isInstanceOf(AiOutputValidationException.class);
        assertThatThrownBy(() -> validator.validate(new GeneratedWorkflowProposal("name", "desc", List.of())))
                .isInstanceOf(AiOutputValidationException.class);
    }
    @Test
    void leavesCycleValidationToDeterministicEngine() {
        assertThatCode(() -> validator.validate(proposal("NOOP", List.of("download")))).doesNotThrowAnyException();
        assertThatCode(() -> validator.validate(proposal("NOOP", List.of("missing")))).doesNotThrowAnyException();
        var task = new GeneratedTaskProposal("duplicate", "Task", "NOOP", List.of(), 1);
        assertThatCode(() -> validator.validate(new GeneratedWorkflowProposal("Demo", "Description", List.of(task, task))))
                .doesNotThrowAnyException();
    }
    @Test
    void recordsProviderFailure() {
        var metrics = new SimpleMeterRegistry();
        var service = new WorkflowGenerationService(prompt -> { throw new AiProviderUnavailableException(); }, validator, properties, metrics);
        assertThatThrownBy(() -> service.generate("orders")).isInstanceOf(AiProviderUnavailableException.class);
        assertThat(metrics.get("ai.request.failures").counter().count()).isEqualTo(1);
    }
    private GeneratedWorkflowProposal proposal(String type, List<String> dependencies) {
        return new GeneratedWorkflowProposal("Orders", "Demo", List.of(new GeneratedTaskProposal("download", "Download", type, dependencies, 1)));
    }
}
