package com.loom.ai.controller;

import com.loom.ai.service.AiOutputValidationException;
import com.loom.ai.service.AiProviderUnavailableException;
import com.loom.ai.service.AiInvalidRequestException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.bind.MethodArgumentNotValidException;

@RestControllerAdvice
public class AiExceptionHandler {
    @ExceptionHandler(AiProviderUnavailableException.class)
    ResponseEntity<ErrorResponse> unavailable(AiProviderUnavailableException exception) {
        return ResponseEntity.status(503).body(new ErrorResponse("AI_UNAVAILABLE", exception.getMessage()));
    }
    @ExceptionHandler(AiOutputValidationException.class)
    ResponseEntity<ErrorResponse> invalid(AiOutputValidationException exception) {
        return ResponseEntity.unprocessableContent().body(new ErrorResponse("AI_OUTPUT_INVALID", exception.getMessage()));
    }
    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ErrorResponse> requestInvalid() {
        return ResponseEntity.badRequest().body(new ErrorResponse("INVALID_REQUEST", "Request fields are missing or exceed their permitted limits."));
    }
    @ExceptionHandler(AiInvalidRequestException.class)
    ResponseEntity<ErrorResponse> invalidRequest(AiInvalidRequestException exception) {
        return ResponseEntity.badRequest().body(new ErrorResponse("INVALID_REQUEST", exception.getMessage()));
    }
    public record ErrorResponse(String code, String message) {}
}
