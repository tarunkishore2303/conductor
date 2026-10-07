package com.tarunkishore.loom_api.ai.copilot;

import com.loom.common.security.FailureEvidenceSanitizer;
import com.tarunkishore.loom_api.ai.AiOutputException;
import com.tarunkishore.loom_api.ai.AiUnavailableException;
import com.tarunkishore.loom_api.ai.FailureAnalysisService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Pattern;

@Service
public class CopilotService {
    private static final Logger log = LoggerFactory.getLogger(CopilotService.class);
    private static final int MAX_TOOLS = 4;
    private static final Pattern UUID_PATTERN = Pattern.compile(
            "(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    private static final Pattern MUTATION = Pattern.compile(
            "(?i)^\\s*(?:please\\s+)?(?:(?:can|could|would)\\s+you\\s+)?(?:please\\s+)?"
                    + "(?:retry|replay|cancel|delete|start|stop|schedule|execute|modify|update|mutate)\\b");
    private final CopilotClient client;
    private final CopilotToolRegistry tools;
    private final FailureAnalysisService analyses;
    private final ObjectMapper mapper;
    private final MeterRegistry metrics;
    private final Duration timeout;

    public CopilotService(CopilotClient client, CopilotToolRegistry tools, FailureAnalysisService analyses,
            ObjectMapper mapper, MeterRegistry metrics,
            @Value("${conductor.ai.copilot-timeout:180s}") Duration timeout) {
        if (timeout == null || timeout.isNegative() || timeout.isZero()
                || timeout.compareTo(Duration.ofMinutes(5)) > 0)
            throw new IllegalArgumentException("Copilot timeout must be positive and at most five minutes");
        this.client = client;
        this.tools = tools;
        this.analyses = analyses;
        this.mapper = mapper;
        this.metrics = metrics;
        this.timeout = timeout;
    }

    public Response query(Query request) {
        if (request == null || request.question() == null || request.question().isBlank()
                || request.question().length() > 2000)
            throw new IllegalArgumentException("Question must contain 1 to 2000 characters");
        int scopes = (request.jobId() == null ? 0 : 1) + (request.workflowId() == null ? 0 : 1)
                + (request.analysisId() == null ? 0 : 1);
        if (scopes != 1) throw new IllegalArgumentException("Supply exactly one jobId, workflowId or analysisId");
        var sample = Timer.start(metrics);
        String outcome = "success";
        String correlation = UUID.randomUUID().toString();
        metrics.counter("ai.copilot.requests").increment();
        try {
            if (MUTATION.matcher(request.question()).find())
                return new Response("Conductor Copilot is read-only and cannot retry tasks, change runs, or control execution.",
                        List.of(), List.of(), List.of(), null, Instant.now(), true);
            // A single deadline covers model calls AND read tools; cancellation interrupts HTTP waits.
            var executor = Executors.newVirtualThreadPerTaskExecutor();
            try {
                var work = executor.submit(() -> run(request, System.nanoTime() + timeout.toNanos()));
                try {
                    return work.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
                } catch (TimeoutException | InterruptedException exception) {
                    work.cancel(true);
                    executor.shutdownNow();
                    if (exception instanceof InterruptedException) Thread.currentThread().interrupt();
                    throw new AiUnavailableException();
                } catch (ExecutionException exception) {
                    if (exception.getCause() instanceof RuntimeException runtime) throw runtime;
                    throw new AiUnavailableException();
                }
            } finally {
                // Do not await a database driver that ignores interruption after the deadline.
                executor.shutdownNow();
            }
        } catch (RuntimeException exception) {
            outcome = "failure";
            metrics.counter("ai.copilot.failures").increment();
            throw exception;
        } finally {
            long duration = sample.stop(metrics.timer("ai.copilot.duration", "outcome", outcome));
            log.info("AI operation=copilot correlationId={} outcome={} durationMs={}", correlation, outcome, duration / 1_000_000);
        }
    }

    private Response run(Query request, long deadline) {
        UUID jobId = request.jobId();
        if (request.analysisId() != null) jobId = analyses.getById(request.analysisId()).jobId();
        var scope = new CopilotToolRegistry.Scope(jobId, request.workflowId(), request.analysisId());
        var evidence = new ArrayList<CopilotClient.Evidence>();
        var used = new ArrayList<String>();
        String question = FailureEvidenceSanitizer.sanitize(request.question());
        for (int iteration = 0; iteration <= MAX_TOOLS; iteration++) {
            Duration remaining = Duration.ofNanos(deadline - System.nanoTime());
            if (remaining.isNegative() || remaining.isZero()) throw new AiUnavailableException();
            var input = new CopilotClient.StepRequest(question, scope,
                    iteration == MAX_TOOLS ? List.of() : tools.namesFor(scope), List.copyOf(evidence));
            if (mapper.writeValueAsString(input).length() > 24000)
                throw new AiOutputException("Copilot evidence exceeds the context budget");
            var result = client.step(input, remaining);
            if (result == null || result.step() == null || result.model() == null || result.model().isBlank()
                    || result.model().length() > 128) throw new AiOutputException("Invalid copilot structured response");
            var step = result.step();
            if ("ANSWER".equals(step.action())) {
                if (evidence.isEmpty()) {
                    throw new AiOutputException("Copilot must retrieve execution evidence before answering");
                }
                if (evidence.stream().noneMatch(CopilotClient.Evidence::success)) {
                    return new Response(
                            "I cannot answer from execution evidence because every requested read tool failed. "
                                    + "Inspect the structured tool errors or select a scope with available evidence.",
                            List.copyOf(evidence), List.copyOf(used), List.of(), null, Instant.now(), true);
                }
                validateAnswer(step, evidence);
                return new Response(step.answer(), List.copyOf(evidence), List.copyOf(used),
                        List.copyOf(step.evidenceIds()), result.model(), Instant.now(), true);
            }
            if (!"TOOL".equals(step.action())) throw new AiOutputException("Unsupported copilot action");
            if (iteration == MAX_TOOLS) throw new AiOutputException("Copilot exceeded the four-tool limit");
            var arguments = step.arguments();
            var toolResult = tools.execute(step.tool(), arguments == null ? null
                    : new CopilotToolRegistry.Arguments(arguments.taskId(), arguments.topK()), scope);
            String metricTool = step.tool() != null && tools.namesFor(scope).contains(step.tool()) ? step.tool() : "unsupported";
            metrics.counter("ai.tool.calls", "tool", metricTool).increment();
            if (!toolResult.success()) metrics.counter("ai.tool.failures", "tool", metricTool).increment();
            if (toolResult.success() && step.tool() != null && tools.namesFor(scope).contains(step.tool())) used.add(step.tool());
            if (toolResult.factsJson() == null || toolResult.factsJson().length() > 4096
                    || toolResult.references() == null || toolResult.references().size() > 100)
                throw new AiOutputException("Read tool returned oversized or invalid evidence");
            evidence.add(new CopilotClient.Evidence("E" + (iteration + 1), metricTool, toolResult.success(),
                    toolResult.errorCode(), toolResult.factsJson(), List.copyOf(toolResult.references())));
            log.info("AI operation=copilot_tool tool={} outcome={}", metricTool, toolResult.success() ? "success" : "failure");
        }
        throw new AiOutputException("Copilot did not produce an answer");
    }

    private static void validateAnswer(CopilotClient.Step step, List<CopilotClient.Evidence> evidence) {
        if (step.answer() == null || step.answer().isBlank() || step.answer().length() > 3000
                || step.evidenceIds() == null || step.evidenceIds().size() > MAX_TOOLS
                || new HashSet<>(step.evidenceIds()).size() != step.evidenceIds().size())
            throw new AiOutputException("Invalid copilot answer or citations");
        var allowed = new HashSet<String>();
        var knownEvidence = new HashSet<String>();
        for (var item : evidence) {
            knownEvidence.add(item.evidenceId());
            if (item.success() && step.evidenceIds().contains(item.evidenceId()))
                item.references().forEach(ref -> allowed.add(ref.id().toString().toLowerCase(Locale.ROOT)));
        }
        if (!knownEvidence.containsAll(step.evidenceIds())
                || (evidence.stream().anyMatch(CopilotClient.Evidence::success)
                && evidence.stream().noneMatch(item -> item.success() && step.evidenceIds().contains(item.evidenceId()))))
            throw new AiOutputException("Copilot must cite retrieved evidence");
        var ids = UUID_PATTERN.matcher(step.answer());
        while (ids.find()) if (!allowed.contains(ids.group().toLowerCase(Locale.ROOT)))
            throw new AiOutputException("Copilot answer contains an ungrounded identifier");
    }

    public record Query(String question, UUID jobId, UUID workflowId, UUID analysisId) {}
    public record Response(String answer, List<CopilotClient.Evidence> evidence, List<String> toolsUsed,
                           List<String> evidenceIds, String model, Instant generatedAt, boolean readOnly) {}
}
