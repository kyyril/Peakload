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

