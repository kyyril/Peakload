package io.loadtest.worker.grpc;

import io.grpc.stub.StreamObserver;
import io.loadtest.common.metrics.MetricsCollector;
import io.loadtest.common.metrics.MetricsCollector.AggregateStats;
import io.loadtest.common.metrics.MetricsCollector.MetricsBatch;
import io.loadtest.common.parser.ScenarioParser;
import io.loadtest.common.parser.ScenarioParser.TestScenarioDefinition;
import io.loadtest.worker.engine.VirtualThreadLoadGenerator;
import io.loadtest.v1.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * gRPC Worker Service implementation.
 *
 * Handles commands from the Master node:
 * - DeployScenario: Receive test configuration
 * - StartTest: Begin load generation at specified time
 * - StopTest: Graceful or immediate shutdown
 * - GetStats: Real-time aggregate statistics

 * Thread Safety:
 * - scenarioManager: Thread-safe ConcurrentHashMap
 * - MetricsCollector: Lock-free internal implementation
 * - gRPC callbacks: Executed on gRPC thread pool, all state access is safe
 */
public final class WorkerGrpcService extends WorkerServiceGrpc.WorkerServiceImplBase {

    private static final Logger log = LoggerFactory.getLogger(WorkerGrpcService.java);

    private final String workerId = UUID.randomUUID().toString();
    private final ScenarioManager scenarioManager;
    private final MetricsCollector metricsCollector;
    private final ScheduledExecutorService metricsStreamExecutor;

    private final AtomicReference<WorkerStatus> currentStatus = new AtomicReference<>(WorkerStatus.IDLE);

    /**
     * Create the gRPC worker service.
     */
    public WorkerGrpcService() {
        this.scenarioManager = new ScenarioManager();
        this.metricsCollector = new MetricsCollector(1000, 500);
        this.metricsStreamExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "metrics-stream-engine");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Start the worker service.
     */
    public void start() {
        metricsCollector.start();
        currentStatus.set(WorkerStatus.IDLE);
        log.info("Worker service started: workerId={}", workerId);
    }

    /**
     * Stop the worker service.
     */
    public void stop() {
        currentStatus.set(WorkerStatus.DRAINING);
        scenarioManager.stopAll(true);
        metricsCollector.stop();
        metricsStreamExecutor.shutdown();
        currentStatus.set(WorkerStatus.IDLE);
        log.info("Worker service stopped: workerId={}", workerId);
    }

    
    // gRPC SERVICE IMPLEMENTATIONS
    

    @Override
    public void deployScenario(TestScenario request, StreamObserver<ScenarioAck> responseObserver) {
        try {
            currentStatus.set(WorkerStatus.INITIALIZING);
            log.info("Deploying scenario: scenarioId={}, name={}", request.getScenarioId(), request.getScenarioName());

            // Parse protobuf scenario into domain model
            TestScenarioDefinition scenario = parseProtoScenario(request);

            // Create load generator(s) for this scenario
            scenarioManager.deployScenario(scenario);

            currentStatus.set(WorkerStatus.IDLE);
            responseObserver.onNext(ScenarioAck.newBuilder()
                    .setAccepted(true)
                    .setScenarioId(request.getScenarioId())
                    .build());
            responseObserver.onCompleted();

        } catch (Exception e) {
            log.error("Failed to deploy scenario: {}", request.getScenarioId(), e);
            responseObserver.onNext(ScenarioAck.newBuilder()
                    .setAccepted(false)
                    .setScenarioId(request.getScenarioId())
                    .setErrorMessage(e.getMessage())
                    .build());
            responseObserver.onCompleted();
        }
    }

    @Override
    public void startTest(StartTestCommand request, StreamObserver<StartTestAck> responseObserver) {
        try {
            String scenarioId = request.getScenarioId();
            long startTimestampMs = request.getStartTimestampMs();

            log.info("Starting test: scenarioId={}, scheduledStart={}", scenarioId,
                    Instant.ofEpochMilli(startTimestampMs));

            // Calculate delay until start time
            long delayMs = Math.max(0, startTimestampMs - System.currentTimeMillis());

            if (delayMs > 0) {
                // Scheduled start
                scenarioManager.scheduleStart(scenarioId, delayMs);
            } else {
                // Immediate start
                scenarioManager.startScenario(scenarioId);
            }

            currentStatus.set(WorkerStatus.RUNNING);
            responseObserver.onNext(StartTestAck.newBuilder()
                    .setStarted(true)
                    .setScenarioId(scenarioId)
                    .build());
            responseObserver.onCompleted();

        } catch (Exception e) {
            log.error("Failed to start test: {}", request.getScenarioId(), e);
            responseObserver.onNext(StartTestAck.newBuilder()
                    .setStarted(false)
                    .setScenarioId(request.getScenarioId())
                    .build());
            responseObserver.onCompleted();
        }
    }

    @Override
    public void stopTest(StopTestCommand request, StreamObserver<StopTestAck> responseObserver) {
        try {
            String scenarioId = request.getScenarioId();
            boolean immediate = request.getImmediate();

            log.info("Stopping test: scenarioId={}, immediate={}", scenarioId, immediate);

            long inFlightRequests = scenarioManager.stopScenario(scenarioId, immediate);

            currentStatus.set(WorkerStatus.DRAINING);
            responseObserver.onNext(StopTestAck.newBuilder()
                    .setStopped(true)
                    .setRequestsInFlight(inFlightRequests)
                    .build());
            responseObserver.onCompleted();

            // Reset to idle after brief delay
            CompletableFuture.runAsync(() -> {
                try { Thread.sleep(100); } catch (InterruptedException ignored) {}
                currentStatus.set(WorkerStatus.IDLE);
            });

        } catch (Exception e) {
            log.error("Failed to stop test: {}", request.getScenarioId(), e);
            responseObserver.onNext(StopTestAck.newBuilder()
                    .setStopped(false)
                    .build());
            responseObserver.onCompleted();
        }
    }

    @Override
    public void getStats(StatsRequest request, StreamObserver<AggregateStats> responseObserver) {
        try {
            AggregateStats stats = metricsCollector.getAggregateStats();
            long snapshotTime = stats.snapshotTime().toEpochMilli();

            // Convert to protobuf
            io.loadtest.v1.AggregateStats protoStats = io.loadtest.v1.AggregateStats.newBuilder()
                    .setScenarioId(request.getScenarioId())
                    .setTimestampMs(snapshotTime)
                    .setTotalRequests(stats.totalRequests())
                    .setSuccessfulRequests(stats.successfulRequests())
                    .setFailedRequests(stats.failedRequests())
                    .setLatencyP50Ms(stats.latencyP50Micros() / 1000)
                    .setLatencyP90Ms(stats.latencyP90Micros() / 1000)
                    .setLatencyP95Ms(stats.latencyP95Micros() / 1000)
                    .setLatencyP99Ms(stats.latencyP99Micros() / 1000)
                    .setLatencyMaxMs(stats.latencyMaxMicros() / 1000)
                    .setLatencyMinMs(stats.latencyMinMicros() / 1000)
                    .setBytesReceived(stats.bytesReceived())
                    .setBytesSent(stats.bytesSent())
                    .build();

            responseObserver.onNext(protoStats);
            responseObserver.onCompleted();

        } catch (Exception e) {
            log.error("Failed to get stats", e);
            responseObserver.onError(e);
        }
    }

    @Override
    public void healthCheck(HealthCheckRequest request, StreamObserver<HealthCheckResponse> responseObserver) {
        responseObserver.onNext(HealthCheckResponse.newBuilder()
                .setHealthy(true)
                .setMessage("Worker is healthy")
                .build());
        responseObserver.onCompleted();
    }

    
    // HELPER METHODS
    

    /**
     * Parse protobuf TestScenario into domain TestScenarioDefinition.
     */
    private TestScenarioDefinition parseProtoScenario(TestScenario proto) throws ScenarioParser.ParseException {
        // Re-serialize to JSON and parse using ScenarioParser
        // This is a bit roundabout but ensures consistent parsing logic
        StringBuilder json = new StringBuilder();
        json.append("{\"id\":\"").append(proto.getScenarioId()).append("\",");
        json.append("\"name\":\"").append(proto.getScenarioName()).append("\",");

        // Parse load profile
        io.loadtest.v1.LoadProfile loadProfile = proto.getLoadProfile();
        json.append("\"loadProfile\":{");
        json.append("\"type\":\"constant\","); // Default
        json.append("\"targetRps\":").append(loadProfile.getTargetRps()).append(",");
        json.append("\"maxConcurrency\":").append(loadProfile.getMaxConcurrentRequests()).append(",");
        json.append("\"duration\":").append(loadProfile.getHoldDurationMs()).append(",");
        json.append("\"rampUpDuration\":").append(loadProfile.getRampUpDurationMs()).append(",");
        json.append("\"rampDownDuration\":").append(loadProfile.getRampDownDurationMs());
        json.append("},");

        // Parse HTTP scenarios
        json.append("\"scenarios\":[");
        for (int i = 0; i < proto.getHttpScenariosList().size(); i++) {
            io.loadtest.v1.HttpScenario http = proto.getHttpScenarios(i);
            json.append("{\"url\":\"").append(http.getUrl()).append("\",");
            json.append("\"method\":\"").append(http.getMethod().name().replace("HTTP_METHOD_", "")).append("\",");
            json.append("\"connectTimeout\":").append(http.getConnectTimeoutMs()).append(",");
            json.append("\"readTimeout\":").append(http.getReadTimeoutMs()).append(",");
            json.append("\"followRedirects\":").append(http.getFollowRedirects()).append(",");
            json.append("\"name\":\"").append(http.getName()).append("\"");
            json.append("}");
            if (i < proto.getHttpScenariosList().size() - 1) {
                json.append(",");
            }
        }
        json.append("],");

        // Tags
        json.append("\"tags\":{");
        Map<String, String> tags = proto.getTagsMap();
        if (!tags.isEmpty()) {
            boolean first = true;
            for (Map.Entry<String, String> entry : tags.entrySet()) {
                if (!first) json.append(",");
                json.append("\"").append(entry.getKey()).append("\":\"").append(entry.getValue()).append("\"");
                first = false;
            }
        }
        json.append("}}");

        ScenarioParser parser = new ScenarioParser();
        return parser.parse(json.toString());
    }

    /**
     * Get worker ID.
     */
    public String getWorkerId() {
        return workerId;
    }

    /**
     * Get current worker status.
     */
    public WorkerStatus getStatus() {
        return currentStatus.get();
    }

    
    // SCENARIO MANAGER
    

    /**
     * Manages active scenarios and their load generators.
     */
    private final class ScenarioManager {
        private final ConcurrentHashMap<String, VirtualThreadLoadGenerator> generators = new ConcurrentHashMap<>();
        private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);

        void deployScenario(TestScenarioDefinition scenario) {
            // Create load generator for each HTTP scenario
            // For now, we support single scenario
            var httpScenario = scenario.httpScenarios().get(0);
            var loadProfile = scenario.loadProfile();

            var generator = new VirtualThreadLoadGenerator(
                    scenario.scenarioId(),
                    workerId,
                    httpScenario,
                    loadProfile,
                    metricsCollector
            );

            generators.put(scenario.scenarioId(), generator);

            // Set up metrics streaming to Master
            metricsCollector.setBatchSink(batch -> {
                // In real implementation, stream to Master via gRPC
                log.debug("Metrics batch: {} metrics", batch.metrics().size());
            });
        }

        void startScenario(String scenarioId) {
            var generator = generators.get(scenarioId);
            if (generator == null) {
                throw new IllegalArgumentException("Unknown scenario: " + scenarioId);
            }
            generator.start();
        }

        void scheduleStart(String scenarioId, long delayMs) {
            scheduler.schedule(() -> {
                try {
                    startScenario(scenarioId);
                } catch (Exception e) {
                    log.error("Scheduled start failed", e);
                }
            }, delayMs, TimeUnit.MILLISECONDS);
        }

        long stopScenario(String scenarioId, boolean immediate) {
            var generator = generators.remove(scenarioId);
            if (generator != null) {
                generator.stop(immediate);
            }
            // Return approximate in-flight requests (would need more instrumentation)
            return 0;
        }

        void stopAll(boolean immediate) {
            generators.forEach((id, gen) -> gen.stop(immediate));
            generators.clear();
        }
    }
}
