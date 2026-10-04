package com.tarunkishore.loom_api.ai;

import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
@RestControllerAdvice
public class AiExceptionHandler {
    @ExceptionHandler(AiUnavailableException.class)
    public ProblemDetail unavailable(AiUnavailableException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
    }
    @ExceptionHandler(AiOutputException.class)
    public ProblemDetail invalid(AiOutputException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_CONTENT, e.getMessage());
    }
}
