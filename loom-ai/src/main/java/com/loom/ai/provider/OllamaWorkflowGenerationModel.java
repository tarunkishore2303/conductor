package com.loom.ai.provider;

import com.loom.ai.model.GeneratedWorkflowProposal;
import com.loom.ai.model.WorkflowGenerationModel;
import com.loom.ai.service.AiOutputValidationException;
import com.loom.ai.service.AiProviderUnavailableException;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import java.util.List;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

public class OllamaWorkflowGenerationModel implements WorkflowGenerationModel {
    private final ChatModel model;
    private final String systemPrompt;
    private final OllamaChatOptions options;
    private final BeanOutputConverter<GeneratedWorkflowProposal> converter = new BeanOutputConverter<>(GeneratedWorkflowProposal.class);
    private final JsonMapper json = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();
    public OllamaWorkflowGenerationModel(ChatModel model, String systemPrompt, OllamaChatOptions options) {
        this.model = model;
        this.systemPrompt = systemPrompt;
        this.options = options;
    }
    @Override
    public GeneratedWorkflowProposal generate(String prompt) {
        String output;
        try {
            var structuredOptions = options.mutate().outputSchema(converter.getJsonSchema()).build();
            var response = model.call(new Prompt(List.of(new SystemMessage(systemPrompt), new UserMessage(prompt)), structuredOptions));
            if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
                throw new AiOutputValidationException("AI returned an empty structured response.");
            }
            output = response.getResult().getOutput().getText();
        } catch (AiOutputValidationException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new AiProviderUnavailableException();
        }
        if (output == null || output.isBlank() || output.length() > 65536) {
            throw new AiOutputValidationException("AI returned empty or oversized structured output.");
        }
        try {
            return json.readValue(output, GeneratedWorkflowProposal.class);
        } catch (RuntimeException exception) {
            throw new AiOutputValidationException("AI produced invalid structured output.");
        }
    }
}
