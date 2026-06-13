package io.loadtest.master.grpc;

import io.grpc.stub.StreamObserver;
import io.loadtest.master.metrics.AggregatedMetricsStore;
import io.loadtest.master.registry.WorkerRegistry;
import io.loadtest.master.dto.WorkerInfo;
import io.loadtest.v1.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;

/**
 * Master gRPC Service implementation.
 *
 * Handles incoming gRPC calls from workers:
 * - RegisterWorker: Worker registration at startup
 * - WorkerHeartbeatStream: Bi-directional streaming for health monitoring
 * - StreamMetrics: High-throughput metrics streaming
 */
@Service
public class MasterGrpcService extends MasterServiceGrpc.MasterServiceImplBase {

    private static final Logger log = LoggerFactory.getLogger(MasterGrpcService.class);

    private final WorkerRegistry workerRegistry;
    private final AggregatedMetricsStore metricsStore;

    public MasterGrpcService(WorkerRegistry workerRegistry, AggregatedMetricsStore metricsStore) {
        this.workerRegistry = workerRegistry;
        this.metricsStore = metricsStore;
    }

    // ============================================================
    // WORKER REGISTRATION
    // ============================================================

    @Override
    public void registerWorker(WorkerRegistration request, StreamObserver<WorkerStateUpdate> responseObserver) {
        log.info("Registering worker: id={}, hostname={}", request.getWorkerId(), request.getHostname());

        try {
            WorkerInfo workerInfo = new WorkerInfo(
                    request.getWorkerId(),
                    request.getHostname(),
                    request.getPort(),
                    request.getInitialStatus().name(),
                    request.getAvailableCores(),
                    request.getAvailableMemoryMb(),
                    request.getJavaVersion(),
                    Instant.now(),
                    Instant.now(),
                    0,
                    0,
                    0,
                    0
            );

            workerRegistry.register(workerInfo);

            responseObserver.onNext(WorkerStateUpdate.newBuilder()
                    .setWorkerId(request.getWorkerId())
                    .setAccepted(true)
                    .build());
            responseObserver.onCompleted();

            log.info("Worker registered successfully: id={}", request.getWorkerId());

        } catch (Exception e) {
            log.error("Failed to register worker: id={}", request.getWorkerId(), e);
            responseObserver.onNext(WorkerStateUpdate.newBuilder()
                    .setWorkerId(request.getWorkerId())
                    .setAccepted(false)
                    .setRejectionReason(e.getMessage())
                    .build());
            responseObserver.onCompleted();
        }
    }

    // ============================================================
    // HEARTBEAT STREAMING
    // ============================================================

    @Override
    public StreamObserver<WorkerHeartbeat> workerHeartbeatStream(StreamObserver<WorkerStateUpdate> responseObserver) {
        return new StreamObserver<>() {
            private String workerId;

            @Override
            public void onNext(WorkerHeartbeat heartbeat) {
                this.workerId = heartbeat.getWorkerId();

                // Update heartbeat timestamp
                workerRegistry.updateHeartbeat(workerId);

                // Update worker status
                String status = heartbeat.getStatus().name();
                workerRegistry.setWorkerStatus(workerId, status);

                // Log health indicators periodically
                if (heartbeat.getActiveThreads() > 0) {
                    log.debug("Worker heartbeat: id={}, status={}, threads={}, cpu={}%",
                            workerId, status, heartbeat.getActiveThreads(),
                            heartbeat.getCpuUsagePercent());
                }

                // Send acknowledgment
                responseObserver.onNext(WorkerStateUpdate.newBuilder()
                        .setWorkerId(workerId)
                        .setAccepted(true)
                        .build());
            }

            @Override
            public void onError(Throwable t) {
                log.warn("Heartbeat stream error from worker {}: {}", workerId, t.getMessage());
            }

            @Override
            public void onCompleted() {
                log.info("Heartbeat stream completed for worker: {}", workerId);
                responseObserver.onCompleted();
            }
        };
    }

    // ============================================================
    // METRICS STREAMING
    // ============================================================

    /**
     * High-throughput metrics streaming from workers.
     *
     * Workers stream batches of metrics to the Master.
     * Each batch contains up to 500ms of request data.
     *
     * Performance: At 100K RPS with 500ms batches = 200 metrics per batch.
     * gRPC streaming handles this efficiently with flow control.
     */
    @Override
    public StreamObserver<MetricsBatch> streamMetrics(StreamObserver<AckResponse> responseObserver) {
        return new StreamObserver<>() {
            private String workerId;
            private long totalProcessed = 0;

            @Override
            public void onNext(MetricsBatch batch) {
                this.workerId = batch.getWorkerId();

                // Process each metric in the batch
                for (RequestMetric metric : batch.getMetricsList()) {
                    metricsStore.recordMetric(
                            metric.getScenarioId(),
                            (long) metric.getLatencyMs() * 1000, // Convert back to microseconds
                            metric.getStatusCode(),
                            metric.getSuccess(),
                            metric.getRequestBytes(),
                            metric.getResponseBytes()
                    );
                    totalProcessed++;
                }

                // Send acknowledgment every 10 batches (reduces response overhead)
                if (totalProcessed % (batch.getTotalRequestsInBatch() * 10) < batch.getTotalRequestsInBatch()) {
                    responseObserver.onNext(AckResponse.newBuilder()
                            .setSuccess(true)
                            .setMessage("Processed " + totalProcessed + " metrics")
                            .build());
                }
            }

            @Override
            public void onError(Throwable t) {
                log.error("Metrics stream error from worker {}: {}", workerId, t.getMessage());
            }

            @Override
            public void onCompleted() {
                log.info("Metrics stream completed from worker {}: total={}", workerId, totalProcessed);
                responseObserver.onNext(AckResponse.newBuilder()
                        .setSuccess(true)
                        .setMessage("Final: processed " + totalProcessed + " metrics")
                        .build());
                responseObserver.onCompleted();
            }
        };
    }

    // ============================================================
    // WORK ASSIGNMENT
    // ============================================================

    @Override
    public void requestWorkAssignment(WorkRequest request, StreamObserver<WorkAssignment> responseObserver) {
        String workerId = request.getWorkerId();

        log.debug("Work assignment request from worker: {}", workerId);

        // In a full implementation, this would check the scheduler
        // for pending scenarios that need workers

        responseObserver.onNext(WorkAssignment.newBuilder()
                .setHasWork(false)
                .build());
        responseObserver.onCompleted();
    }
}
