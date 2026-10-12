package com.loom.ai.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import static org.assertj.core.api.Assertions.assertThat;

class FailureContextPropertiesTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(PropertiesConfiguration.class);

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(FailureContextProperties.class)
    static class PropertiesConfiguration {}

    @Test
    void defaultMatchesApiBudget() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(FailureContextProperties.class).failureContextMaxChars()).isEqualTo(32768);
        });
    }

    @Test
    void existingPropertyNameBinds() {
        runner.withPropertyValues("conductor.ai.failure-context-max-chars=16000").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(FailureContextProperties.class).failureContextMaxChars()).isEqualTo(16000);
        });
    }

    @Test
    void zeroBudgetFailsStartup() {
        runner.withPropertyValues("conductor.ai.failure-context-max-chars=0")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void excessiveBudgetFailsStartup() {
        runner.withPropertyValues("conductor.ai.failure-context-max-chars=65537")
                .run(context -> assertThat(context).hasFailed());
    }
}
