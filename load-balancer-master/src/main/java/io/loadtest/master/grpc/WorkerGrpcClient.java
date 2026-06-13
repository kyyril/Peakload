package io.loadtest.master.grpc;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.loadtest.v1.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * gRPC client for communicating with Worker nodes.
 *
 * Manages connections to workers and provides methods for:
 * - Deploying scenarios
 * - Starting/stopping tests
 * - Streaming metrics
 */
@Component
public class WorkerGrpcClient {

    private static final Logger log = LoggerFactory.getLogger(WorkerGrpcClient.class);

    // Channel cache - reuse connections to the same worker
    private final ConcurrentHashMap<String, ManagedChannel> channels = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, WorkerServiceGrpc.WorkerServiceBlockingStub> stubs = new ConcurrentHashMap<>();

    /**
     * Get or create a gRPC stub for a worker.
     */
    private WorkerServiceGrpc.WorkerServiceBlockingStub getStub(String workerId) {
        return stubs.computeIfAbsent(workerId, this::createStub);
    }

    /**
     * Create a gRPC stub for a worker.
     * TODO: Resolve worker hostname:port from registry.
     */
    private WorkerServiceGrpc.WorkerServiceBlockingStub createStub(String workerId) {
        // In production, resolve worker address from registry
        // For now, assume worker runs on localhost:50051
        String host = System.getenv().getOrDefault("WORKER_" + workerId + "_HOST", "localhost");
        int port = Integer.parseInt(System.getenv().getOrDefault("WORKER_" + workerId + "_PORT", "50051"));

        ManagedChannel channel = ManagedChannelBuilder.forAddress(host, port)
                .usePlaintext()
                .maxInboundMessageSize(100 * 1024 * 1024)
                .build();

        channels.put(workerId, channel);
        return WorkerServiceGrpc.newBlockingStub(channel);
    }

    /**
     * Deploy a scenario to a worker.
     */
    public void deployScenario(String workerId, TestScenario scenario) {
        log.debug("Deploying scenario to worker {}: {}", workerId, scenario.getScenarioId());

        try {
            ScenarioAck ack = getStub(workerId).deployScenario(scenario);
            if (!ack.getAccepted()) {
                throw new RuntimeException("Scenario rejected by worker: " + ack.getErrorMessage());
            }
            log.info("Scenario deployed to worker {}: {}", workerId, scenario.getScenarioId());
        } catch (Exception e) {
            log.error("Failed to deploy scenario to worker {}", workerId, e);
            throw new RuntimeException("Failed to deploy scenario", e);
        }
    }

    /**
     * Start a test on a worker.
     */
    public void startTest(String workerId, String scenarioId, long startTimestampMs) {
        log.debug("Starting test on worker {}: scenario={}", workerId, scenarioId);

        StartTestCommand command = StartTestCommand.newBuilder()
                .setScenarioId(scenarioId)
                .setStartTimestampMs(startTimestampMs)
                .build();

        try {
            StartTestAck ack = getStub(workerId).startTest(command);
            if (!ack.getStarted()) {
                throw new RuntimeException("Test start rejected by worker");
            }
            log.info("Test started on worker {}: scenario={}", workerId, scenarioId);
        } catch (Exception e) {
            log.error("Failed to start test on worker {}", workerId, e);
            throw new RuntimeException("Failed to start test", e);
        }
    }

    /**
     * Stop a test on a worker.
     */
    public void stopTest(String workerId, String scenarioId, boolean immediate) {
        log.debug("Stopping test on worker {}: scenario={}, immediate={}", workerId, scenarioId, immediate);

        StopTestCommand command = StopTestCommand.newBuilder()
                .setScenarioId(scenarioId)
                .setImmediate(immediate)
                .build();

        try {
            StopTestAck ack = getStub(workerId).stopTest(command);
            log.info("Test stopped on worker {}: scenario={}, inFlight={}",
                    workerId, scenarioId, ack.getRequestsInFlight());
        } catch (Exception e) {
            log.error("Failed to stop test on worker {}", workerId, e);
            throw new RuntimeException("Failed to stop test", e);
        }
    }

    /**
     * Get stats from a worker.
     */
    public AggregateStats getStats(String workerId, String scenarioId) {
        StatsRequest request = StatsRequest.newBuilder()
                .setScenarioId(scenarioId)
                .build();

        try {
            return getStub(workerId).getStats(request);
        } catch (Exception e) {
            log.error("Failed to get stats from worker {}", workerId, e);
            throw new RuntimeException("Failed to get stats", e);
        }
    }

    /**
     * Health check a worker.
     */
    public boolean healthCheck(String workerId) {
        HealthCheckRequest request = HealthCheckRequest.newBuilder()
                .setServiceName("worker")
                .build();

        try {
            HealthCheckResponse response = getStub(workerId)
                    .withDeadlineAfter(5, TimeUnit.SECONDS)
                    .healthCheck(request);
            return response.getHealthy();
        } catch (Exception e) {
            log.warn("Health check failed for worker {}: {}", workerId, e.getMessage());
            return false;
        }
    }

    /**
     * Close all channels.
     */
    public void shutdown() {
        channels.values().forEach(channel -> {
            try {
                channel.shutdown().awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                channel.shutdownNow();
                Thread.currentThread().interrupt();
            }
        });
        channels.clear();
        stubs.clear();
    }
}
