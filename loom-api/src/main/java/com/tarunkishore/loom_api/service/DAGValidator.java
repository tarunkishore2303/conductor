package com.tarunkishore.loom_api.service;

import com.loom.common.dto.TaskDefinition;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
public class DAGValidator {

    public void validate(List<TaskDefinition> tasks) {
        Set<String> knownIds = new HashSet<>();
        for (TaskDefinition t : tasks) {
            if (!knownIds.add(t.taskId())) {
                throw new IllegalArgumentException("Duplicate taskId: " + t.taskId());
            }
        }

        Map<String, List<String>> adj = new HashMap<>();
        for (TaskDefinition t : tasks) {
            for (String dep : t.dependsOn()) {
                if (!knownIds.contains(dep)) {
                    throw new IllegalArgumentException(
                        "Task '" + t.taskId() + "' depends on unknown taskId: " + dep);
                }
                adj.computeIfAbsent(dep, k -> new ArrayList<>()).add(t.taskId());
            }
        }

        Set<String> visited = new HashSet<>();
        Set<String> inStack = new HashSet<>();
        for (String id : knownIds) {
            if (!visited.contains(id)) {
                dfs(id, adj, visited, inStack);
            }
        }
    }

    private void dfs(String node, Map<String, List<String>> adj,
                     Set<String> visited, Set<String> inStack) {
        visited.add(node);
        inStack.add(node);
        for (String neighbor : adj.getOrDefault(node, List.of())) {
            if (inStack.contains(neighbor)) {
                throw new IllegalArgumentException("Cycle detected involving task: " + neighbor);
            }
            if (!visited.contains(neighbor)) {
                dfs(neighbor, adj, visited, inStack);
            }
        }
        inStack.remove(node);
    }
}
