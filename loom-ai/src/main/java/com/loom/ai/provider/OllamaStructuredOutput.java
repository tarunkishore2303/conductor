package com.loom.ai.provider;

import com.loom.ai.service.AiOutputValidationException;
import com.loom.ai.service.AiProviderUnavailableException;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.cfg.CoercionAction;
import tools.jackson.databind.cfg.CoercionInputShape;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.type.LogicalType;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Shared provider mechanics; domain-specific validation stays in its service. */
public class OllamaStructuredOutput {
    private static final Logger log = LoggerFactory.getLogger(OllamaStructuredOutput.class);
    private final ChatModel model;
    private final OllamaChatOptions options;
    private final JsonMapper json = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .withCoercionConfig(LogicalType.Textual, config -> config
                    .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                    .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                    .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail))
            .build();

    public OllamaStructuredOutput(ChatModel model, OllamaChatOptions options) {
        this.model = model;
        this.options = options;
    }

    public <T> T call(String systemPrompt, String context, Class<T> outputType) {
        return call(systemPrompt, context, outputType, new BeanOutputConverter<>(outputType).getJsonSchema());
    }

    public <T> T call(String systemPrompt, String context, Class<T> outputType, String schema) {
        String output;
        try {
            var structuredOptions = options.mutate().outputSchema(schema).build();
            var response = model.call(new Prompt(List.of(new SystemMessage(systemPrompt), new UserMessage(context)), structuredOptions));
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
            T value = json.readValue(output, outputType);
            if (value == null) throw new AiOutputValidationException("AI returned an empty structured response.");
            return value;
        } catch (RuntimeException exception) {
            log.warn("AI structuredOutputRejected exceptionType={}", exception.getClass().getSimpleName());
            throw new AiOutputValidationException("AI produced invalid structured output.");
        }
    }
}
