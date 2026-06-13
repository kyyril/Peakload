package io.loadtest.master.dto;

import java.util.List;
import java.util.Map;

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
