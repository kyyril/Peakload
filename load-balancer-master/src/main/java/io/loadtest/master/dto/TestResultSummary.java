package io.loadtest.master.dto;

import java.util.Map;

/**
 * Test result summary.
 */
public record TestResultSummary(
        String scenarioId,
        String name,
        long totalRequests,
        long successfulRequests,
        long failedRequests,
        double successRate,
        double actualRps,
        long totalDurationMs,

        long latencyP50Ms,
        long latencyP95Ms,
        long latencyP99Ms,
        long latencyMaxMs,
        double latencyMeanMs,
        double latencyStdDev,

        long bytesSent,
        long bytesReceived,
        double throughputMbps,

        String status,
        String errorMessage,

        Map<Integer, Long> statusCodeDistribution,
        Map<String, Long> errorDistribution
) {}
