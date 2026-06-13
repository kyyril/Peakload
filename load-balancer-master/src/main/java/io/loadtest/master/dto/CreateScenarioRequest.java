package io.loadtest.master.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Request payload for creating a new load test scenario.
 */
public record CreateScenarioRequest(
        @NotBlank(message = "Scenario name is required")
        String name,

        @NotBlank(message = "Target URL is required")
        String url,

        String method,

        String body,
        String contentType,

        Map<String, String> headers,

        @Positive(message = "Target RPS must be positive")
        long targetRps,

        @Positive(message = "Max concurrency must be positive")
        int maxConcurrency,

        @PositiveOrZero(message = "Duration seconds must be non-negative")
        long durationSeconds,

        @PositiveOrZero
        long rampUpSeconds,

        @PositiveOrZero
        long rampDownSeconds,

        String loadProfileType,

        int totalSteps,

        boolean synchronizedStart,

        Map<String, String> tags
) {
    public CreateScenarioRequest {
        // Default values
        if (method == null || method.isBlank()) {
            method = "GET";
        }
        if (loadProfileType == null || loadProfileType.isBlank()) {
            loadProfileType = "constant";
        }
        if (durationSeconds <= 0) {
            durationSeconds = 60;
        }
        if (maxConcurrency <= 0) {
            maxConcurrency = 1000;
        }
    }
}

/**
 * Response payload for scenario details.
 */
public record ScenarioResponse(
        String scenarioId,
        String name,
        String url,
        String method,
        long targetRps,
        int maxConcurrency,
        long durationSeconds,
        String loadProfileType,
        String status,
        long createdTimestamp,
        long startedTimestamp,
        long completedTimestamp,
        Map<String, String> tags,
        List<String> assignedWorkers
) {}

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

/**
 * Request for starting a test.
 */
public record StartTestRequest(
        Long scheduledStartTimestampMs,
        boolean waitForSynchronization
) {}

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
) {
    public double successRate() {
        return totalRequests > 0 ? (double) successfulRequests / totalRequests : 0;
    }

    public double actualRps() {
        return totalDurationMs > 0 ? (double) totalRequests / (totalDurationMs / 1000.0) : 0;
    }

    public double throughputMbps() {
        return totalDurationMs > 0 ? (bytesReceived * 8 / 1024.0 / 1024.0) / (totalDurationMs / 1000.0) : 0;
    }
}
