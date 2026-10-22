package com.loom.ai.provider;

import com.loom.ai.model.GeneratedWorkflowProposal;
import com.loom.capability.TransformTaskConfig;
import com.loom.capability.HttpRequestTaskConfig;
import com.loom.ai.exception.AiOutputValidationException;
import com.loom.ai.exception.AiProviderUnavailableException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
        assertThat(options.getOutputSchema()).contains("required", "tasks", "identifier", "HTTP_REQUEST", "TRANSFORM", "DELAY", "additionalProperties", "captureResponseBody", "inputTaskId", "bodyTaskId");
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
    @ParameterizedTest
    @ValueSource(strings = {"1.5", "\"1\"", "true"})
    void rejectsIncorrectRetryTypes(String retry) {
        String output = "{\"name\":\"Demo\",\"description\":\"Demo\",\"tasks\":[{\"identifier\":\"task\",\"name\":\"Task\",\"type\":\"NOOP\",\"dependencies\":[],\"maxRetries\":" + retry + "}]}";
        when(chatModel.call(any(Prompt.class))).thenReturn(response(output));
        assertThatThrownBy(() -> model.generate("prompt")).isInstanceOf(AiOutputValidationException.class);
    }
    @Test
    void rejectsNumericName() {
        when(chatModel.call(any(Prompt.class))).thenReturn(response("{\"name\":42,\"description\":\"Demo\",\"tasks\":[]}"));
        assertThatThrownBy(() -> model.generate("prompt")).isInstanceOf(AiOutputValidationException.class);
    }
    @Test
    void wrapsProviderFailureWithoutLeakingDetails() {
        when(chatModel.call(any(Prompt.class))).thenThrow(new IllegalStateException("secret provider detail"));
        assertThatThrownBy(() -> model.generate("prompt")).isInstanceOf(AiProviderUnavailableException.class)
                .hasMessageNotContaining("secret");
    }
    @Test
    void parsesTypedConfigurationFromStructuredResponse() {
        when(chatModel.call(any(Prompt.class))).thenReturn(response("""
                {"name":"Wait","description":"Demo","tasks":[{"identifier":"wait","name":"Wait","type":"DELAY","dependencies":[],"maxRetries":0,"config":{"durationMillis":1}}]}
                """));
        assertThat(model.generate("wait").tasks().getFirst().config())
                .isEqualTo(new com.loom.capability.DelayTaskConfig(1));
    }
    @Test
    void rejectsUnknownConfigurationFields() {
        when(chatModel.call(any(Prompt.class))).thenReturn(response("""
                {"name":"Wait","description":"Demo","tasks":[{"identifier":"wait","name":"Wait","type":"DELAY","dependencies":[],"maxRetries":0,"config":{"durationMillis":1,"script":"arbitrary"}}]}
                """));
        assertThatThrownBy(() -> model.generate("wait")).isInstanceOf(AiOutputValidationException.class);
    }

    @Test
    void parsesWholeOutputReferencesWithoutRuntimeOutputContent() {
        when(chatModel.call(any(Prompt.class))).thenReturn(response("""
                {"name":"Pipeline","description":"Demo","tasks":[
                  {"identifier":"select","name":"Select","type":"TRANSFORM","dependencies":["fetch"],"maxRetries":0,
                   "config":{"operation":"JSON_SELECT","inputTaskId":"fetch","fields":["id"]}},
                  {"identifier":"notify","name":"Notify","type":"HTTP_REQUEST","dependencies":["select"],"maxRetries":1,
                   "config":{"method":"POST","url":"https://example.test/notify","timeoutMillis":1000,
                             "contentType":"application/json","bodyTaskId":"select","captureResponseBody":false}}]}
                """));
        var result = model.generate("select and notify");
        assertThat(((TransformTaskConfig) result.tasks().getFirst().config()).inputTaskId())
                .isEqualTo("fetch");
        assertThat(((HttpRequestTaskConfig) result.tasks().get(1).config()).bodyTaskId())
                .isEqualTo("select");
    }

    private ChatResponse response(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }
}
