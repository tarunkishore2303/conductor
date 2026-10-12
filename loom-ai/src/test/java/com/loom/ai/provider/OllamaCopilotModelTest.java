package com.loom.ai.provider;

import com.loom.ai.model.CopilotRequest;
import com.loom.ai.service.AiOutputValidationException;
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
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class OllamaCopilotModelTest {
    private final ChatModel chat = mock(ChatModel.class);
    private final OllamaCopilotModel model = new OllamaCopilotModel(new OllamaStructuredOutput(chat,
            OllamaChatOptions.builder().model("local-model").numCtx(8192).numPredict(4096).build()), "Read-only tools only.");

    @Test
    void mapsStructuredToolProposalWithNativeSchema() {
        when(chat.call(any(Prompt.class))).thenReturn(response("{\"action\":\"TOOL\",\"tool\":\"getRun\",\"arguments\":{},\"evidenceIds\":[]}"));
        var result = model.step(request());
        assertThat(result.action()).isEqualTo("TOOL");
        assertThat(result.tool()).isEqualTo("getRun");
        var captured = ArgumentCaptor.forClass(Prompt.class);
        verify(chat).call(captured.capture());
        var options = (OllamaChatOptions) captured.getValue().getOptions();
        assertThat(options.getModel()).isEqualTo("local-model");
        assertThat(options.getOutputSchema()).contains("action", "evidenceIds", "taskId", "topK");
        var schema = new JsonMapper().readTree(options.getOutputSchema());
        assertThat(schema.has("oneOf")).isFalse();
        var tool = schema;
        assertThat(tool.get("properties").get("action").get("enum").toString()).isEqualTo("[\"TOOL\"]");
        assertThat(tool.get("properties").has("answer")).isFalse();
        assertThat(tool.get("properties").get("tool").get("enum").toString()).isEqualTo("[\"getRun\"]");
        assertThat(tool.get("properties").get("evidenceIds").get("maxItems").intValue()).isZero();
        assertThat(tool.get("additionalProperties").booleanValue()).isFalse();
        assertThat(captured.getValue().getInstructions().getLast().getText()).contains("allowedTools", "scope");
    }

    @Test
    void successfulEvidenceAllowsDisjointToolAndAnswerBranches() {
        when(chat.call(any(Prompt.class))).thenReturn(response("{\"action\":\"ANSWER\",\"answer\":\"The evidence reports failure.\",\"evidenceIds\":[\"E1\"]}"));
        var request = new CopilotRequest("What happened?", request().scope(), List.of("getRun"), List.of(
                new CopilotRequest.Evidence("E1", "getRun", true, null, "{\"status\":\"FAILED\"}", List.of())));
        assertThat(model.step(request).action()).isEqualTo("ANSWER");
        var captured = ArgumentCaptor.forClass(Prompt.class);
        verify(chat).call(captured.capture());
        var schema = new JsonMapper().readTree(((OllamaChatOptions) captured.getValue().getOptions()).getOutputSchema());
        assertThat(schema.get("oneOf").size()).isEqualTo(2);
        var tool = schema.get("oneOf").get(0);
        var answer = schema.get("oneOf").get(1);
        assertThat(tool.get("properties").has("answer")).isFalse();
        assertThat(tool.get("properties").get("tool").get("enum").toString()).isEqualTo("[\"getRun\"]");
        assertThat(tool.get("properties").get("evidenceIds").get("maxItems").intValue()).isZero();
        assertThat(tool.get("additionalProperties").booleanValue()).isFalse();
        assertThat(answer.get("properties").has("tool")).isFalse();
        assertThat(answer.get("properties").has("arguments")).isFalse();
        assertThat(answer.get("properties").get("action").get("enum").toString()).isEqualTo("[\"ANSWER\"]");
        assertThat(answer.get("properties").get("evidenceIds").get("items").get("enum").toString()).isEqualTo("[\"E1\"]");
        assertThat(answer.get("additionalProperties").booleanValue()).isFalse();
    }

    @Test
    void finalStepSchemaOnlyAllowsAnswerWithActualEvidenceLabels() {
        when(chat.call(any(Prompt.class))).thenReturn(response("{\"action\":\"ANSWER\",\"answer\":\"The evidence reports failure.\",\"evidenceIds\":[\"E1\"]}"));
        var request = new CopilotRequest("What happened?", request().scope(), List.of(), List.of(
                new CopilotRequest.Evidence("E1", "getRun", true, null, "{\"status\":\"FAILED\"}", List.of())));
        assertThat(model.step(request).action()).isEqualTo("ANSWER");
        var captured = ArgumentCaptor.forClass(Prompt.class);
        verify(chat).call(captured.capture());
        var schema = new JsonMapper().readTree(((OllamaChatOptions) captured.getValue().getOptions()).getOutputSchema());
        assertThat(schema.has("oneOf")).isFalse();
        assertThat(schema.get("properties").get("action").get("enum").toString()).isEqualTo("[\"ANSWER\"]");
        var citations = schema.get("properties").get("evidenceIds");
        assertThat(citations.get("items").get("enum").toString()).isEqualTo("[\"E1\"]");
        assertThat(citations.get("minItems").intValue()).isEqualTo(1);
        assertThat(schema.get("additionalProperties").booleanValue()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "not JSON",
            "{\"action\":42,\"evidenceIds\":[]}",
            "{\"action\":\"TOOL\",\"tool\":\"getRun\",\"arguments\":{\"jobId\":\"changed-scope\"},\"evidenceIds\":[]}",
            "{\"action\":\"TOOL\",\"tool\":\"getSimilarIncidents\",\"arguments\":{\"topK\":1.5},\"evidenceIds\":[]}",
            "{\"action\":\"ANSWER\",\"answer\":\"Done\",\"evidenceIds\":[42]}"})
    void rejectsMalformedTypesAndAttemptsToChangeScope(String output) {
        when(chat.call(any(Prompt.class))).thenReturn(response(output));
        assertThatThrownBy(() -> model.step(request())).isInstanceOf(AiOutputValidationException.class);
    }

    private CopilotRequest request() {
        return new CopilotRequest("How many retries?", new CopilotRequest.Scope("00000000-0000-0000-0000-000000000001", null, null),
                List.of("getRun"), List.of());
    }

    private ChatResponse response(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }
}
