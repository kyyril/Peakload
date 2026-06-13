package io.loadtest.master.service;

import io.loadtest.common.model.HttpScenario;
import io.loadtest.common.model.LoadProfile;
import io.loadtest.common.parser.ScenarioParser;
import io.loadtest.common.parser.ScenarioParser.TestScenarioDefinition;
import io.loadtest.master.dto.*;
import io.loadtest.master.grpc.WorkerGrpcClient;
import io.loadtest.master.metrics.AggregatedMetricsStore;
import io.loadtest.master.registry.WorkerRegistry;
import io.loadtest.master.dto.TestStatus;
import io.loadtest.v1.AggregateStats;
import io.loadtest.v1.HttpHeader;
import io.loadtest.v1.RequestBody;
import io.loadtest.v1.TestScenario;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/**
 * Scenario orchestration service.
 *
 * Responsibilities:
 * - Scenario creation and persistence
 * - Worker assignment and workload distribution
 * - Test lifecycle management (start/stop/pause)
 * - Metrics aggregation from all workers
 *
 * Architecture:
 * ┌───────────────────────────────────────────────────────────────────────┐
 * │                 ScenarioOrchestrationService                          │
 * │                                                                        │
 * │   ┌────────────────┐     ┌───────────────────────────────────────┐   │
 * │   │ CreateScenario ├───►│ ScenarioParser ◄──── JSON request      │   │
 * │   │                 │     └───────────────────────────────────────┘   │
 * │   └────────┬───────┘                                                   │
 * │            │                                                            │
 * │   ┌────────▼───────┐     ┌───────────────────────────────────────┐   │
 * │   │ WorkerAssigner ├───►│ WorkerRegistry (Redis)                  │   │
 * │   │                 │     │   Find(IDLE workers)                   │   │
 * │   │                 │     │   Assign scenario to each              │   │
--│
    │   │                 │     │   Track status                          │   │
--│
 * │   └────────┬───────┘     └───────────────────────────────────────┘   │
 * │            │                                                            │
 * │   ┌────────▼───────┐     ┌───────────────────────────────────────┐   │
 * │   │ StartScenario  ├────►│ WorkerGrpcClient                       │   │
--│
    │   │                 │     │   DeployScenario(id, proto)            │   │
--│
    │   │                 │     │   StartTest(id, startTimestamp)       │   │
--│
 * │   └────────┬───────┘     └───────────────────────────────────────┘   │
 * │            │                                                            │
 * │   ┌────────▼───────────────────────────────────────────────────────┐│
 * │   │   AggregatedMetricsStore (InfluxDB / In Memory)                  ││
 * │   │   - Collect metrics from all workers                              ││
 * │   │   - Aggregate percentiles (p50, p90, p95, p99)                    ││
 * │   │   - Calculate throughput                                          ││
--│
    │   │   - Track status code distribution                                ││
--│
 * │   └─────────────────────────────────────────────────────────────────┘│
 * └───────────────────────────────────────────────────────────────────────┘
 */
@Service
public class ScenarioOrchestrationService {

    private static final Logger log = LoggerFactory.getLogger(ScenarioOrchestrationService.class);

    private final WorkerRegistry workerRegistry;
    private final WorkerGrpcClient workerGrpcClient;
    private final AggregatedMetricsStore metricsStore;
    private final ScenarioParser scenarioParser;

    // In-memory scenario storage (Redis-backed in production)
    private final ConcurrentHashMap<String, TestScenarioDefinition> scenarios = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, List<ScenarioAssignment>> assignments = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ScenarioInstanceState> instanceStates = new ConcurrentHashMap<>();

    public ScenarioOrchestrationService(
            WorkerRegistry workerRegistry,
            WorkerGrpcClient workerGrpcClient,
            AggregatedMetricsStore metricsStore
    ) {
        this.workerRegistry = workerRegistry;
        this.workerGrpcClient = workerGrpcClient;
        this.metricsStore = metricsStore;
        this.scenarioParser = new ScenarioParser();
    }

    
    // SCENARIO MANAGEMENT
    

    /**
     * Create a new test scenario.
     */
    public ScenarioResponse createScenario(CreateScenarioRequest request) {
        String scenarioId = UUID.randomUUID().toString();
        log.info("Creating scenario: id={}, name={}, url={}", scenarioId, request.name(), request.url());

        // Build JSON from request for parsing
        String json = buildScenarioJson(scenarioId, request);
        try {
            TestScenarioDefinition scenario = scenarioParser.parse(json);
            scenarios.put(scenarioId, scenario);

            // Initialize instance state
            instanceStates.put(scenarioId, new ScenarioInstanceState(
                    scenarioId,
                    TestStatus.QUEUED,
                    Instant.now(),
                    null,
                    null
            ));

            return toScenarioResponse(scenario);

        } catch (ScenarioParser.ParseException e) {
            throw new IllegalArgumentException("Invalid scenario configuration: " + e.getMessage(), e);
        }
    }

    /**
     * List all scenarios.
     */
    public List<ScenarioResponse> listScenarios(int page, int size, String statusFilter) {
        return scenarios.values().stream()
                .filter(s -> statusFilter == null || statusFilter.isEmpty() ||
                        instanceStates.get(s.scenarioId()).status().name().equalsIgnoreCase(statusFilter))
                .skip((long) page * size)
                .limit(size)
                .map(this::toScenarioResponse)
                .toList();
    }

    /**
     * Get a specific scenario.
     */
    public Optional<ScenarioResponse> getScenario(String scenarioId) {
        TestScenarioDefinition scenario = scenarios.get(scenarioId);
        if (scenario == null) {
            return Optional.empty();
        }
        return Optional.of(toScenarioResponse(scenario));
    }

    /**
     * Delete a scenario.
     */
    public void deleteScenario(String scenarioId) {
        // Stop if running
        if (isRunning(scenarioId)) {
            stopTest(scenarioId, true);
        }

        scenarios.remove(scenarioId);
        assignments.remove(scenarioId);
        instanceStates.remove(scenarioId);
        metricsStore.clear(scenarioId);
    }

    
    // TEST EXECUTION
    

    /**
     * Start a test scenario.
     */
    public ScenarioStatus startTest(String scenarioId, StartTestRequest request) {
        TestScenarioDefinition scenario = scenarios.get(scenarioId);
        if (scenario == null) {
            throw new IllegalArgumentException("Scenario not found: " + scenarioId);
        }

        ScenarioInstanceState state = instanceStates.get(scenarioId);
        if (state.status() == TestStatus.RUNNING) {
            throw new IllegalStateException("Scenario is already running: " + scenarioId);
        }

        log.info("Starting scenario: id={}, name={}", scenarioId, scenario.scenarioName());

        // Find available workers
        List<WorkerInfo> availableWorkers = workerRegistry.findAvailableWorkers(1);
        if (availableWorkers.isEmpty()) {
            throw new IllegalStateException("No available workers");
        }

        // Calculate start timestamp (for synchronized start)
        long startTimestamp = request != null && request.scheduledStartTimestampMs() != null
                ? request.scheduledStartTimestampMs()
                : System.currentTimeMillis() + 1000; // 1 second delay for synchronization

        // Convert scenario to protobuf
        TestScenario protoScenario = toProtoScenario(scenario);

        // Deploy to each worker
        List<ScenarioAssignment> scenarioAssignments = new ArrayList<>();
        for (WorkerInfo worker : availableWorkers) {
            try {
                // Deploy scenario
                workerGrpcClient.deployScenario(worker.workerId(), protoScenario);

                // Schedule start
                long delayMs = Math.max(0, startTimestamp - System.currentTimeMillis());
                CompletableFuture.runAsync(() -> {
                    try {
                        Thread.sleep(delayMs);
                        workerGrpcClient.startTest(worker.workerId(), scenarioId, startTimestamp);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } catch (Exception e) {
                        log.error("Failed to start test on worker {}", worker.workerId(), e);
                    }
                });

                workerRegistry.setWorkerStatus(worker.workerId(), "RUNNING");
                scenarioAssignments.add(new ScenarioAssignment(scenarioId, worker.workerId(), 0, "RUNNING"));

            } catch (Exception e) {
                log.error("Failed to deploy scenario to worker {}", worker.workerId(), e);
            }
        }

        assignments.put(scenarioId, scenarioAssignments);

        // Update state
        instanceStates.put(scenarioId, new ScenarioInstanceState(
                scenarioId,
                TestStatus.RUNNING,
                state.createdAt(),
                Instant.now(),
                null
        ));

        return getStatus(scenarioId).orElseThrow();
    }

    /**
     * Stop a test scenario.
     */
    public ScenarioStatus stopTest(String scenarioId, boolean immediate) {
        ScenarioInstanceState state = instanceStates.get(scenarioId);
        if (state == null || state.status() != TestStatus.RUNNING) {
            return getStatus(scenarioId).orElseThrow();
        }

        log.info("Stopping scenario: id={}, immediate={}", scenarioId, immediate);

        // Send stop command to all assigned workers
        List<ScenarioAssignment> assignmentList = assignments.get(scenarioId);
        if (assignmentList != null) {
            for (ScenarioAssignment assignment : assignmentList) {
                try {
                    workerGrpcClient.stopTest(assignment.workerId(), scenarioId, immediate);
                    workerRegistry.setWorkerStatus(assignment.workerId(), "IDLE");
                } catch (Exception e) {
                    log.error("Failed to stop test on worker {}", assignment.workerId(), e);
                }
            }
        }

        // Update state
        instanceStates.put(scenarioId, new ScenarioInstanceState(
                scenarioId,
                immediate ? TestStatus.CANCELLED : TestStatus.COMPLETED,
                state.createdAt(),
                state.startedAt(),
                Instant.now()
        ));

        return getStatus(scenarioId).orElseThrow();
    }

    /**
     * Get current scenario status.
     */
    public Optional<ScenarioStatus> getStatus(String scenarioId) {
        TestScenarioDefinition scenario = scenarios.get(scenarioId);
        ScenarioInstanceState state = instanceStates.get(scenarioId);
        List<ScenarioAssignment> assignmentList = assignments.get(scenarioId);

        if (scenario == null || state == null) {
            return Optional.empty();
        }

        // Get aggregated metrics
        TestResultSummary summary = metricsStore.getSummary(scenarioId);
        long elapsedMs = state.startedAt() != null
                ? java.time.Duration.between(state.startedAt(), Instant.now()).toMillis()
                : 0;

        long durationMs = scenario.loadProfile().estimatedDurationMs().orElse(0L);
        long remainingMs = Math.max(0, durationMs - elapsedMs);

        return Optional.of(new ScenarioStatus(
                scenarioId,
                state.status().name(),
                summary != null ? summary.actualRps() : 0,
                summary != null ? summary.totalRequests() : 0,
                summary != null ? summary.successfulRequests() : 0,
                summary != null ? summary.failedRequests() : 0,
                summary != null ? summary.latencyP50Ms() : 0,
                summary != null ? summary.latencyP95Ms() : 0,
                summary != null ? summary.latencyP99Ms() : 0,
                "ACTIVE",
                elapsedMs,
                remainingMs,
                assignmentList != null ? assignmentList.size() : 0
        ));
    }

    /**
     * Get final test results.
     */
    public Optional<TestResultSummary> getResults(String scenarioId) {
        return Optional.ofNullable(metricsStore.getSummary(scenarioId));
    }

    
    // WORKER MANAGEMENT
    

    public List<WorkerInfo> listWorkers() {
        return workerRegistry.getAllWorkers();
    }

    public Optional<WorkerHealth> getWorkerHealth(String workerId) {
        return workerRegistry.getWorker(workerId)
                .map(w -> new WorkerHealth(
                        w.workerId(),
                        workerRegistry.isAlive(w.workerId()),
                        w.status(),
                        "Worker is healthy",
                        Map.of("lastHeartbeat", w.lastHeartbeat().toString())
                ));
    }

    
    // INTERNAL METHODS
    

    private boolean isRunning(String scenarioId) {
        ScenarioInstanceState state = instanceStates.get(scenarioId);
        return state != null && state.status() == TestStatus.RUNNING;
    }

    /**
     * Build scenario JSON from request.
     */
    private String buildScenarioJson(String scenarioId, CreateScenarioRequest request) {
        StringBuilder json = new StringBuilder();
        json.append("{\"id\":\"").append(scenarioId).append("\",");
        json.append("\"name\":\"").append(request.name()).append("\",");

        // Scenarios array
        json.append("\"scenarios\":[{");
        json.append("\"url\":\"").append(request.url()).append("\",");
        json.append("\"method\":\"").append(request.method()).append("\",");
        if (request.body() != null && !request.body().isBlank()) {
            json.append("\"body\":\"").append(escapeJson(request.body())).append("\",");
        }
        if (request.contentType() != null) {
            json.append("\"contentType\":\"").append(request.contentType()).append("\",");
        }
        json.append("\"connectTimeout\":5000,");
        json.append("\"readTimeout\":30000,");
        json.append("\"followRedirects\":true");
        json.append("}],");

        // Load profile
        json.append("\"loadProfile\":{");
        json.append("\"type\":\"").append(request.loadProfileType()).append("\",");
        json.append("\"targetRps\":").append(request.targetRps()).append(",");
        json.append("\"duration\":\"").append(request.durationSeconds()).append("s\",");
        json.append("\"maxConcurrency\":").append(request.maxConcurrency());
        if (request.loadProfileType().equalsIgnoreCase("rampup")) {
            json.append(",\"rampUpDuration\":\"").append(request.rampUpSeconds()).append("s\"");
            json.append(",\"rampDownDuration\":\"").append(request.rampDownSeconds()).append("s\"");
        }
        if (request.loadProfileType().equalsIgnoreCase("stepup")) {
            json.append(",\"totalSteps\":").append(request.totalSteps());
        }
        if (request.loadProfileType().equalsIgnoreCase("spike")) {
            json.append(",\"synchronizedStart\":").append(request.synchronizedStart());
        }
        json.append("},");

        // Tags
        json.append("\"tags\":{");
        if (request.tags() != null && !request.tags().isEmpty()) {
            boolean first = true;
            for (Map.Entry<String, String> tag : request.tags().entrySet()) {
                if (!first) json.append(",");
                json.append("\"").append(tag.getKey()).append("\":\"").append(tag.getValue()).append("\"");
                first = false;
            }
        }
        json.append("}}");

        return json.toString();
    }

    private String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /**
     * Convert scenario to protobuf.
     */
    private TestScenario toProtoScenario(TestScenarioDefinition scenario) {
        io.loadtest.common.model.HttpScenario httpScenario = scenario.httpScenarios().get(0);
        io.loadtest.common.model.LoadProfile loadProfile = scenario.loadProfile();

        io.loadtest.v1.HttpScenario.Builder httpBuilder = io.loadtest.v1.HttpScenario.newBuilder()
                .setUrl(httpScenario.url())
                .setMethod(convertMethod(httpScenario.method()))
                .setConnectTimeoutMs((int) httpScenario.connectTimeout().toMillis())
                .setReadTimeoutMs((int) httpScenario.readTimeout().toMillis())
                .setFollowRedirects(httpScenario.followRedirects())
                .setName(httpScenario.name());

        // Add headers
        for (io.loadtest.common.model.HttpScenario.Header header : httpScenario.headers()) {
            httpBuilder.addHeaders(HttpHeader.newBuilder()
                    .setKey(header.name())
                    .setValue(header.value())
                    .build());
        }

        // Add body if present
        if (httpScenario.body().isPresent()) {
            io.loadtest.common.model.HttpScenario.RequestBody body = httpScenario.body().get();
            httpBuilder.setBody(RequestBody.newBuilder()
                    .setRawContent(body.content())
                    .setContentType(body.contentType())
                    .build());
        }

        // Build load profile
        io.loadtest.v1.LoadProfile.Builder loadBuilder = io.loadtest.v1.LoadProfile.newBuilder()
                .setTargetRps(loadProfile.targetRps())
                .setMaxConcurrentRequests(loadProfile.maxConcurrency());

        if (loadProfile instanceof io.loadtest.common.model.LoadProfile.RampUp rampUp) {
            loadBuilder.setRampUpDurationMs(rampUp.rampUpDuration().toMillis());
            loadBuilder.setHoldDurationMs(rampUp.holdDuration().toMillis());
            loadBuilder.setRampDownDurationMs(rampUp.rampDownDuration().toMillis());
        } else if (loadProfile.estimatedDurationMs().isPresent()) {
            loadBuilder.setHoldDurationMs(loadProfile.estimatedDurationMs().getAsLong());
        }

        return TestScenario.newBuilder()
                .setScenarioId(scenario.scenarioId())
                .setScenarioName(scenario.scenarioName())
                .addHttpScenarios(httpBuilder.build())
                .setLoadProfile(loadBuilder.build())
                .putAllTags(scenario.tags())
                .setCreatedTimestampMs(scenario.createdTimestampMs())
                .build();
    }

    private io.loadtest.v1.HttpMethod convertMethod(io.loadtest.common.model.HttpScenario.HttpMethod method) {
        return switch (method) {
            case GET -> io.loadtest.v1.HttpMethod.HTTP_METHOD_GET;
            case POST -> io.loadtest.v1.HttpMethod.HTTP_METHOD_POST;
            case PUT -> io.loadtest.v1.HttpMethod.HTTP_METHOD_PUT;
            case PATCH -> io.loadtest.v1.HttpMethod.HTTP_METHOD_PATCH;
            case DELETE -> io.loadtest.v1.HttpMethod.HTTP_METHOD_DELETE;
            case HEAD -> io.loadtest.v1.HttpMethod.HTTP_METHOD_HEAD;
            case OPTIONS -> io.loadtest.v1.HttpMethod.HTTP_METHOD_OPTIONS;
            default -> io.loadtest.v1.HttpMethod.HTTP_METHOD_GET;
        };
    }

    /**
     * Convert scenario definition to response.
     */
    private ScenarioResponse toScenarioResponse(TestScenarioDefinition scenario) {
        ScenarioInstanceState state = instanceStates.get(scenario.scenarioId());
        List<ScenarioAssignment> assignmentList = assignments.get(scenario.scenarioId());

        return new ScenarioResponse(
                scenario.scenarioId(),
                scenario.scenarioName(),
                scenario.httpScenarios().get(0).url(),
                scenario.httpScenarios().get(0).method().name(),
                scenario.loadProfile().targetRps(),
                scenario.loadProfile().maxConcurrency(),
                scenario.loadProfile().estimatedDurationMs().orElse(0L) / 1000,
                scenario.loadProfile() instanceof io.loadtest.common.model.LoadProfile.RampUp ? "rampup"
                        : scenario.loadProfile() instanceof io.loadtest.common.model.LoadProfile.Spike ? "spike"
                        : scenario.loadProfile() instanceof io.loadtest.common.model.LoadProfile.StepUp ? "stepup" : "constant",
                state != null ? state.status().name() : "UNKNOWN",
                scenario.createdTimestampMs(),
                state != null && state.startedAt() != null ? state.startedAt().toEpochMilli() : 0,
                state != null && state.completedAt() != null ? state.completedAt().toEpochMilli() : 0,
                scenario.tags(),
                assignmentList != null ? assignmentList.stream().map(ScenarioAssignment::workerId).toList() : List.of()
        );
    }

    /**
     * Instance state record.
     */
    private record ScenarioInstanceState(
            String scenarioId,
            TestStatus status,
            Instant createdAt,
            Instant startedAt,
            Instant completedAt
    ) {}
}
