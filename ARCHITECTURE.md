# Architecture Decision Records (ADRs)

This document captures the key architectural decisions for the Distributed Load Testing Platform.

---

## ADR-001: Virtual Threads over Reactive Frameworks

### Status
Accepted

### Context
We need to generate massive concurrent HTTP requests (hundreds of thousands per second).
Traditional approaches include:
1. **Thread-per-request with Platform Threads**: Simple blocking code, but limited by thread pool size
2. **Reactive/Await-based (WebFlux, Vert.x)**: Non-blocking I/O with event loops, efficient but complex code
3. **Virtual Threads (Project Loom)**: Best of both worlds - simple blocking code with massive concurrency

### Decision
Use Java 21 Virtual Threads (`Executors.newVirtualThreadPerTaskExecutor()`).

### Rationale
1. **Code Readability**: Blocking code is easier to understand and maintain than reactive streams
2. **Memory Efficiency**: Virtual threads consume ~1KB vs ~1MB for platform threads
3. **Scalability**: Can create millions of virtual threads without pool tuning
4. **Existing Code**: No changes needed to existing blocking I/O code (HttpClient, etc.)

### Consequences
- Requires Java 21+ runtime
- Pinned carrier threads during `synchronized` blocks (avoid in hot paths)
- Debugging stack traces may be deeper

---

## ADR-002: Lock-Free Metrics Collection with LongAdder

### Status
Accepted

### Context
We need to track request counts, success/failure rates, and latency distributions across
thousands of concurrent threads. Traditional synchronization approaches:
1. **AtomicLong**: CAS loops, degrades under contention
2. **synchronized blocks**: Mutual exclusion, kernel-level blocking
3. **ConcurrentHashMap**: Per-key locks, still has contention
4. **LongAdder**: Striped accumulators, no contention

### Decision
Use `java.util.concurrent.atomic.LongAdder` for all counters and a custom lock-free
histogram for latency percentiles.

### Rationale
```
Thread contention analysis at 100K RPS:

AtomicLong:
  Thread 1: CAS(counter, 100 -> 101) → Success
  Thread 2: CAS(counter, 100 -> 101) → Retry (value changed to 101)
  Thread 2: CAS(counter, 101 -> 102) → Success
  ...
  Thread N: Exponential backoff → ~40% throughput degradation

LongAdder:
  Thread 1: cells[0].increment() → No contention
  Thread 2: cells[1].increment() → No contention
  ...
  Thread N: sum() → Eventually consistent, acceptable for monitoring
```

### Consequences
- Read operations (`sum()`) are not atomic snapshots
- Slight inconsistency between totals is acceptable for dashboards
- Requires explicit periodic "snapshot" for precise totals

---

## ADR-003: Phaser for Synchronized Burst Testing

### Status
Accepted

### Context
Spike tests require all load-generator threads to start simultaneously.
Without synchronization:
- Thread 1 starts at T+0ms
- Thread 2 starts at T+2ms
- Thread N starts at T+500ms
Result: "Spike" becomes gradual ramp, defeating the purpose.

### Decision
Use `java.util.concurrent.Phaser` as a thread barrier with dynamic party registration.

### Rationale
```
Traditional CyclicBarrier:
  barrier.await() → Blocks fixed number of parties
  Cannot add threads after barrier creation

Phaser:
  phaser.register() → Add thread dynamically
  phaser.arriveAndAwaitAdvance() → Block at barrier
  phaser.arriveAndDeregister() → Release all threads
```

Implementation:
```java
// Each virtual thread
phaser.register();
phaser.arriveAndAwaitAdvance(); // "Starting line"
executeRequest();                // Begin immediately

// Coordinator thread
phaser.arriveAndDeregister();   // "Starting gun"
```

### Consequences
- Minor overhead (~0.1ms) for registration
- All threads within ~1ms start window
- Works with dynamically-discovered worker count

---

## ADR-004: gRPC Streaming for Metrics Transport

### Status
Accepted

### Context
Workers need to send request metrics back to Master for aggregation and storage.
Options:
1. **REST POST requests**: Simple but high overhead
2. **gRPC Unary**: Better but still per-message overhead
3. **gRPC Client Streaming**: Efficient multiplexed streaming
4. **Kafka/Pulsar**: Reliable but adds latency and complexity

### Decision
Use gRPC client streaming with 500ms batch windows.

### Rationale
```
Per-message overhead comparison (at 100K RPS):

REST POST:
  ~500 bytes HTTP header overhead per request
  100K RPS * 500 bytes = 50 MB/s just for headers

gRPC Unary:
  ~100 bytes HTTP/2 frame overhead per request
  100K RPS * 100 bytes = 10 MB/s overhead

gRPC Streaming + Batching:
  ~100 bytes frame overhead per BATCH
  Batches: 100K RPS * 0.5s / 500 metrics = 200 batches/s
  200 batches/s * 100 bytes = 20 KB/s overhead
  Savings: 99.96% reduction in overhead
```

### Consequences
- 500ms latency window for metrics (acceptable for dashboards)
- Requires flow control to prevent worker memory pressure
- Master must handle out-of-order batches

---

## ADR-005: Separated Master and Worker Modules

### Status
Accepted

### Context
The platform has two runtime components with different requirements:
- **Master**: Control plane, needs REST API, database connections, admin UI
- **Worker**: Data plane, needs minimal footprint for maximum throughput

### Decision
Separate Maven modules with different dependency profiles:
- Master: Spring Boot (web, data-redis, validation)
- Worker: Vanilla Java 21 (no Spring, minimal dependencies)
- Common: Shared protobufs, DTOs, parser

### Rationale
```
Memory footprint comparison:

Spring Boot Worker:
  - Spring context initialization: ~200ms
  - Base memory: ~150MB
  - Per-request overhead: Higher due to proxying

Vanilla Java Worker:
  - Startup: ~50ms
  - Base memory: ~30MB
  - Per-request overhead: Raw HTTP client

At 1M concurrent requests, memory matters:
  Spring Worker: 1M * 2KB + 150MB = 2.15GB
  Vanilla Worker: 1M * 1KB + 30MB = 1.03GB
```

### Consequences
- Workers must be manually managed (lifecycle, configuration)
- No Spring dependency injection in Workers
- More code in Worker for setup, but more control

---

## ADR-006: ZGC for Latency-Critical Garbage Collection

### Status
Accepted

### Context
Latency measurements must be accurate. Traditional GC pauses would corrupt results:
- G1GC: 50-200ms mixed collections
- ParallelGC: 100-500ms stop-the-world pauses
- ZGC: <1ms guaranteed pause time

### Decision
Configure ZGC with generational mode for all worker nodes.

### Rationale
```
Impact of GC pauses on P99 latency measurement:

Scenario: Target service responds at P99 = 50ms
  Without GC: Measured P99 = 50ms (accurate)
  
  With G1GC pause during request:
    Request arrives → GC kicks in → 150ms pause → Request completes
    Measured latency = 50ms (real) + 150ms (GC) = 200ms
    Result: P99 reported as 200ms (incorrect!)

With ZGC:
    All pauses < 1ms
    Measured latency = 50ms + 0.5ms = 50.5ms
    Result: P99 reported as ~50ms (accurate within 1%)
```

JVM Flags:
```bash
-XX:+UseZGC
-XX:+ZGenerational          # Generational mode (Java 21+)
-XX:ZAllocationSpikeTolerance=5  # Handle burst allocations
```

### Consequences
- Slightly higher memory overhead (10-15%)
- Better suited for throughput vs. memory-constrained environments
- Predictable latency guarantees

---

## ADR-007: Redis for Worker Registry

### Status
Accepted

### Context
Master needs to track which workers are available, healthy, and ready for work.
Options:
1. **In-memory Map**: Master-only, lost on restart
2. **Database**: Durable but slow for heartbeats
3. **Redis**: Fast, supports TTL expiration, supports pub/sub

### Decision
Use Redis Hash + TTL for worker registration and health tracking.

### Rationale
```
Worker registry requirements:
- Fast heartbeat updates (every 5 seconds per worker)
- Automatic expiration of stale workers (TTL)
- Cross-master visibility (multiple masters can share registry)
- Simple key-value operations

Redis structure:
  workers:list          → SET {worker_id_1, worker_id_2, ...}
  workers:{id}:info     → HASH {hostname, port, cores, status}
  workers:{id}:heartbeat → STRING (timestamp, ttl=30s)
```

### Consequences
- Redis becomes critical infrastructure (single point of failure without Sentinel)
- Heartbeat traffic: N workers * 5-second interval = N/5 writes per second
- Can be replaced with Consul or etcd if needed

---

## Summary

| Decision | Choice | Primary Benefit |
|----------|--------|-----------------|
| ADR-001 | Virtual Threads | Simplicity + Scalability |
| ADR-002 | LongAdder | Lock-free throughput |
| ADR-003 | Phaser | True synchronized bursts |
| ADR-004 | gRPC Streaming | Minimal metrics overhead |
| ADR-005 | Separated Modules | Worker memory efficiency |
| ADR-006 | ZGC | Accurate latency measurement |
| ADR-007 | Redis | Fast state management |
