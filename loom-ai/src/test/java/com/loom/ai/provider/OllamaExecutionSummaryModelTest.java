package com.loom.ai.provider;

import com.loom.ai.model.ExecutionSummaryRequest;
import com.loom.ai.service.AiOutputValidationException;
import com.loom.ai.service.AiProviderUnavailableException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class OllamaExecutionSummaryModelTest {
    private final ChatModel chat = mock(ChatModel.class);
    private final OllamaExecutionSummaryModel model = new OllamaExecutionSummaryModel(new OllamaStructuredOutput(chat,
            OllamaChatOptions.builder().model("local-model").numCtx(8192).numPredict(4096).build()), "Interpret recorded evidence only.");

    @Test
    void mapsInterpretationWithNativeSchemaAndConfiguredModel() {
        when(chat.call(any(Prompt.class))).thenReturn(response("{\"overview\":\"The run completed.\",\"notableEvents\":[],\"operationalNotes\":[]}"));
        assertThat(model.summarize(request()).overview()).isEqualTo("The run completed.");
        var captured = ArgumentCaptor.forClass(Prompt.class);
        verify(chat).call(captured.capture());
        var options = (OllamaChatOptions) captured.getValue().getOptions();
        assertThat(options.getModel()).isEqualTo("local-model");
        assertThat(options.getOutputSchema()).contains("overview", "notableEvents", "operationalNotes")
                .doesNotContain("taskCount", "recordedRetries", "durationMillis");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "not JSON",
            "{\"overview\":42,\"notableEvents\":[],\"operationalNotes\":[]}",
            "{\"overview\":\"Completed\",\"notableEvents\":[42],\"operationalNotes\":[]}",
            "{\"overview\":\"Completed\",\"notableEvents\":[],\"operationalNotes\":[],\"taskCount\":123}",
            "{\"overview\":\"Completed\",\"notableEvents\":[],\"operationalNotes\":[],\"facts\":{\"recordedRetries\":7}}"})
    void rejectsMalformedOutputAndModelAuthoredStatistics(String output) {
        when(chat.call(any(Prompt.class))).thenReturn(response(output));
        assertThatThrownBy(() -> model.summarize(request())).isInstanceOf(AiOutputValidationException.class);
    }

    @Test
    void providerExceptionDoesNotExposePrivateDetails() {
        when(chat.call(any(Prompt.class))).thenThrow(new IllegalStateException("password=private-value"));
        assertThatThrownBy(() -> model.summarize(request())).isInstanceOf(AiProviderUnavailableException.class)
                .hasMessageNotContaining("private-value");
    }

    private ExecutionSummaryRequest request() {
        return new ExecutionSummaryRequest("{}", "00000000-0000-0000-0000-000000000001", List.of(), null);
    }

    private ChatResponse response(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }
}
