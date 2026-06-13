package io.loadtest.master.dto;

/**
 * Scenario status information.
 */
public record ScenarioStatus(
        String scenarioId,
        String status,
        double currentRps,
        long totalRequests,
        long successfulRequests,
        long failedRequests,
        long latencyP50Ms,
        long latencyP95Ms,
        long latencyP99Ms,
        String currentPhase,
        long elapsedTimeMs,
        long remainingTimeMs,
        int activeWorkers
) {}
