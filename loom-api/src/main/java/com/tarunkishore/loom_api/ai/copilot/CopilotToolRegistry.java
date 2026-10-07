package com.tarunkishore.loom_api.ai.copilot;

import com.tarunkishore.loom_api.ai.AiOutputException;
import com.tarunkishore.loom_api.ai.AiUnavailableException;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;

/** Fixed read-only allowlist with code-owned references and explicit scope enforcement. */
@Component
public class CopilotToolRegistry {
    private static final List<String> RUN_TOOLS = List.of("getRun", "getTasks", "getFailedTasks",
            "getTaskAttempts", "getFailureAnalysis", "getSimilarIncidents");
    public static final Set<String> ALLOWED_NAMES = Set.of("getRun", "getTasks", "getFailedTasks",
            "getTaskAttempts", "getFailureAnalysis", "getSimilarIncidents", "getWorkflow");
    private final CopilotReadService reads;
    private final ObjectMapper mapper;

    public CopilotToolRegistry(CopilotReadService reads, ObjectMapper mapper) {
        this.reads = reads;
        this.mapper = mapper;
    }

    public static List<String> namesFor(Scope scope) {
        if (scope == null) return List.of();
        if (scope.jobId() != null && scope.workflowId() == null) return RUN_TOOLS;
        if (scope.workflowId() != null && scope.jobId() == null && scope.analysisId() == null) {
            return List.of("getWorkflow");
        }
        return List.of();
    }

    public ToolResult execute(String tool, Arguments arguments, Scope scope) {
        if (tool == null || !ALLOWED_NAMES.contains(tool)) return error(tool, "UNSUPPORTED_TOOL");
        if (!namesFor(scope).contains(tool)) return error(tool, "OUT_OF_SCOPE");
        var args = arguments == null ? new Arguments(null, null) : arguments;
        if (args.taskId() != null && !tool.equals("getTaskAttempts")) return error(tool, "INVALID_ARGUMENT");
        if (args.topK() != null && !tool.equals("getSimilarIncidents")) return error(tool, "INVALID_ARGUMENT");
        if (tool.equals("getTaskAttempts") && args.taskId() == null) return error(tool, "INVALID_ARGUMENT");
        if (args.topK() != null && (args.topK() < 1 || args.topK() > 5)) return error(tool, "INVALID_ARGUMENT");
        try {
            Object facts;
            List<Reference> references;
            switch (tool) {
                case "getRun" -> {
                    var result = reads.getRun(scope.jobId());
                    facts = result;
                    var refs = new java.util.ArrayList<Reference>();
                    refs.add(runReference(result.jobId()));
                    result.slowestObservedTasks().forEach(t -> refs.add(taskReference(t.taskId(), result.jobId())));
                    references = List.copyOf(refs);
                }
                case "getTasks", "getFailedTasks" -> {
                    var result = reads.getTasks(scope.jobId(), tool.equals("getFailedTasks"));
                    facts = result;
                    var refs = new java.util.ArrayList<Reference>();
                    refs.add(runReference(result.jobId()));
                    result.tasks().forEach(t -> refs.add(taskReference(t.taskId(), result.jobId())));
                    references = List.copyOf(refs);
                }
                case "getTaskAttempts" -> {
                    var result = reads.getTaskAttempts(scope.jobId(), args.taskId());
                    facts = result;
                    references = List.of(runReference(result.jobId()), taskReference(result.taskId(), result.jobId()));
                }
                case "getFailureAnalysis" -> {
                    var result = reads.getFailureAnalysis(scope.jobId(), scope.analysisId());
                    facts = result;
                    var refs = new java.util.ArrayList<Reference>();
                    refs.add(runReference(result.jobId()));
                    refs.add(analysisReference(result.analysisId()));
                    result.facts().tasks().forEach(t -> refs.add(taskReference(t.taskId(), result.jobId())));
                    references = List.copyOf(refs);
                }
                case "getSimilarIncidents" -> {
                    var result = reads.getSimilarIncidents(scope.jobId(), args.topK() == null ? 3 : args.topK());
                    if (scope.analysisId() != null && !scope.analysisId().equals(result.currentAnalysisId())) {
                        return error(tool, "EVIDENCE_NOT_READY");
                    }
                    facts = result;
                    var refs = new java.util.ArrayList<Reference>();
                    refs.add(runReference(result.jobId()));
                    refs.add(analysisReference(result.currentAnalysisId()));
                    result.matches().forEach(m -> {
                        refs.add(new Reference("incident", m.incidentId(),
                                "/api/v1/ai/failure-analyses/" + m.analysisId()));
                        refs.add(runReference(m.jobId()));
                        refs.add(taskReference(m.taskId(), m.jobId()));
                        refs.add(analysisReference(m.analysisId()));
                    });
                    references = refs.stream().distinct().toList();
                }
                case "getWorkflow" -> {
                    var result = reads.getWorkflow(scope.workflowId());
                    facts = result;
                    references = List.of(new Reference("workflow", result.workflowId(),
                            "/api/v1/workflows/" + result.workflowId()));
                }
                default -> { return error(tool, "UNSUPPORTED_TOOL"); }
            }
            String json = mapper.writeValueAsString(facts);
            if (json.length() > 4096) return error(tool, "CONTEXT_LIMIT");
            return new ToolResult(tool, true, null, json, references);
        } catch (CopilotReadService.ToolReadException exception) {
            return error(tool, exception.code());
        } catch (NoSuchElementException exception) {
            return error(tool, "MISSING_EVIDENCE");
        } catch (AiUnavailableException exception) {
            return error(tool, "READ_UNAVAILABLE");
        } catch (AiOutputException exception) {
            return error(tool, "INVALID_EVIDENCE");
        } catch (IllegalStateException exception) {
            return error(tool, "EVIDENCE_NOT_READY");
        } catch (org.springframework.dao.DataAccessException exception) {
            return error(tool, "READ_UNAVAILABLE");
        } catch (tools.jackson.core.JacksonException exception) {
            return error(tool, "INVALID_EVIDENCE");
        }
    }

    private static ToolResult error(String tool, String code) {
        return new ToolResult(tool != null && tool.length() <= 64 ? tool : "unknown",
                false, code, "{}", List.of());
    }
    private static Reference runReference(UUID jobId) {
        return new Reference("run", jobId, "/api/v1/jobs/" + jobId);
    }
    private static Reference taskReference(UUID taskId, UUID jobId) {
        return new Reference("task", taskId, "/api/v1/jobs/" + jobId);
    }
    private static Reference analysisReference(UUID analysisId) {
        return new Reference("analysis", analysisId, "/api/v1/ai/failure-analyses/" + analysisId);
    }

    public record Scope(UUID jobId, UUID workflowId, UUID analysisId) {}
    public record Arguments(UUID taskId, Integer topK) {}
    public record Reference(String kind, UUID id, String url) {}
    public record ToolResult(String tool, boolean success, String errorCode,
            String factsJson, List<Reference> references) {}
}
