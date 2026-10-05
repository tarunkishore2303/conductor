package com.loom.ai.config;

import com.loom.ai.model.FailureInterpretationModel;
import com.loom.ai.model.WorkflowGenerationModel;
import com.loom.ai.provider.OllamaFailureInterpretationModel;
import com.loom.ai.provider.OllamaStructuredOutput;
import com.loom.ai.provider.OllamaWorkflowGenerationModel;
import com.loom.ai.service.AiProviderUnavailableException;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

@Configuration
public class ModelConfiguration {
    @Bean
    WorkflowGenerationModel workflowGenerationModel(AiProperties properties) throws IOException {
        if (!properties.enabled()) {
            return prompt -> { throw new AiProviderUnavailableException(); };
        }
        return new OllamaWorkflowGenerationModel(structuredOutput(properties), prompt("workflow-generation.txt"));
    }

    @Bean
    FailureInterpretationModel failureInterpretationModel(AiProperties properties) throws IOException {
        if (!properties.enabled()) {
            return facts -> { throw new AiProviderUnavailableException(); };
        }
        return new OllamaFailureInterpretationModel(structuredOutput(properties), prompt("failure-interpretation.txt"));
    }

    @Bean
    @ConditionalOnProperty(name = "conductor.ai.enabled", havingValue = "true")
    public OllamaStructuredOutput structuredOutput(AiProperties properties) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.timeout());
        factory.setReadTimeout(properties.timeout());
        var api = OllamaApi.builder().baseUrl(properties.baseUrl())
                .restClientBuilder(RestClient.builder().requestFactory(factory)).build();
        var options = OllamaChatOptions.builder().model(properties.model())
                .temperature(properties.temperature()).numPredict(properties.maxTokens()).numCtx(8192).build();
        var model = OllamaChatModel.builder().ollamaApi(api).options(options)
                .retryTemplate(new RetryTemplate(RetryPolicy.withMaxRetries(0))).build();
        return new OllamaStructuredOutput(model, options);
    }

    private String prompt(String name) throws IOException {
        return new ClassPathResource("prompts/" + name).getContentAsString(StandardCharsets.UTF_8);
    }
}
