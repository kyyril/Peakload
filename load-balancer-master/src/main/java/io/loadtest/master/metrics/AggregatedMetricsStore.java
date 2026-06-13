package io.loadtest.master.metrics;

import io.loadtest.master.dto.TestResultSummary;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * In-memory store for aggregated test metrics.
 *
 * In production, metrics are persisted to InfluxDB or ClickHouse.
 * This implementation provides fast in-memory aggregation for real-time dashboards.
 *
 */
@Component
public class AggregatedMetricsStore {

    private final ConcurrentHashMap<String, MetricAccumulator> scenarioMetrics = new ConcurrentHashMap<>();

    /**
     * Record a metric from a worker.
     */
    public void recordMetric(String scenarioId, long latencyMicros, int statusCode,
                             boolean success, long requestBytes, long responseBytes) {
        MetricAccumulator accumulator = scenarioMetrics.computeIfAbsent(
                scenarioId,
                k -> new MetricAccumulator()
        );

        accumulator.record(latencyMicros, statusCode, success, requestBytes, responseBytes);
    }

    /**
     * Record a batch of metrics from a worker.
     */
    public void recordBatch(String scenarioId, List<MetricData> batch) {
        MetricAccumulator accumulator = scenarioMetrics.computeIfAbsent(
                scenarioId,
                k -> new MetricAccumulator()
        );

        for (MetricData data : batch) {
            accumulator.record(data.latencyMicros, data.statusCode, data.success,
                    data.requestBytes, data.responseBytes);
        }
    }

    /**
     * Get summary for a scenario.
     */
    public TestResultSummary getSummary(String scenarioId) {
        MetricAccumulator accumulator = scenarioMetrics.get(scenarioId);
        if (accumulator == null) {
            return null;
        }
        return accumulator.toSummary(scenarioId);
    }

    /**
     * Clear all metrics for a scenario.
     */
    public void clear(String scenarioId) {
        scenarioMetrics.remove(scenarioId);
    }

    
    // INTERNAL ACCUMULATOR
    

    /**
     * Internal metric accumulator using LongAdder for high throughput.
     */
    private static final class MetricAccumulator {
        private final LongAdder totalRequests = new LongAdder();
        private final LongAdder successfulRequests = new LongAdder();
        private final LongAdder failedRequests = new LongAdder();
        private final LongAdder bytesSent = new LongAdder();
        private final LongAdder bytesReceived = new LongAdder();

        // For mean/std dev calculation
        private final LongAdder latencySumMicros = new LongAdder();
        private final LongAdder latencySumSquaresMicros = new LongAdder();

        // For min/max tracking
        private final java.util.concurrent.atomic.AtomicLong latencyMin = new java.util.concurrent.atomic.AtomicLong(Long.MAX_VALUE);
        private final java.util.concurrent.atomic.AtomicLong latencyMax = new java.util.concurrent.atomic.AtomicLong(Long.MIN_VALUE);

        // Latency histogram for percentile calculation (bucketed by powers of 2)
        private final LongAdder[] latencyHistogram = new LongAdder[32];
        private static final long[] HISTOGRAM_BOUNDS;

        static {
            HISTOGRAM_BOUNDS = new long[32];
            for (int i = 0; i < 32; i++) {
                HISTOGRAM_BOUNDS[i] = 1L << i; // 1, 2, 4, 8, 16, ...
            }
        }

        private final ConcurrentHashMap<Integer, LongAdder> statusCodeCounts = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<String, LongAdder> errorCounts = new ConcurrentHashMap<>();

        private final Instant startTime = Instant.now();

        MetricAccumulator() {
            for (int i = 0; i < latencyHistogram.length; i++) {
                latencyHistogram[i] = new LongAdder();
            }
        }

        void record(long latencyMicros, int statusCode, boolean success,
                    long requestBytes, long responseBytes) {
            totalRequests.increment();
            if (success) {
                successfulRequests.increment();
            } else {
                failedRequests.increment();
            }

            latencySumMicros.add(latencyMicros);
            latencySumSquaresMicros.add(latencyMicros * latencyMicros);

            // Update min/max
            latencyMin.accumulateAndGet(latencyMicros, Math::min);
            latencyMax.accumulateAndGet(latencyMicros, Math::max);

            // Update histogram
            int bucket = findBucket(latencyMicros);
            latencyHistogram[bucket].increment();

            // Update status code counts
            statusCodeCounts.computeIfAbsent(statusCode, k -> new LongAdder()).increment();

            bytesSent.add(requestBytes);
            bytesReceived.add(responseBytes);
        }

        private int findBucket(long value) {
            int idx = 31;
            for (int i = 0; i < 32; i++) {
                if (value < HISTOGRAM_BOUNDS[i]) {
                    return i;
                }
            }
            return idx;
        }

        TestResultSummary toSummary(String scenarioId) {
            long total = totalRequests.sum();
            if (total == 0) {
                return new TestResultSummary(scenarioId, "", 0L, 0L, 0L, 0.0, 0.0, 0L, 0L, 0L, 0L, 0L, 0.0, 0.0, 0L, 0L, 0.0, "COMPLETE", "", Map.of(), Map.of());
            }

            long elapsedMs = java.time.Duration.between(startTime, Instant.now()).toMillis();

            // Calculate mean
            double meanMicros = (double) latencySumMicros.sum() / total;
            double meanSquared = (double) latencySumSquaresMicros.sum() / total;
            double stdDevMicros = Math.sqrt(meanSquared - meanMicros * meanMicros);

            // Calculate percentiles from histogram
            long[] histogramCounts = new long[32];
            long cumulative = 0;
            for (int i = 0; i < 32; i++) {
                histogramCounts[i] = latencyHistogram[i].sum();
                cumulative += histogramCounts[i];
            }
            cumulative = 0;

            long p50Micros = percentileFromHistogram(histogramCounts, total, 0.50);
            long p95Micros = percentileFromHistogram(histogramCounts, total, 0.95);
            long p99Micros = percentileFromHistogram(histogramCounts, total, 0.99);

            // Build status code map
            Map<Integer, Long> statusCodeMap = new HashMap<>();
            statusCodeCounts.forEach((code, adder) -> statusCodeMap.put(code, adder.sum()));

            return new TestResultSummary(
                    scenarioId,
                    "",
                    total,
                    successfulRequests.sum(),
                    failedRequests.sum(),
                    (double) successfulRequests.sum() / total,
                    elapsedMs > 0 ? (double) total / (elapsedMs / 1000.0) : 0,
                    elapsedMs,
                    p50Micros / 1000, // Convert to milliseconds
                    p95Micros / 1000,
                    p99Micros / 1000,
                    latencyMax.get() / 1000,
                    meanMicros / 1000,
                    stdDevMicros / 1000,
                    bytesSent.sum(),
                    bytesReceived.sum(),
                    elapsedMs > 0 ? (bytesReceived.sum() * 8.0 / 1024 / 1024) / (elapsedMs / 1000.0) : 0,
                    "COMPLETE",
                    "",
                    Collections.unmodifiableMap(statusCodeMap),
                    Collections.emptyMap()
            );
        }

        private long percentileFromHistogram(long[] counts, long total, double percentile) {
            long target = (long) (total * percentile);
            long cumulative = 0;

            for (int i = 0; i < counts.length; i++) {
                cumulative += counts[i];
                if (cumulative >= target) {
                    // Interpolate within bucket
                    return HISTOGRAM_BOUNDS[i];
                }
            }
            return HISTOGRAM_BOUNDS[31];
        }
    }

    /**
     * Simple metric data record.
     */
    public record MetricData(
            long latencyMicros,
            int statusCode,
            boolean success,
            long requestBytes,
            long responseBytes
    ) {}
}
