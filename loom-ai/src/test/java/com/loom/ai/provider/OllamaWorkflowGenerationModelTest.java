package com.loom.ai.provider;

import com.loom.ai.model.GeneratedWorkflowProposal;
import com.loom.ai.service.AiOutputValidationException;
import com.loom.ai.service.AiProviderUnavailableException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class OllamaWorkflowGenerationModelTest {
    private final ChatModel chatModel = mock(ChatModel.class);
    private final OllamaWorkflowGenerationModel model = new OllamaWorkflowGenerationModel(chatModel, "System instructions",
            OllamaChatOptions.builder().model("test-model").numCtx(8192).numPredict(4096).build());

    @Test
    void mapsStructuredResponseAndSendsNativeSchema() {
        when(chatModel.call(any(Prompt.class))).thenReturn(response("""
                {"name":"Orders","description":"Demo","tasks":[{"identifier":"download","name":"Download","type":"NOOP","dependencies":[],"maxRetries":1}]}
                """));
        GeneratedWorkflowProposal result = model.generate("Build orders flow");
        assertThat(result.name()).isEqualTo("Orders");
        assertThat(result.tasks().getFirst().type()).isEqualTo("NOOP");
        var captor = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).call(captor.capture());
        var options = (OllamaChatOptions) captor.getValue().getOptions();
        assertThat(options.getOutputSchema()).contains("required", "tasks", "identifier");
        assertThat(captor.getValue().getInstructions()).hasSize(2);
    }
    @Test
    void rejectsMalformedOutput() {
        when(chatModel.call(any(Prompt.class))).thenReturn(response("this is not JSON"));
        assertThatThrownBy(() -> model.generate("prompt")).isInstanceOf(AiOutputValidationException.class);
    }
    @Test
    void rejectsEmptyOutput() {
        when(chatModel.call(any(Prompt.class))).thenReturn(response(""));
        assertThatThrownBy(() -> model.generate("prompt")).isInstanceOf(AiOutputValidationException.class);
    }
    @Test
    void rejectsProseWrappedJsonRatherThanExtractingIt() {
        when(chatModel.call(any(Prompt.class))).thenReturn(response("```json\n{\"name\":\"Demo\",\"description\":\"Demo\",\"tasks\":[]}\n```"));
        assertThatThrownBy(() -> model.generate("prompt")).isInstanceOf(AiOutputValidationException.class);
    }
    @Test
    void rejectsUnknownFields() {
        when(chatModel.call(any(Prompt.class))).thenReturn(response("{\"name\":\"Demo\",\"description\":\"Demo\",\"tasks\":[],\"execute\":true}"));
        assertThatThrownBy(() -> model.generate("prompt")).isInstanceOf(AiOutputValidationException.class);
    }
    @Test
    void wrapsProviderFailureWithoutLeakingDetails() {
        when(chatModel.call(any(Prompt.class))).thenThrow(new IllegalStateException("secret provider detail"));
        assertThatThrownBy(() -> model.generate("prompt")).isInstanceOf(AiProviderUnavailableException.class)
                .hasMessageNotContaining("secret");
    }
    private ChatResponse response(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }
}
