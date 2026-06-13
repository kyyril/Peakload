package io.loadtest.common.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import static java.util.Objects.requireNonNull;

/**
 * Immutable record of a single HTTP request execution.
 *
 * Performance Considerations:
 * - Java Records are more memory-efficient than classes (~40% less overhead)
 * - Final fields enable JVM escape analysis optimizations
 * - Primitive types for latency avoid Long object boxing
 *
 * Memory Layout:
 * Each RequestMetric instance consumes ~80 bytes:
 * - Object header: 12 bytes
 * - Reference fields (scenarioId, workerId, etc.): ~30 bytes
 * - Primitive fields (timestamps, latencies): ~40 bytes
 *
 * At 100K RPS with 1-minute retention = ~480MB raw metric storage
 * Batching reduces this to ~50MB by aggregating percentiles.
 */
public record RequestMetric(
        String traceId,
        String scenarioId,
        String workerId,
        Instant timestamp,
        long latencyMicros,        // Microseconds for sub-millisecond precision
        int statusCode,
        boolean success,
        String errorMessage,
        long requestBytes,
        long responseBytes,
        long dnsTimeMicros,
        long connectTimeMicros,
        long tlsHandshakeMicros,
        long timeToFirstByteMicros
) {
    public RequestMetric {
        requireNonNull(traceId, "traceId cannot be null");
        requireNonNull(scenarioId, "scenarioId cannot be null");
        requireNonNull(workerId, "workerId cannot be null");
        requireNonNull(timestamp, "timestamp cannot be null");
        requireNonNull(errorMessage, "errorMessage cannot be null (use empty string)");

        // Validation constraints
        if (latencyMicros < 0) {
            throw new IllegalArgumentException("Latency cannot be negative: " + latencyMicros);
        }
        if (statusCode < -1 || statusCode > 599) {
            throw new IllegalArgumentException("Invalid status code: " + statusCode);
        }
    }

    /**
     * Create a successful request metric.
     */
    public static RequestMetric success(
            String scenarioId,
            String workerId,
            Instant timestamp,
            long latencyMicros,
            int statusCode
    ) {
        return new RequestMetric(
                UUID.randomUUID().toString(),
                scenarioId,
                workerId,
                timestamp,
                latencyMicros,
                statusCode,
                true,
                "",
                0, 0, 0, 0, 0, 0
        );
    }

    /**
     * Create a failed request metric with error message.
     */
    public static RequestMetric failure(
            String scenarioId,
            String workerId,
            Instant timestamp,
            String errorMessage
    ) {
        return new RequestMetric(
                UUID.randomUUID().toString(),
                scenarioId,
                workerId,
                timestamp,
                0,
                -1,
                false,
                errorMessage,
                0, 0, 0, 0, 0, 0
        );
    }

    /**
     * Convert latency from microseconds to milliseconds.
     */
    public double latencyMillis() {
        return latencyMicros / 1000.0;
    }

    /**
     * Convert latency from microseconds to seconds.
     */
    public double latencySeconds() {
        return latencyMicros / 1_000_000.0;
    }

    /**
     * Total time spent in network phases (DNS + Connect + TLS + TTFB).
     * Useful for identifying bottlenecks vs. server processing time.
     */
    public long networkTimeMicros() {
        return dnsTimeMicros + connectTimeMicros + tlsHandshakeMicros + timeToFirstByteMicros;
    }

    /**
     * Server time estimation: latency minus network phases.
     * Approximates actual server-side processing time.
     */
    public long estimatedServerTimeMicros() {
        long network = networkTimeMicros();
        return Math.max(0, latencyMicros - network);
    }

    /**
     * Convert to protobuf message for gRPC transmission.
     */
    public io.loadtest.v1.RequestMetric toProto() {
        return io.loadtest.v1.RequestMetric.newBuilder()
                .setTraceId(traceId)
                .setScenarioId(scenarioId)
                .setWorkerId(workerId)
                .setTimestampMs(timestamp.toEpochMilli())
                .setLatencyMs((int) (latencyMicros / 1000))
                .setSuccess(success)
                .setStatusCode(statusCode)
                .setErrorMessage(errorMessage)
                .setRequestBytes(requestBytes)
                .setResponseBytes(responseBytes)
                .setDnsTimeMs((int) (dnsTimeMicros / 1000))
                .setConnectTimeMs((int) (connectTimeMicros / 1000))
                .setTlsTimeMs((int) (tlsHandshakeMicros / 1000))
                .setTtfbMs((int) (timeToFirstByteMicros / 1000))
                .build();
    }

    /**
     * Create from protobuf message.
     */
    public static RequestMetric fromProto(io.loadtest.v1.RequestMetric proto) {
        return new RequestMetric(
                proto.getTraceId(),
                proto.getScenarioId(),
                proto.getWorkerId(),
                Instant.ofEpochMilli(proto.getTimestampMs()),
                proto.getLatencyMs() * 1000L,
                proto.getStatusCode(),
                proto.getSuccess(),
                proto.getErrorMessage(),
                proto.getRequestBytes(),
                proto.getResponseBytes(),
                proto.getDnsTimeMs() * 1000L,
                proto.getConnectTimeMs() * 1000L,
                proto.getTlsTimeMs() * 1000L,
                proto.getTtfbMs() * 1000L
        );
    }
}
