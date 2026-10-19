package com.tarunkishore.loom_api.ai.optimization.support;

import com.tarunkishore.loom_api.ai.optimization.domain.OptimizationContext;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Dependency relationships only; graph structure cannot prove business independence. */
public final class OptimizationGraph {
    private OptimizationGraph() {}
    public static List<List<String>> siblingPairs(OptimizationContext context) {
        var tasks = context.tasks().stream().sorted(Comparator.comparing(OptimizationContext.TaskStructure::taskId)).toList();
        Map<String, List<String>> parents = new HashMap<>();
        tasks.forEach(task -> parents.put(task.taskId(), task.dependsOn()));
        List<List<String>> pairs = new ArrayList<>();
        for (int a = 0; a < tasks.size(); a++) for (int b = a + 1; b < tasks.size(); b++) {
            var left = tasks.get(a); var right = tasks.get(b);
            if (!left.dependsOn().isEmpty() && new HashSet<>(left.dependsOn()).equals(new HashSet<>(right.dependsOn()))
                    && !reaches(left.taskId(), right.taskId(), parents, new HashSet<>())
                    && !reaches(right.taskId(), left.taskId(), parents, new HashSet<>()))
                pairs.add(List.of(left.taskId(), right.taskId()));
        }
        return pairs;
    }

    private static boolean reaches(String from, String target, Map<String, List<String>> parents, Set<String> seen) {
        if (!seen.add(from)) return false;
        return parents.getOrDefault(from, List.of()).stream()
                .anyMatch(parent -> parent.equals(target) || reaches(parent, target, parents, seen));
    }
}
