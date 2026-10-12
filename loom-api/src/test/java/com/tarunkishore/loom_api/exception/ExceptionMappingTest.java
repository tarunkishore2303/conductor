package com.tarunkishore.loom_api.exception;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.*;
import java.util.NoSuchElementException;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ExceptionMappingTest {
    private MockMvc mvc;
    @BeforeEach void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new TestController())
                .setControllerAdvice(new GlobalExceptionHandler(), new AiExceptionHandler()).build();
    }
    @Test void unavailableProviderIs503() throws Exception {
        mvc.perform(get("/errors/unavailable")).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.detail").value("AI generation is unavailable; try again later"));
    }
    @Test void invalidModelOutputIs422() throws Exception {
        mvc.perform(get("/errors/output")).andExpect(status().isUnprocessableContent());
    }
    @Test void missingResourceIs404() throws Exception {
        mvc.perform(get("/errors/missing")).andExpect(status().isNotFound());
    }
    @Test void conflictIs409() throws Exception {
        mvc.perform(get("/errors/conflict")).andExpect(status().isConflict());
    }
    @Test void invalidRequestIs400() throws Exception {
        mvc.perform(get("/errors/request")).andExpect(status().isBadRequest());
    }
    @Test void beanValidationDoesNotEchoRejectedValue() throws Exception {
        mvc.perform(post("/errors/body").contentType("application/json").content("{\"value\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Request fields are missing or exceed their permitted limits."));
    }
    @Test void malformedJsonDoesNotEchoPayload() throws Exception {
        mvc.perform(post("/errors/body").contentType("application/json").content("{private_payload"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Request body or parameter format is invalid."));
    }
    @Test void invalidUuidDoesNotEchoParameter() throws Exception {
        mvc.perform(get("/errors/id/not-a-valid-id")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Request body or parameter format is invalid."));
    }
    @RestController static class TestController {
        @GetMapping("/errors/{kind}") String error(@PathVariable String kind) {
            throw switch(kind) {
                case "unavailable" -> new AiUnavailableException();
                case "output" -> new AiOutputException("AI output is invalid.");
                case "missing" -> new NoSuchElementException("Run not found.");
                case "conflict" -> new IllegalStateException("Run is not terminal.");
                default -> new IllegalArgumentException("Invalid request.");
            };
        }
        @PostMapping("/errors/body") void body(@Valid @RequestBody Input input) {}
        @GetMapping("/errors/id/{id}") void id(@PathVariable java.util.UUID id) {}
    }
    record Input(@NotBlank String value) {}
}
