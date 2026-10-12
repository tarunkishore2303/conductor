package com.tarunkishore.loom_api.validation;

import com.tarunkishore.loom_api.ai.copilot.CopilotService;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

class ExactlyOneScopeTest {
    @Test void acceptsEachSingleScope() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            var id = UUID.randomUUID();
            assertThat(validator.validate(new CopilotService.Query("question", id, null, null))).isEmpty();
            assertThat(validator.validate(new CopilotService.Query("question", null, id, null))).isEmpty();
            assertThat(validator.validate(new CopilotService.Query("question", null, null, id))).isEmpty();
        }
    }

    @Test void rejectsMissingMultipleAndInvalidQuestion() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            var id = UUID.randomUUID();
            assertThat(validator.validate(new CopilotService.Query("question", null, null, null))).hasSize(1);
            assertThat(validator.validate(new CopilotService.Query("question", id, id, null))).hasSize(1);
            assertThat(validator.validate(new CopilotService.Query(" ", id, null, null))).hasSize(1);
            assertThat(validator.validate(new CopilotService.Query("x".repeat(2001), id, null, null))).hasSize(1);
        }
    }
}
