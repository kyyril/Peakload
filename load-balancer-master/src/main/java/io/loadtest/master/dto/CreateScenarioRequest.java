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

