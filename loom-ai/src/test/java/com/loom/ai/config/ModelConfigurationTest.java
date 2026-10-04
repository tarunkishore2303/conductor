package com.loom.ai.config;

import com.loom.ai.service.AiProviderUnavailableException;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import static org.assertj.core.api.Assertions.*;

class ModelConfigurationTest {
    @Test
    void disabledProviderRequiresNoSecretsOrRunningOllama() throws Exception {
        var properties = new AiProperties(false, "http://localhost:11434", "qwen2.5-coder:7b", 0, Duration.ofSeconds(1), 100);
        var model = new ModelConfiguration().workflowGenerationModel(properties);
        assertThatThrownBy(() -> model.generate("prompt")).isInstanceOf(AiProviderUnavailableException.class);
    }
    @Test
    void rejectsUnboundedConfiguration() {
        assertThatThrownBy(() -> new AiProperties(true, "http://localhost:11434", "model", 0, Duration.ZERO, 4096))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AiProperties(true, "http://localhost:11434", "model", 0, Duration.ofSeconds(1), 10000))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
