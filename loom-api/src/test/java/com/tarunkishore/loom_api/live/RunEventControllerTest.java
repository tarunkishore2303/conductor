package com.tarunkishore.loom_api.live;

import com.tarunkishore.loom_api.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.NoSuchElementException;
import java.util.UUID;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class RunEventControllerTest {
    @Test void existingRunUsesUnbufferedSseAndUnknownRunReturns404() throws Exception {
        var stream = mock(RunEventStream.class);
        var mvc = MockMvcBuilders.standaloneSetup(new RunEventController(stream))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        UUID id = UUID.randomUUID();
        when(stream.subscribe(id)).thenReturn(new SseEmitter(300000L));
        mvc.perform(get("/api/v1/jobs/{id}/events", id)).andExpect(status().isOk())
                .andExpect(request().asyncStarted()).andExpect(header().string("X-Accel-Buffering", "no"))
                .andExpect(header().string("Cache-Control", "no-cache, no-transform"));
        when(stream.subscribe(id)).thenThrow(new NoSuchElementException("Run not found"));
        mvc.perform(get("/api/v1/jobs/{id}/events", id)).andExpect(status().isNotFound());
    }
}
