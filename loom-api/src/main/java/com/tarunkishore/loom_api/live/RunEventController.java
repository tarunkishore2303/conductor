package com.tarunkishore.loom_api.live;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/jobs")
public class RunEventController {
    private final RunEventStream streams;

    public RunEventController(RunEventStream streams) { this.streams = streams; }

    @GetMapping(value = "/{jobId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> events(@PathVariable UUID jobId) {
        return ResponseEntity.ok().header("Cache-Control", "no-cache, no-transform")
                .header("X-Accel-Buffering", "no").body(streams.subscribe(jobId));
    }
}
