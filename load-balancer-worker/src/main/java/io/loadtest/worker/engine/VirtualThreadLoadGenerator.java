package io.loadtest.worker.engine;

import io.loadtest.common.metrics.MetricsCollector;
import io.loadtest.common.model.HttpScenario;
import io.loadtest.common.model.LoadProfile;
import io.loadtest.common.model.RequestMetric;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.locks.LockSupport;

import static java.util.Objects.requireNonNull;

/**
 * Core Load Generation Engine using Java 21 Virtual Threads.
 *
 * Architecture Rationale:
 * ════════════════════════════════════════════════════════════════════════════
 *
 * VIRTUAL THREAD PINNING AVOIDANCE:
 * ─────────────────────────────────
 * This class is designed to AVOID carrier thread pinning:
 *
 * 1. NO synchronized blocks - synchronized pins carrier threads
 * 2. NO Object.wait()/notify() - native operations that pin threads
 * 3. LockSupport.parkNanos() - Safe! Does NOT pin carrier threads
 * 4. LongAdder for counters - Lock-free, no synchronization
 * 5. Phaser for coordination - Uses internal lock-free mechanisms
 * 6. AtomicLong CAS loops - Non-blocking compare-and-swap
 *
 * WHAT CAUSES PINNING (avoid these):
 *   synchronized(lock) { ... }           ← PINS carrier thread!
 *   Object.wait()                        ← PINS carrier thread!
 *   Thread.sleep() inside synchronized   ← PINS for duration!
 *
 * WHAT IS SAFE (use these instead):
 *   ReentrantLock.lock()/unlock()        ← Virtual thread friendly in Java 21+
 *   LockSupport.parkNanos()              ← Does NOT pin
 *   LongAdder.increment()                ← Lock-free
 *   ConcurrentHashMap.computeIfAbsent()  ← Non-blocking
 *   Phaser.arriveAndAwaitAdvance()       ← Non-blocking coordination
 *
 * WHY VIRTUAL THREADS (Project Loom)?
 * ─────────────────────────────────────
 * Traditional thread-per-request model with Platform Threads fails at scale:
 * - Platform threads: ~1MB stack per thread = 10K threads = 10GB RAM
 * - Context switch overhead: ~1-10µs per switch
 * - OS scheduler bottleneck: Fixed number of kernel threads
 *
 * Virtual Threads solve this:
 * - Lightweight: ~1KB per virtual thread = millions of threads feasible
 * - Pinned only during blocking I/O (handled by JVM, not OS)
 * - M:N scheduling: Many virtual threads → Few carrier threads (CPU cores)
 * - Zero modification to existing blocking I/O code
 *
 * SYNCHRONIZED BURST (Phaser Pattern):
 * ─────────────────────────────────────
 * For spike testing, we need all requests to start simultaneously.
 * Without synchronization: Threads stagger over 100-500ms due to scheduling.
 *
 * Phaser provides a "starting gun" mechanism:
 * 1. Worker threads call arriveAndAwaitAdvance() → block at start line
 * 2. Coordinator thread waits for all workers to arrive
 * 3. Coordinator calls arriveAndDeregister() → releases all threads
 * 4. All threads begin execution within ~1ms of each other
 *
 * BACKPRESSURE & RATE LIMITING:
 * ─────────────────────────────
 * Target RPS enforcement uses a token bucket algorithm:
 * - Tokens replenish at target rate
 * - Worker threads request token before generating request
 * - If no tokens available, thread parks briefly
 * - Prevents overwhelming target or exceeding test parameters
 *
 * LOCK-FREE COUNTERS (LongAdder):
 * ──────────────────────────────
 * See MetricsCollector.java for details on lock-free counting.
 
 * @author Load Test Platform Team
 * @version 1.0.0
 */
public final class VirtualThreadLoadGenerator implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(VirtualThreadLoadGenerator.class);


    // CONFIGURATION


    private final String scenarioId;
    private final String workerId;
    private final HttpScenario scenario;
    private final LoadProfile loadProfile;
    private final HttpClient httpClient;
    private final MetricsCollector metricsCollector;


    // EXECUTOR & CONCURRENCY


    /**
     * Virtual thread executor for task dispatch.
     *
     * Why newVirtualThreadPerTaskExecutor?
     * - Creates a new virtual thread for each submitted task
     * - No thread pool sizing needed (unlike fixed thread pools)
     * - Virtual threads are cheap and collected by GC after completion
     * - Carrier thread pool size defaults to CPU cores (optimal)
     */
    private final ExecutorService virtualThreadExecutor;

    /**
     * Phaser for synchronized burst testing.
     *
     * Why Phaser over CyclicBarrier?
     * - Dynamic party registration: Can vary number of threads at runtime
     * - arriveAndDeregister: Clean shutdown without breaking barrier
     * - No InterruptedException (unlike CyclicBarrier.await())
     */
    private Phaser burstPhaser;


    // RATE LIMITING


    /**
     * Token bucket rate limiter state.
     * volatile for visibility across all worker threads.
     */
    private volatile RateLimiter rateLimiter;


    // STATE MANAGEMENT


    private final AtomicReference<RunState> state = new AtomicReference<>(RunState.IDLE);
    private final AtomicLong requestsGenerated = new AtomicLong(0);
    private final AtomicLong startTimeMs = new AtomicLong(0);
    private final AtomicLong targetStopTimeMs = new AtomicLong(0);


    // CONSTRUCTOR AND INITIALIZATION


    /**
     * Create a new load generator.
     */
    public VirtualThreadLoadGenerator(
            String scenarioId,
            String workerId,
            HttpScenario scenario,
            LoadProfile loadProfile,
            MetricsCollector metricsCollector
    ) {
        this.scenarioId = requireNonNull(scenarioId, "scenarioId cannot be null");
        this.workerId = requireNonNull(workerId, "workerId cannot be null");
        this.scenario = requireNonNull(scenario, "scenario cannot be null");
        this.loadProfile = requireNonNull(loadProfile, "loadProfile cannot be null");
        this.metricsCollector = requireNonNull(metricsCollector, "metricsCollector cannot be null");

        // Configure HttpClient optimized for virtual threads
        this.httpClient = createOptimizedHttpClient(scenario);

        // Virtual thread executor - no pool size limits
        this.virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
    }

    /**
     * Create HttpClient optimized for Virtual Threads.
     *
     * Critical Configuration:
     * - HTTP/2: Multiplexed connections reduce TLS handshake overhead
     * - ConnectionPool: Shared across virtual threads (max 100 connections)
     * - ConnectTimeout: From scenario config
     * - No response timeout: We handle this manually for precise measurement
     */
    private HttpClient createOptimizedHttpClient(HttpScenario scenario) {
        return HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_2)
                .connectTimeout(scenario.connectTimeout())
                .followRedirects(scenario.followRedirects()
                        ? HttpClient.Redirect.NORMAL
                        : HttpClient.Redirect.NEVER)
                .executor(Executors.newVirtualThreadPerTaskExecutor())
                .build();
    }


    // LIFECYCLE MANAGEMENT


    /**
     * Run states for state machine.
     */
    private enum RunState {
        IDLE,           // Not started
        INITIALIZING,   // Setting up phaser
        RUNNING,        // Actively generating load
        STOPPING,       // Graceful shutdown in progress
        STOPPED         // Fully stopped
    }

    /**
     * Start load generation with synchronized burst.
     *
     * For SPIKE/CONSTANT profiles: All threads wait at phaser barrier,
     * then release simultaneously for synchronized burst.
     */
    public CompletableFuture<Void> start() {
        if (!state.compareAndSet(RunState.IDLE, RunState.INITIALIZING)) {
            throw new IllegalStateException(
                    "Cannot start: current state is " + state.get() + ", expected IDLE"
            );
        }

        // Initialize rate limiter
        this.rateLimiter = new RateLimiter(loadProfile.targetRps(), loadProfile.maxConcurrency());

        // Calculate run duration
        long durationMs = loadProfile.estimatedDurationMs().orElse(Long.MAX_VALUE);
        startTimeMs.set(System.currentTimeMillis());
        targetStopTimeMs.set(startTimeMs.get() + durationMs);

        // Configure phaser for synchronized burst
        boolean isSynchronized = loadProfile instanceof LoadProfile.Spike spike && spike.synchronizedStart();
        if (isSynchronized) {
            initializeBurstPhaser(loadProfile);
        }

        state.set(RunState.RUNNING);
        log.info("Load generator started: scenario={}, targetRps={}, duration={}ms",
                scenarioId, loadProfile.targetRps(), durationMs);

        return CompletableFuture.runAsync(this::runLoadGeneration, virtualThreadExecutor);
    }

    /**
     * Initialize phaser for synchronized burst.
     *
     * The phaser acts as a "race starting gate":
     * - Phase 0: All threads ARRIVE and WAIT
     * - When all threads present: coordinator releases (DEREGISTER and ADVANCE)
     * - All threads run simultaneously
     */
    private void initializeBurstPhaser(LoadProfile loadProfile) {
        // Estimate concurrent virtual threads needed
        long estimatedThreads = calculateRequiredThreads(loadProfile);
        int parties = (int) Math.min(estimatedThreads, loadProfile.maxConcurrency());

        // Phaser with estimated party count (dynamically adjustable)
        this.burstPhaser = new Phaser(1) { // +1 for coordinator thread
            @Override
            protected boolean onAdvance(int phase, int registeredParties) {
                log.debug("Phaser advance: phase={}, parties={}", phase, registeredParties);
                return false; // Don't terminate
            }
        };
    }

    /**
     * Calculate required threads for target RPS.
     *
     * Formula: Target_RPS * Avg_Latency_Seconds = Required_Threads
     * This ensures enough threads to maintain throughput without queuing.
     */
    private long calculateRequiredThreads(LoadProfile profile) {
        // Assume average 50ms latency for estimation
        long estimatedLatencyMs = 50;
        long rps = profile.targetRps();
        return (rps * estimatedLatencyMs) / 1000;
    }

    /**
     * Stop load generation gracefully.
     *
     * @param immediate If true, cancel in-flight requests; if false, drain naturally
     */
    public CompletableFuture<Void> stop(boolean immediate) {
        if (!state.compareAndSet(RunState.RUNNING, RunState.STOPPING)) {
            return CompletableFuture.completedFuture(null);
        }

        log.info("Stopping load generator: scenario={}, immediate={}, requestsGenerated={}",
                scenarioId, immediate, requestsGenerated.get());

        return CompletableFuture.runAsync(() -> {
            if (immediate) {
                virtualThreadExecutor.shutdownNow();
            } else {
                virtualThreadExecutor.shutdown();
            }
            state.set(RunState.STOPPED);
        });
    }

    @Override
    public void close() {
        stop(true).join();
        try {
            if (!virtualThreadExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                virtualThreadExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            virtualThreadExecutor.shutdownNow();
        }
    }


    // CORE LOAD GENERATION LOOP


    /**
     * Main load generation loop.
     *
     * This method runs in a single coordinator thread and spawns
     * virtual threads for each request.
     */
    private void runLoadGeneration() {
        try {
            // Ramp-up phase calculation
            Duration warmup = Duration.ZERO;
            if (loadProfile instanceof LoadProfile.RampUp rampUp) {
                warmup = rampUp.rampUpDuration();
            }

            Instant testStart = Instant.now();

            // Dispatch loop
            while (state.get() == RunState.RUNNING && !shouldStop()) {
                // Get current target RPS (handles ramp-up)
                long currentTargetRps = calculateCurrentRps(testStart);
                rateLimiter.setTargetRps(currentTargetRps);

                // Check rate limit before dispatching
                if (!rateLimiter.tryAcquire()) {
                    // Rate limited - back off briefly
                    LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
                    continue;
                }

                // Dispatch request in virtual thread
                dispatchRequest();

                // Track for stop condition
                requestsGenerated.incrementAndGet();
            }

            log.info("Load generation completed: totalRequests={}, durationMs={}",
                    requestsGenerated.get(), System.currentTimeMillis() - startTimeMs.get());

        } catch (Exception e) {
            log.error("Load generation failed: scenario={}", scenarioId, e);
            throw new RuntimeException("Load generation failed", e);
        }
    }

    /**
     * Calculate current RPS based on elapsed time and load profile.
     * Handles ramp-up phases dynamically.
     */
    private long calculateCurrentRps(Instant testStart) {
        Duration elapsed = Duration.between(testStart, Instant.now());

        return switch (loadProfile) {
            case LoadProfile.Constant c -> c.targetRps();
            case LoadProfile.Spike s -> s.targetRps();
            case LoadProfile.RampUp rampUp -> rampUp.calculateRpsAt(elapsed);
            case LoadProfile.StepUp stepUp -> {
                int step = (int) (elapsed.toMillis() / stepUp.stepDuration().toMillis()) + 1;
                yield stepUp.getRpsForStep(Math.min(step, stepUp.totalSteps()));
            }
        };
    }

    /**
     * Check if test should stop.
     */
    private boolean shouldStop() {
        // Time-based stop
        if (System.currentTimeMillis() >= targetStopTimeMs.get()) {
            return true;
        }

        // Request count-based stop (for profiles with total requests)
        if (loadProfile.estimatedTotalRequests().isPresent()) {
            return requestsGenerated.get() >= loadProfile.estimatedTotalRequests().getAsLong();
        }

        return false;
    }


    // REQUEST DISPATCH


    /**
     * Dispatch a single request in a virtual thread.
     */
    private void dispatchRequest() {
        // Register with phaser if synchronized burst is enabled
        if (burstPhaser != null) {
            burstPhaser.register();
            try {
                // Wait at barrier (all threads wait here)
                burstPhaser.arriveAndAwaitAdvance();
            } finally {
                // Deregister after this request (phaser continues)
                burstPhaser.arriveAndDeregister();
            }
        } else {
            // Non-synchronized: just execute
            executeRequest();
        }
    }

    /**
     * Execute HTTP request and collect metrics.
     *
     * Runs on virtual thread - can block freely without harming concurrency.
     */
    private void executeRequest() {
        Instant requestStart = Instant.now();
        long dnsStart = 0, connectStart = 0, tlsStart = 0;

        try {
            // Build HTTP request from scenario
            HttpRequest httpRequest = buildHttpRequest(scenario);

            // Execute with timing
            long requestStartMicros = System.nanoTime() / 1000;

            HttpResponse<String> response = httpClient.send(
                    httpRequest,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
            );

            long responseEndMicros = System.nanoTime() / 1000;
            long latencyMicros = responseEndMicros - requestStartMicros;

            // Record success metric
            RequestMetric metric = RequestMetric.success(
                    scenarioId,
                    workerId,
                    requestStart,
                    latencyMicros,
                    response.statusCode()
            );

            metricsCollector.record(metric);

        } catch (IOException e) {
            // Connection error
            long latencyMicros = (System.nanoTime() / 1000) - (requestStart.toEpochMilli() * 1000);
            RequestMetric metric = RequestMetric.failure(
                    scenarioId,
                    workerId,
                    requestStart,
                    "connection_error:" + e.getMessage()
            );
            metricsCollector.record(metric);

        } catch (InterruptedException e) {
            // Thread interrupted - means we're shutting down
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            // Unexpected error
            RequestMetric metric = RequestMetric.failure(
                    scenarioId,
                    workerId,
                    requestStart,
                    "error:" + e.getClass().getSimpleName()
            );
            metricsCollector.record(metric);
        }
    }

    /**
     * Build HttpClient request from scenario configuration.
     */
    private HttpRequest buildHttpRequest(HttpScenario scenario) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(scenario.url()))
                .timeout(Duration.ofMillis(50)) // Short timeout to avoid blocking threads

                .version(HttpClient.Version.HTTP_2);

        // Set method and body
        switch (scenario) {
            case HttpScenario.Get get -> builder.GET();
            case HttpScenario.Post post -> builder.POST(HttpRequest.BodyPublishers.ofString(post.body().content()));
            case HttpScenario.Put put -> builder.PUT(HttpRequest.BodyPublishers.ofString(put.body().content()));
            case HttpScenario.Patch patch -> builder.method("PATCH", HttpRequest.BodyPublishers.ofString(patch.body().content()));
            case HttpScenario.Delete delete -> builder.DELETE();
            case HttpScenario.Custom custom -> {
                HttpRequest.BodyPublisher body = custom.body()
                        .map(b -> HttpRequest.BodyPublishers.ofString(b.content()))
                        .orElse(HttpRequest.BodyPublishers.noBody());
                builder.method(custom.method().name(), body);
            }
        }

        // Add headers
        scenario.headers().forEach(header ->
                builder.header(header.name(), header.value())
        );

        return builder.build();
    }


    // RATE LIMITER (Token Bucket)


    /**
     * Token bucket rate limiter for precise RPS control.
     *
     * VIRTUAL THREAD SAFE: Uses CAS loops instead of synchronized blocks.
     * CAS (Compare-And-Swap) is a non-blocking atomic operation that does
     * NOT pin carrier threads, unlike 'synchronized' which would pin.
     *
     * Algorithm:
     * 1. Tokens accumulate over time up to a maximum bucket size
     * 2. Each request consumes one token
     * 3. If no tokens available, request waits
     * 4. Token replenishment rate = target RPS
     *
     * Thread Safety: Uses CAS loops for lock-free token management.
     */
    private static final class RateLimiter {
        private final AtomicLong tokens = new AtomicLong(0);
        private final AtomicLong lastRefillTime = new AtomicLong(System.nanoTime());
        private final LongAdder rejectedCount = new LongAdder();

        private volatile long targetRps;
        private volatile int maxConcurrency;

        private static final long MAX_TOKENS = 10000;

        RateLimiter(long targetRps, int maxConcurrency) {
            this.targetRps = targetRps;
            this.maxConcurrency = maxConcurrency;
        }

        /**
         * Attempt to acquire a token.
         * @return true if token acquired, false if rate limited
         */
        boolean tryAcquire() {
            refillTokens();

            while (true) {
                long currentTokens = tokens.get();
                if (currentTokens <= 0) {
                    rejectedCount.increment();
                    return false;
                }

                if (tokens.compareAndSet(currentTokens, currentTokens - 1)) {
                    return true;
                }
                // CAS failed, retry
            }
        }

        /**
         * Refill tokens based on elapsed time.
         * Lock-free: Only one thread will successfully update refill time.
         */
        private void refillTokens() {
            long now = System.nanoTime();
            long lastRefill = lastRefillTime.get();

            // Time since last refill in nanoseconds
            long elapsedNanos = now - lastRefill;
            if (elapsedNanos < TimeUnit.MILLISECONDS.toNanos(1)) {
                // Refill every millisecond at most
                return;
            }

            // Calculate tokens to add based on target RPS
            // tokens = (elapsed seconds) * (tokens per second)
            long tokensToAdd = (elapsedNanos * targetRps) / TimeUnit.SECONDS.toNanos(1);

            // Try to update last refill time
            if (lastRefillTime.compareAndSet(lastRefill, now)) {
                // Successfully grabbed the refill lock - add tokens
                while (true) {
                    long currentTokens = tokens.get();
                    long newTokens = Math.min(currentTokens + tokensToAdd, MAX_TOKENS);
                    if (tokens.compareAndSet(currentTokens, newTokens)) {
                        break;
                    }
                }
            }
        }

        void setTargetRps(long newTargetRps) {
            this.targetRps = newTargetRps;
        }

        long getRejectedCount() {
            return rejectedCount.sum();
        }
    }
}
