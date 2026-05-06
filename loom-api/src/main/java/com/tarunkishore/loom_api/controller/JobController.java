package com.tarunkishore.loom_api.controller;

import com.loom.common.dto.JobSubmitRequest;
import com.tarunkishore.loom_api.dto.JobResponse;
import com.tarunkishore.loom_api.service.JobService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/jobs")
@RequiredArgsConstructor
public class JobController {

    private final JobService jobService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public JobResponse submit(@Valid @RequestBody JobSubmitRequest request) {
        return JobResponse.from(jobService.submit(request));
    }

    @GetMapping("/{jobId}")
    public JobResponse getJob(@PathVariable UUID jobId) {
        return JobResponse.from(jobService.getJob(jobId));
    }

    @DeleteMapping("/{jobId}")
    public JobResponse cancel(@PathVariable UUID jobId) {
        return JobResponse.from(jobService.cancel(jobId));
    }
}
