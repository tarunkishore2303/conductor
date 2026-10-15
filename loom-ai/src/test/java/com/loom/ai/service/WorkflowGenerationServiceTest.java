package com.loom.ai.service;

import com.loom.ai.validation.ProposalShapeValidator;

import com.loom.ai.exception.AiProviderUnavailableException;

import com.loom.ai.exception.AiOutputValidationException;

import com.loom.ai.config.AiProperties;
import com.loom.ai.model.GeneratedTaskProposal;
import com.loom.ai.model.GeneratedWorkflowProposal;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.List;
import com.loom.capability.*;
import static org.assertj.core.api.Assertions.*;

class WorkflowGenerationServiceTest {
    private final ProposalShapeValidator validator = new ProposalShapeValidator(capabilities());
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
    @Test
    void acceptsOnlyValidTypedCapabilities() {
        var tasks = List.of(
                new GeneratedTaskProposal("fetch", "Fetch", "HTTP_REQUEST", List.of(), 1,
                        new HttpRequestTaskConfig("GET", "https://example.test/orders", 1000, null, null)),
                new GeneratedTaskProposal("wait", "Wait", "DELAY", List.of("fetch"), 0, new DelayTaskConfig(0)),
                new GeneratedTaskProposal("select", "Select", "TRANSFORM", List.of("wait"), 0,
                        new TransformTaskConfig("JSON_SELECT", "{}", List.of("id"), java.util.Map.of())));
        assertThatCode(() -> validator.validate(new GeneratedWorkflowProposal("Real", "static inputs", tasks)))
                .doesNotThrowAnyException();
    }
    @Test
    void rejectsWrongConfigurationType() {
        var task = new GeneratedTaskProposal("wait", "Wait", "DELAY", List.of(), 0, new NoOpTaskConfig());
        assertThatThrownBy(() -> validator.validate(new GeneratedWorkflowProposal("Demo", "desc", List.of(task))))
                .isInstanceOf(AiOutputValidationException.class);
    }
    @Test
    void rejectsMissingConfigurationForRealCapability() {
        var task = new GeneratedTaskProposal("wait", "Wait", "DELAY", List.of(), 0, null);
        assertThatThrownBy(() -> validator.validate(new GeneratedWorkflowProposal("Demo", "desc", List.of(task))))
                .isInstanceOf(AiOutputValidationException.class);
    }

    private static TaskCapabilityValidators capabilities() {
        var properties = TaskProperties.defaults();
        return new TaskCapabilityValidators(List.of(new NoOpTaskConfigValidator(),
                new DelayTaskConfigValidator(properties), new HttpRequestTaskConfigValidator(properties),
                new TransformTaskConfigValidator(properties)));
    }

}
