package io.loadtest.master.dto;

/**
 * Internal test execution status enum.
 */
public enum TestStatus {
    QUEUED,
    INITIALIZING,
    RUNNING,
    STOPPING,
    COMPLETED,
    FAILED,
    CANCELLED
}
