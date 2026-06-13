package io.loadtest.master.dto;

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
