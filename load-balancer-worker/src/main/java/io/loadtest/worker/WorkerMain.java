package io.loadtest.worker;

import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.loadtest.worker.grpc.WorkerGrpcService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Worker Node Entry Point.
 *
 * JVM Configuration for Virtual Threads and ZGC:
 * java --enable-preview \\
 *      -XX:+UseZGC -XX:+ZGenerational \\
 *      -XX:+UnlockExperimentalVMOptions -XX:ZAllocationSpikeTolerance=5 \\
 *      -Xms512m -Xmx2048m \\
 *      -XX:ActiveProcessorCount=${CPU_CORES} \\
 *      -Djdk.virtualThreadScheduler.parallelism=${CPU_CORES} \\
 *      -Djdk.virtualThreadScheduler.maxPoolSize=${CPU_CORES*2} \\
 *      -jar load-balancer-worker.jar
 *
 * Flags Explained:
 * -XX:+UseZGC -XX:+ZGenerational
 *   Modern ZGC for ultra-low latency (<1ms pause times).
 *   Critical for accurate latency measurements - GC pauses would corrupt results.
 *
 * -XX:ZAllocationSpikeTolerance=5
 *   ZGC adapts to allocation rate. Spike tolerance prevents
 *   allocation stalls during burst load phases.
 *
 * -Djdk.virtualThreadScheduler.parallelism
 *   Carrier thread count = CPU cores (optimal for I/O-bound workloads).
 *   Each carrier thread hosts multiple virtual threads.
 *
 * Memory Configuration:
 *   - Minimum heap: 512MB (starts quickly, avoids early GC pressure)
 *   - Maximum heap: 2GB (fits most scenarios; scale horizontally if more needed)
 *
 * Launch Command Example:
 * docker run -e WORKER_ID=worker-1 \
 *            -e MASTER_HOST=master \
 *            -e MASTER_PORT=9090 \
 *            -e WORKER_PORT=50051 \
 *            -p 50051:50051 \
 *            loadtest/worker:latest
 */
public final class WorkerMain {

    private static final Logger log = LoggerFactory.getLogger(WorkerMain.class);

    private static final String VERSION = "1.0.0";
    private static final int DEFAULT_PORT = 50051;
    private static final int DEFAULT_MAX_INBOUND_MESSAGE_SIZE = 1024 * 1024 * 100; // 100MB

    // gRPC Server
    private Server grpcServer;
    private WorkerGrpcService workerService;

    // Shutdown coordination
    private final CountDownLatch shutdownLatch = new CountDownLatch(1);

    public static void main(String[] args) {
        WorkerMain worker = new WorkerMain();
        worker.start();
    }

    /**
     * Start the worker node.
     */
    public void start() {
        log.info("╔══════════════════════════════════════════════════════════╗");
        log.info("║     Distributed Load Testing - Worker Node               ║");
        log.info("║     Version: {}                                            ", VERSION);
        log.info("╚══════════════════════════════════════════════════════════╝");

        // Log JVM configuration
        logJvmConfiguration();

        // Read configuration from environment
        int port = Integer.parseInt(System.getenv().getOrDefault("WORKER_PORT", String.valueOf(DEFAULT_PORT)));
        String masterHost = System.getenv().getOrDefault("MASTER_HOST", "localhost");
        int masterPort = Integer.parseInt(System.getenv().getOrDefault("MASTER_PORT", "9090"));

        // Register shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread(this::stop, "shutdown-hook"));

        // Initialize gRPC service
        workerService = new WorkerGrpcService();

        // Build gRPC server
        grpcServer = ServerBuilder.forPort(port)
                .maxInboundMessageSize(DEFAULT_MAX_INBOUND_MESSAGE_SIZE)
                .addService(workerService)
                .build();

        try {
            // Start gRPC server
            grpcServer.start();
            log.info("gRPC server started on port {}", port);
            log.info("Worker ID: {}", workerService.getWorkerId());
            log.info("Master: {}:{}", masterHost, masterPort);

            // Start worker service
            workerService.start();

            // Wait for termination
            grpcServer.awaitTermination();

        } catch (Exception e) {
            log.error("Worker failed to start", e);
            System.exit(1);
        }
    }

    /**
     * Stop the worker node gracefully.
     */
    public void stop() {
        log.info("Shutting down worker...");

        // Stop worker service
        if (workerService != null) {
            workerService.stop();
        }

        // Stop gRPC server
        if (grpcServer != null) {
            grpcServer.shutdown();
            try {
                if (!grpcServer.awaitTermination(10, TimeUnit.SECONDS)) {
                    grpcServer.shutdownNow();
                }
            } catch (InterruptedException e) {
                grpcServer.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }

        // Signal completion
        shutdownLatch.countDown();
        log.info("Worker shutdown complete");
    }

    /**
     * Log JVM configuration for diagnostics.
     */
    private void logJvmConfiguration() {
        log.info("JVM Configuration:");
        log.info("  Java Version: {}", System.getProperty("java.version"));
        log.info("  JVM Name: {}", System.getProperty("java.vm.name"));
        log.info("  JVM Vendor: {}", System.getProperty("java.vm.vendor"));
        log.info("  Available CPUs: {}", Runtime.getRuntime().availableProcessors());
        log.info("  Max Memory: {} MB", Runtime.getRuntime().maxMemory() / (1024 * 1024));

        // Log virtual thread configuration
        String parallelism = System.getProperty("jdk.virtualThreadScheduler.parallelism");
        String maxPool = System.getProperty("jdk.virtualThreadScheduler.maxPoolSize");
        log.info("  Virtual Thread Scheduler:");
        log.info("    Parallelism: {}", parallelism != null ? parallelism : "default (CPU cores)");
        log.info("    Max Pool Size: {}", maxPool != null ? maxPool : "default");

        // Log ZGC status
        log.info("  GC Configuration: {}", System.getProperty("java.vm.gc"));
    }

    /**
     * Wait for shutdown to complete.
     */
    public void awaitTermination() throws InterruptedException {
        shutdownLatch.await();
    }
}
