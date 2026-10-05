package com.loom.ai.provider;

import com.loom.ai.model.*;
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

class OllamaFailureInterpretationModelTest {
    private final ChatModel chat = mock(ChatModel.class);
    private final OllamaFailureInterpretationModel model = new OllamaFailureInterpretationModel(
            new OllamaStructuredOutput(chat, OllamaChatOptions.builder().model("test-model").numCtx(8192).numPredict(4096).build()),
            "Interpret facts, do not execute.");

    @Test
    void returnsHypothesesOnlyAndSendsNativeSchemaWithConfiguredOptions() {
        when(chat.call(any(Prompt.class))).thenReturn(response("""
                {"likelyCause":"Likely transient dependency failure","confidence":"LOW","explanation":"Evidence is incomplete.","recommendedActions":["Inspect dependency health manually."]}
                """));
        var result = model.analyze(facts());
        assertThat(result.confidence()).isEqualTo(FailureInterpretation.Confidence.LOW);
        var captured = ArgumentCaptor.forClass(Prompt.class);
        verify(chat).call(captured.capture());
        var options = (OllamaChatOptions) captured.getValue().getOptions();
        assertThat(options.getModel()).isEqualTo("test-model");
        assertThat(options.getOutputSchema()).contains("LOW", "MEDIUM", "HIGH", "recommendedActions")
                .doesNotContain("attemptNumber", "deadLetteredAt");
        assertThat(captured.getValue().getInstructions().getLast().getText()).contains("attemptNumber", "deadLetteredAt", "jobId");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"likelyCause\":42,\"confidence\":\"LOW\",\"explanation\":\"Unknown\",\"recommendedActions\":[\"Inspect\"]}",
            "{\"likelyCause\":\"Unknown\",\"confidence\":\"CERTAIN\",\"explanation\":\"Unknown\",\"recommendedActions\":[\"Inspect\"]}",
            "{\"likelyCause\":\"Unknown\",\"confidence\":\"LOW\",\"explanation\":\"Unknown\",\"recommendedActions\":[42]}",
            "{\"likelyCause\":\"Unknown\",\"confidence\":\"LOW\",\"explanation\":\"Unknown\",\"recommendedActions\":[\"Inspect\"],\"facts\":{\"attempts\":7}}",
            "```json\n{}\n```", "null", ""})
    void rejectsMalformedTypesAndModelAuthoredFacts(String output) {
        when(chat.call(any(Prompt.class))).thenReturn(response(output));
        assertThatThrownBy(() -> model.analyze(facts())).isInstanceOf(AiOutputValidationException.class);
    }

    @Test
    void providerFailureHasNoRawDetails() {
        when(chat.call(any(Prompt.class))).thenThrow(new IllegalStateException("Bearer private-token"));
        assertThatThrownBy(() -> model.analyze(facts())).isInstanceOf(AiProviderUnavailableException.class)
                .hasMessageNotContaining("private-token");
    }

    private ChatResponse response(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }

    private FailureFactsRequest facts() {
        var attempt = new AttemptFailureFacts(null, "FAILED", "2026-10-05T00:00:00Z", null,
                "DependencyUnavailable", "HTTP 503", null);
        var task = new TaskFailureFacts("00000000-0000-0000-0000-000000000002", "Orders", "DEAD_LETTER", 1,
                List.of(attempt), "2026-10-05T00:01:00Z");
        return new FailureFactsRequest("00000000-0000-0000-0000-000000000001", "FAILED", List.of(task));
    }
}
