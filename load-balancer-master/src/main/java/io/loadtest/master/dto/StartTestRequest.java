package io.loadtest.master.dto;

/**
 * Request for starting a test.
 */
public record StartTestRequest(
        Long scheduledStartTimestampMs,
        boolean waitForSynchronization
) {}
