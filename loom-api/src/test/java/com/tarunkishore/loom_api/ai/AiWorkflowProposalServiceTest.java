package com.tarunkishore.loom_api.ai;

import com.tarunkishore.loom_api.repository.AiWorkflowProposalRepository;
import com.tarunkishore.loom_api.service.DAGValidator;
import com.tarunkishore.loom_api.service.WorkflowService;
import com.loom.common.model.WorkflowTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
class AiWorkflowProposalServiceTest {
    AiWorkflowClient client = mock(AiWorkflowClient.class);
    AiWorkflowProposalRepository repo = mock(AiWorkflowProposalRepository.class);
    WorkflowService workflows = mock(WorkflowService.class);
    WorkflowProposalValidator validator = new WorkflowProposalValidator(new DAGValidator());
    AiWorkflowProposalService service = new AiWorkflowProposalService(client, validator, repo, workflows, JsonMapper.builder().build());
    GeneratedWorkflow valid = new GeneratedWorkflow("orders", "demo", List.of(
            task("download", List.of()), task("left", List.of("download")),
            task("right", List.of("download")), task("join", List.of("left", "right"))));

    static GeneratedWorkflow.Task task(String id, List<String> deps) {
        return new GeneratedWorkflow.Task(id, id, "NOOP", deps, 2);
    }

    @BeforeEach
    void setup() {
        when(client.generate(anyString())).thenReturn(new AiWorkflowClient.Generation(valid, "test-model"));
        when(repo.save(any())).thenAnswer(i -> i.getArgument(0));
    }

    @Test
    void validPreviewMapsAndNeverPersistsWorkflow() {
        var preview = service.generate("orders");
        assertThat(preview.validation().valid()).isTrue();
        assertThat(preview.workflow().tasks()).hasSize(4);
        verifyNoInteractions(workflows);
    }

    @Test
    void cycleRemainsInvalidProposalWithoutWorkflow() {
        when(client.generate(anyString())).thenReturn(new AiWorkflowClient.Generation(
                new GeneratedWorkflow("cycle", null, List.of(task("A", List.of("C")), task("B", List.of("A")), task("C", List.of("B")))), "test"));
        var preview = service.generate("cycle");
        assertThat(preview.validation().valid()).isFalse();
        assertThat(preview.validation().errors()).anyMatch(e -> e.contains("Cycle"));
        verify(repo).save(any());
        verifyNoInteractions(workflows);
    }

    @Test
    void missingDependencyUsesExistingValidator() {
        when(client.generate(anyString())).thenReturn(new AiWorkflowClient.Generation(
                new GeneratedWorkflow("missing", null, List.of(task("A", List.of("missing")))), "test"));
        assertThat(service.generate("missing").validation().errors()).anyMatch(e -> e.contains("unknown"));
    }

    @Test
    void duplicatesUseExistingValidator() {
        when(client.generate(anyString())).thenReturn(new AiWorkflowClient.Generation(
                new GeneratedWorkflow("duplicate", null, List.of(task("A", List.of()), task("A", List.of()))), "test"));
        assertThat(service.generate("duplicate").validation().errors()).anyMatch(e -> e.contains("Duplicate"));
    }

    @Test
    void unsupportedTypeAndMalformedShapeAreRejected() {
        assertThatThrownBy(() -> validator.map(
                new GeneratedWorkflow("x", null, List.of(new GeneratedWorkflow.Task("A", "A", "SCRIPT", List.of(), 0)))))
                .isInstanceOf(AiOutputException.class);
        assertThatThrownBy(() -> validator.map(null)).isInstanceOf(AiOutputException.class);
        assertThatThrownBy(() -> validator.map(
                new GeneratedWorkflow("x", null, List.of())))
                .isInstanceOf(AiOutputException.class);
        assertThatThrownBy(() -> validator.map(
                new GeneratedWorkflow("x", null, List.of(new GeneratedWorkflow.Task("A", "A", "NOOP", null, 0)))))
                .isInstanceOf(AiOutputException.class);
    }

    @Test
    void unavailableLeavesAllPersistenceUntouched() {
        when(client.generate(anyString())).thenThrow(new AiUnavailableException());
        assertThatThrownBy(() -> service.generate("orders")).isInstanceOf(AiUnavailableException.class);
        verifyNoInteractions(repo, workflows);
    }

    @Test
    void invalidInputDoesNotCallModel() {
        assertThatThrownBy(() -> service.generate(" ")).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(client, repo, workflows);
    }

    private AiWorkflowProposal generatedEntity() {
        service.generate("orders");
        var captor = ArgumentCaptor.forClass(AiWorkflowProposal.class);
        verify(repo).save(captor.capture());
        var p = captor.getValue();
        when(repo.findForApproval(p.getId())).thenReturn(Optional.of(p));
        return p;
    }

    @Test
    void approvalUsesNormalWorkflowService() {
        var p = generatedEntity();
        var template = new WorkflowTemplate();
        template.setId(UUID.randomUUID());
        template.setName("orders");
        when(workflows.createTemplate(any())).thenReturn(template);
        when(workflows.parseTasks(template)).thenReturn(List.of());
        var response = service.approve(p.getId());
        assertThat(response.id()).isEqualTo(template.getId());
        assertThat(p.getApprovedWorkflowId()).isEqualTo(template.getId());
        verify(workflows).createTemplate(any());
        assertThatThrownBy(() -> service.approve(p.getId()))
                .isInstanceOf(IllegalStateException.class);
        verify(workflows, times(1)).createTemplate(any());
    }

    @Test
    void expiredProposalCannotBeApproved() {
        var p = generatedEntity();
        p.setExpiresAt(Instant.now().minusSeconds(1));
        assertThatThrownBy(() -> service.approve(p.getId()))
                .isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(workflows);
    }

    @Test
    void approvalRevalidatesStoredDag() {
        var p = generatedEntity();
        p.setWorkflowJson("{\"name\":\"bad\",\"tasks\":[{\"identifier\":\"A\",\"name\":\"A\",\"type\":\"NOOP\",\"dependencies\":[\"A\"],\"maxRetries\":0}]}");
        assertThatThrownBy(() -> service.approve(p.getId()))
                .isInstanceOf(AiOutputException.class);
        verifyNoInteractions(workflows);
    }
}
