package io.loadtest.master.dto;

/**
 * Scenario assignment for a worker.
 */
public record ScenarioAssignment(
        String scenarioId,
        String workerId,
        long assignedRps,
        String status
) {}
