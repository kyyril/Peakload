package io.loadtest.master.dto;

import java.time.Instant;
import java.util.Map;

/**
 * Information about a connected worker node.
 */
public record WorkerInfo(
        String workerId,
        String hostname,
        int port,
        String status,
        int availableCores,
        long availableMemoryMb,
        String javaVersion,
        Instant registeredAt,
        Instant lastHeartbeat,
        int activeScenarios,
        int activeThreads,
        double cpuUsagePercent,
        long usedMemoryMb
) {}

/**
 * Worker health check result.
 */
public record WorkerHealth(
        String workerId,
        boolean healthy,
        String status,
        String message,
        Map<String, Object> details
) {}

/**
 * Worker registration request.
 */
public record WorkerRegistrationRequest(
        String workerId,
        String hostname,
        int port,
        int availableCores,
        long availableMemoryMb,
        String javaVersion
) {}

/**
 * Scenario assignment for a worker.
 */
public record ScenarioAssignment(
        String scenarioId,
        String workerId,
        long assignedRps,
        String status
) {}
