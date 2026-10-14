package com.tarunkishore.loom_api.config;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import static org.assertj.core.api.Assertions.assertThat;

class AiConfigurationPropertiesTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(PropertiesConfiguration.class);

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties({AiServiceProperties.class, FailureContextProperties.class,
            CopilotProperties.class, IncidentProperties.class})
    static class PropertiesConfiguration {}

    @Test
    void defaultsBindWithoutSecretsOrProvider() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(AiServiceProperties.class).baseUrl()).isEqualTo("http://localhost:8085");
            assertThat(context.getBean(AiServiceProperties.class).timeout()).isEqualTo(Duration.ofSeconds(130));
            assertThat(context.getBean(FailureContextProperties.class).failureContextMaxChars()).isEqualTo(32768);
            assertThat(context.getBean(CopilotProperties.class).copilotTimeout()).isEqualTo(Duration.ofSeconds(180));
            assertThat(context.getBean(IncidentProperties.class).embeddingModel()).isEqualTo("nomic-embed-text:v1.5");
            assertThat(context.getBean(IncidentProperties.class).chatModel()).isEqualTo("qwen2.5-coder:7b");
            assertThat(context.getBean(IncidentProperties.class).incidentSimilarityThreshold()).isEqualTo(0.93);
            assertThat(context.getBean(IncidentProperties.class).incidentAutoIndexEnabled()).isFalse();
        });
    }

    @Test
    void existingPropertyNamesBindToCohesiveRecords() {
        runner.withPropertyValues("conductor.ai.base-url=http://loom-ai:8085",
                "conductor.ai.timeout=190s", "conductor.ai.copilot-timeout=240s",
                "conductor.ai.failure-context-max-chars=16000",
                "conductor.ai.embedding-model=local-embedding", "conductor.ai.chat-model=local-chat",
                "conductor.ai.incident-similarity-threshold=0.97",
                "conductor.ai.incident-auto-index-enabled=true").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(AiServiceProperties.class)).isEqualTo(
                    new AiServiceProperties("http://loom-ai:8085", Duration.ofSeconds(190)));
            assertThat(context.getBean(CopilotProperties.class).copilotTimeout()).isEqualTo(Duration.ofSeconds(240));
            assertThat(context.getBean(FailureContextProperties.class).failureContextMaxChars()).isEqualTo(16000);
            assertThat(context.getBean(IncidentProperties.class)).isEqualTo(
                    new IncidentProperties("local-embedding", "local-chat", 0.97, true));
        });
    }

    @Test
    void zeroTransportTimeoutFailsStartup() { assertInvalid("conductor.ai.timeout=0s"); }
    @Test
    void excessiveTransportTimeoutFailsStartup() { assertInvalid("conductor.ai.timeout=301s"); }
    @Test
    void nonHttpServiceUrlFailsStartup() { assertInvalid("conductor.ai.base-url=file:///tmp/model"); }
    @Test
    void credentialedServiceUrlFailsStartup() { assertInvalid("conductor.ai.base-url=http://user@example.org"); }
    @Test
    void excessiveCopilotDeadlineFailsStartup() { assertInvalid("conductor.ai.copilot-timeout=301s"); }
    @Test
    void zeroCopilotDeadlineFailsStartup() { assertInvalid("conductor.ai.copilot-timeout=0s"); }
    @Test
    void emptyFailureContextBudgetFailsStartup() { assertInvalid("conductor.ai.failure-context-max-chars=0"); }
    @Test
    void excessiveFailureContextBudgetFailsStartup() { assertInvalid("conductor.ai.failure-context-max-chars=65537"); }
    @Test
    void invalidSimilarityFailsStartup() { assertInvalid("conductor.ai.incident-similarity-threshold=1.01"); }
    @Test
    void nonFiniteSimilarityFailsStartup() { assertInvalid("conductor.ai.incident-similarity-threshold=NaN"); }
    @Test
    void blankEmbeddingModelFailsStartup() { assertInvalid("conductor.ai.embedding-model="); }
    @Test
    void blankChatModelFailsStartup() { assertInvalid("conductor.ai.chat-model="); }

    private void assertInvalid(String property) {
        runner.withPropertyValues(property).run(context -> assertThat(context).hasFailed());
    }
}
