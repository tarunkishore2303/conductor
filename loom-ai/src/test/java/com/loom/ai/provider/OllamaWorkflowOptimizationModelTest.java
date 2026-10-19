package com.loom.ai.provider;

import com.loom.ai.model.WorkflowOptimizationRequest;
import com.loom.ai.exception.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class OllamaWorkflowOptimizationModelTest {
    private final ChatModel chat = mock(ChatModel.class);
    private final OllamaWorkflowOptimizationModel model = new OllamaWorkflowOptimizationModel(
            new OllamaStructuredOutput(chat, OllamaChatOptions.builder().model("local-model").build()), "Explain supplied candidates only.");

    @Test void usesNativeInterpretationSchemaWithoutWritableWorkflowDefinitions() {
        when(chat.call(any(Prompt.class))).thenReturn(response("{\"overview\":\"Review recorded evidence.\",\"recommendations\":[{\"candidateIds\":[\"O1\"],\"recommendation\":\"Investigate\",\"rationale\":\"Recorded evidence\"}]}"));
        assertThat(model.interpret(request()).recommendations().getFirst().candidateIds()).containsExactly("O1");
        var captured = ArgumentCaptor.forClass(Prompt.class);
        verify(chat).call(captured.capture());
        var options = (OllamaChatOptions) captured.getValue().getOptions();
        assertThat(options.getModel()).isEqualTo("local-model");
        assertThat(options.getOutputSchema()).contains("overview", "recommendations", "candidateIds")
                .doesNotContain("dependsOn", "maxRetries", "taskConfig");
    }

    @ParameterizedTest
    @ValueSource(strings = {"not JSON", "", "null", "{\"overview\":42,\"recommendations\":[]}",
            "{\"overview\":\"Review\",\"recommendations\":[],\"facts\":{}}", "{\"overview\":\"Review\",\"recommendations\":[]} {}"})
    void rejectsMalformedUnknownCoercedAndEmptyOutput(String text) {
        when(chat.call(any(Prompt.class))).thenReturn(response(text));
        assertThatThrownBy(() -> model.interpret(request())).isInstanceOf(AiOutputValidationException.class);
    }

    @Test void providerFailureRemainsSanitized() {
        when(chat.call(any(Prompt.class))).thenThrow(new IllegalStateException("sensitive-provider-detail"));
        assertThatThrownBy(() -> model.interpret(request())).isInstanceOf(AiProviderUnavailableException.class)
                .hasMessageNotContaining("sensitive-provider-detail");
    }
    private WorkflowOptimizationRequest request() {
        return new WorkflowOptimizationRequest("{}", "00000000-0000-0000-0000-000000000001", List.of("O1"));
    }
    private ChatResponse response(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }
}
