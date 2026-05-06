package com.tarunkishore.loom_api.service;

import com.loom.common.dto.TaskDefinition;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DAGValidatorTest {

    private final DAGValidator validator = new DAGValidator();

    @Test
    void validLinearChain() {
        assertDoesNotThrow(() -> validator.validate(List.of(
            new TaskDefinition("A", "Step A", List.of(), 3),
            new TaskDefinition("B", "Step B", List.of("A"), 3),
            new TaskDefinition("C", "Step C", List.of("B"), 3)
        )));
    }

    @Test
    void validDiamond() {
        assertDoesNotThrow(() -> validator.validate(List.of(
            new TaskDefinition("A", "Step A", List.of(), 3),
            new TaskDefinition("B", "Step B", List.of("A"), 3),
            new TaskDefinition("C", "Step C", List.of("A"), 3),
            new TaskDefinition("D", "Step D", List.of("B", "C"), 3)
        )));
    }

    @Test
    void detectsCycle() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> validator.validate(List.of(
                new TaskDefinition("A", "Step A", List.of("C"), 3),
                new TaskDefinition("B", "Step B", List.of("A"), 3),
                new TaskDefinition("C", "Step C", List.of("B"), 3)
            )));
        assertTrue(ex.getMessage().contains("Cycle"));
    }

    @Test
    void detectsDuplicateTaskId() {
        assertThrows(IllegalArgumentException.class,
            () -> validator.validate(List.of(
                new TaskDefinition("A", "Step A", List.of(), 3),
                new TaskDefinition("A", "Step A2", List.of(), 3)
            )));
    }

    @Test
    void detectsUnknownDependency() {
        assertThrows(IllegalArgumentException.class,
            () -> validator.validate(List.of(
                new TaskDefinition("A", "Step A", List.of("GHOST"), 3)
            )));
    }
}
