package com.loom.ai.provider;

import com.loom.ai.model.GeneratedWorkflowProposal;
import com.loom.ai.model.WorkflowGenerationModel;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.ollama.api.OllamaChatOptions;

public class OllamaWorkflowGenerationModel implements WorkflowGenerationModel {
    private final OllamaStructuredOutput output;
    private final String systemPrompt;

    public OllamaWorkflowGenerationModel(ChatModel model, String systemPrompt, OllamaChatOptions options) {
        this(new OllamaStructuredOutput(model, options), systemPrompt);
    }

    public OllamaWorkflowGenerationModel(OllamaStructuredOutput output, String systemPrompt) {
        this.output = output;
        this.systemPrompt = systemPrompt;
    }

    @Override
    public GeneratedWorkflowProposal generate(String prompt) {
        return output.call(systemPrompt, prompt, GeneratedWorkflowProposal.class);
    }
}
