package com.loom.ai.provider;

import com.loom.ai.model.IncidentSynthesisRequest;
import com.loom.ai.service.AiOutputValidationException;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class OllamaIncidentSynthesisModelTest {
    private final ChatModel chat = mock(ChatModel.class);
    private final OllamaIncidentSynthesisModel model = new OllamaIncidentSynthesisModel(new OllamaStructuredOutput(chat,
            OllamaChatOptions.builder().model("local-model").build()), "Use retrieved evidence only.");

    @Test
    void malformedOutputIsRejectedBySharedStrictMapper() {
        var request = new IncidentSynthesisRequest("00000000-0000-0000-0000-000000000001", "Current incident", List.of());
        for (String output : List.of("not JSON", "{\"explanation\":42,\"citedIncidentIds\":[]}", "{\"explanation\":\"Claim\",\"citedIncidentIds\":[42]}")) {
            when(chat.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage(output)))));
            assertThatThrownBy(() -> model.synthesize(request)).isInstanceOf(AiOutputValidationException.class);
        }
    }
}
