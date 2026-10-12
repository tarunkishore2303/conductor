package com.loom.ai.exception;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class AiExceptionHandlerTest {
    private final AiExceptionHandler handler = new AiExceptionHandler();
    @Test void providerFailurePreservesInternalEnvelope() {
        var response = handler.unavailable(new AiProviderUnavailableException());
        assertThat(response.getStatusCode().value()).isEqualTo(503);
        assertThat(response.getBody()).isEqualTo(new AiExceptionHandler.ErrorResponse("AI_UNAVAILABLE", "AI provider is unavailable. Try again later."));
    }
    @Test void malformedOutputPreserves422() {
        var response = handler.invalid(new AiOutputValidationException("Invalid structured output."));
        assertThat(response.getStatusCode().value()).isEqualTo(422);
        assertThat(response.getBody().code()).isEqualTo("AI_OUTPUT_INVALID");
    }
    @Test void invalidDomainRequestPreserves400() {
        var response = handler.invalidRequest(new AiInvalidRequestException("Exactly one scope is required."));
        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody().code()).isEqualTo("INVALID_REQUEST");
    }
    @Test void requestValidationDoesNotIncludeRejectedFields() {
        assertThat(handler.requestInvalid().getBody().message())
                .isEqualTo("Request fields are missing or exceed their permitted limits.");
    }
    @Test void malformedJsonUsesSafeInternalEnvelope() {
        var response = handler.malformedRequest();
        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody()).isEqualTo(new AiExceptionHandler.ErrorResponse("INVALID_REQUEST", "Request body or parameter format is invalid."));
    }
}
