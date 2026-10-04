package com.loom.ai.config;

import com.loom.ai.model.WorkflowGenerationModel;
import com.loom.ai.service.AiProviderUnavailableException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "conductor.ai.enabled=false")
class AiApplicationTest {
    @Autowired private AiProperties properties;
    @Autowired private WorkflowGenerationModel model;

    @Test
    void startsWithoutModelServerAndBindsEnvironmentDefaults() {
        assertThat(properties.enabled()).isFalse();
        assertThat(properties.baseUrl()).isEqualTo("http://localhost:11434");
        assertThat(properties.model()).isEqualTo("qwen2.5-coder:7b");
        assertThatThrownBy(() -> model.generate("Demo")).isInstanceOf(AiProviderUnavailableException.class);
    }
}
