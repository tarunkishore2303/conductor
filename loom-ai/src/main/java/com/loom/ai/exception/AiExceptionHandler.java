package com.loom.ai.exception;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import jakarta.validation.ConstraintViolationException;

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
    @ExceptionHandler({MethodArgumentNotValidException.class, ConstraintViolationException.class})
    ResponseEntity<ErrorResponse> requestInvalid() {
        return ResponseEntity.badRequest().body(new ErrorResponse("INVALID_REQUEST", "Request fields are missing or exceed their permitted limits."));
    }
    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    ResponseEntity<ErrorResponse> malformedRequest() {
        return ResponseEntity.badRequest().body(new ErrorResponse("INVALID_REQUEST", "Request body or parameter format is invalid."));
    }
    @ExceptionHandler(AiInvalidRequestException.class)
    ResponseEntity<ErrorResponse> invalidRequest(AiInvalidRequestException exception) {
        return ResponseEntity.badRequest().body(new ErrorResponse("INVALID_REQUEST", exception.getMessage()));
    }
    public record ErrorResponse(String code, String message) {}
}
