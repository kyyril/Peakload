package io.loadtest.master.dto;

import java.util.Map;

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
