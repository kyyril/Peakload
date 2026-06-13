package io.loadtest.master.registry;

import io.loadtest.master.dto.WorkerInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Redis-backed Worker Registry for tracking connected worker nodes.
 *
 * Architecture:
 * ┌──────────────────────────────────────────────────────────────┐
 * │                    WorkerRegistry                            │
    │                                                               │
 * │   Redis Keys:                                                │
 * │   workers:list         → SET<worker_id>                      │
 * │   workers:{id}:info    → HASH (hostname, port, etc.)         │
 * │   workers:{id}:status → STRING (IDLE/RUNNING/DRAINING)      │
 * │   workers:{id}:heartbeat → STRING (timestamp)               │
--│
--│
 * │   TTL: All keys expire after 30s of no heartbeat             │
    │                                                               │
 * │   Operations:                                                 │
    │   register()      → Add worker to registry                   │
 * │   heartbeat()      → Update heartbeat timestamp              │
 * │   unregister()     → Remove worker from registry             │
--│
    │   getAllWorkers()  → List all active workers                │
--│
    │   assign(capacity) → Find workers with given capacity       │
 * └──────────────────────────────────────────────────────────────┘
 *
 * Thread Safety:
 * - All operations are atomic using Redis commands
 * - TTL provides automatic cleanup of stale workers
 * - No distributed locking needed (at-least-once semantics suffice)
 */
@Repository
public class WorkerRegistry {

    private static final Logger log = LoggerFactory.getLogger(WorkerRegistry.class);

    private static final String WORKERS_SET_KEY = "workers:list";
    private static final String WORKER_INFO_PREFIX = "workers:";
    private static final String WORKER_STATUS_PREFIX = "workers:status:";
    private static final String WORKER_HEARTBEAT_PREFIX = "workers:heartbeat:";
    private static final Duration REGISTRATION_TTL = Duration.ofSeconds(30);
    private static final Duration HEARTBEAT_TIMEOUT = Duration.ofSeconds(10);

    private final RedisTemplate<String, Object> redisTemplate;

    public WorkerRegistry(RedisTemplate<String, Object> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    // ============================================================
    // REGISTRATION
    // ============================================================

    /**
     * Register a new worker node.
     */
    public void register(WorkerInfo workerInfo) {
        String workerId = workerInfo.workerId();
        log.info("Registering worker: {} ({})", workerId, workerInfo.hostname());

        // Add to worker set
        redisTemplate.opsForSet().add(WORKERS_SET_KEY, workerId);

        // Store worker info
        String infoKey = WORKER_INFO_PREFIX + workerId + ":info";
        Map<String, Object> info = new HashMap<>();
        info.put("workerId", workerId);
        info.put("hostname", workerInfo.hostname());
        info.put("port", workerInfo.port());
        info.put("availableCores", workerInfo.availableCores());
        info.put("availableMemoryMb", workerInfo.availableMemoryMb());
        info.put("javaVersion", workerInfo.javaVersion());
        info.put("registeredAt", workerInfo.registeredAt().toString());
        redisTemplate.opsForHash().putAll(infoKey, info);

        // Set status
        setWorkerStatus(workerId, workerInfo.status());

        // Set heartbeat
        updateHeartbeat(workerId);

        // Set TTL on all keys
        refreshTTL(workerId);
    }

    /**
     * Unregister a worker.
     */
    public void unregister(String workerId) {
        log.info("Unregistering worker: {}", workerId);

        redisTemplate.opsForSet().remove(WORKERS_SET_KEY, workerId);
        redisTemplate.delete(WORKER_INFO_PREFIX + workerId + ":info");
        redisTemplate.delete(WORKER_STATUS_PREFIX + workerId);
        redisTemplate.delete(WORKER_HEARTBEAT_PREFIX + workerId);
    }

    // ============================================================
    // HEARTBEAT
    // ============================================================

    /**
     * Update worker heartbeat timestamp.
     */
    public void updateHeartbeat(String workerId) {
        String key = WORKER_HEARTBEAT_PREFIX + workerId;
        redisTemplate.opsForValue().set(key, Instant.now().toString(), REGISTRATION_TTL);
    }

    /**
     * Check if worker is still alive (heartbeat within timeout).
     */
    public boolean isAlive(String workerId) {
        String key = WORKER_HEARTBEAT_PREFIX + workerId;
        Object lastHeartbeat = redisTemplate.opsForValue().get(key);
        if (lastHeartbeat == null) {
            return false;
        }

        Instant heartbeatTime = Instant.parse(lastHeartbeat.toString());
        return heartbeatTime.plus(HEARTBEAT_TIMEOUT).isAfter(Instant.now());
    }

    // ============================================================
    // STATUS MANAGEMENT
    // ============================================================

    /**
     * Set worker status.
     */
    public void setWorkerStatus(String workerId, String status) {
        String key = WORKER_STATUS_PREFIX + workerId;
        redisTemplate.opsForValue().set(key, status, REGISTRATION_TTL);
    }

    /**
     * Get worker status.
     */
    public String getWorkerStatus(String workerId) {
        String key = WORKER_STATUS_PREFIX + workerId;
        Object status = redisTemplate.opsForValue().get(key);
        return status != null ? status.toString() : "UNKNOWN";
    }

    // ============================================================
    // QUERYING
    // ============================================================

    /**
     * Get all registered workers.
     */
    public List<WorkerInfo> getAllWorkers() {
        Set<Object> workerIds = redisTemplate.opsForSet().members(WORKERS_SET_KEY);
        if (workerIds == null || workerIds.isEmpty()) {
            return List.of();
        }

        List<WorkerInfo> workers = new ArrayList<>();
        for (Object id : workerIds) {
            getWorker(id.toString()).ifPresent(workers::add);
        }

        return workers;
    }

    /**
     * Get a specific worker by ID.
     */
    public Optional<WorkerInfo> getWorker(String workerId) {
        String infoKey = WORKER_INFO_PREFIX + workerId + ":info";
        Map<Object, Object> info = redisTemplate.opsForHash().entries(infoKey);

        if (info.isEmpty()) {
            return Optional.empty();
        }

        WorkerInfo workerInfo = WorkerInfo.builder()
                .workerId(getString(info, "workerId"))
                .hostname(getString(info, "hostname"))
                .port(getInt(info, "port"))
                .status(getWorkerStatus(workerId))
                .availableCores(getInt(info, "availableCores"))
                .availableMemoryMb(getLong(info, "availableMemoryMb"))
                .javaVersion(getString(info, "javaVersion"))
                .registeredAt(Instant.parse(getString(info, "registeredAt")))
                .lastHeartbeat(getLastHeartbeat(workerId))
                .build();

        return Optional.of(workerInfo);
    }

    /**
     * Find workers available for scenario assignment.
     */
    public List<WorkerInfo> findAvailableWorkers(int requiredCapacity) {
        return getAllWorkers().stream()
                .filter(w -> "IDLE".equals(w.status()))
                .filter(this::isAlive)
                .sorted(Comparator.comparingInt(WorkerInfo::availableCores).reversed())
                .limit(10)
                .toList();
    }

    // ============================================================
    // HELPER METHODS
    // ============================================================

    private void refreshTTL(String workerId) {
        redisTemplate.expire(WORKER_INFO_PREFIX + workerId + ":info", REGISTRATION_TTL);
        redisTemplate.expire(WORKER_STATUS_PREFIX + workerId, REGISTRATION_TTL);
    }

    private String getString(Map<Object, Object> map, String key) {
        Object value = map.get(key);
        return value != null ? value.toString() : "";
    }

    private int getInt(Map<Object, Object> map, String key) {
        Object value = map.get(key);
        if (value == null) return 0;
        return Integer.parseInt(value.toString());
    }

    private long getLong(Map<Object, Object> map, String key) {
        Object value = map.get(key);
        if (value == null) return 0L;
        return Long.parseLong(value.toString());
    }

    private Instant getLastHeartbeat(String workerId) {
        String key = WORKER_HEARTBEAT_PREFIX + workerId;
        Object value = redisTemplate.opsForValue().get(key);
        return value != null ? Instant.parse(value.toString()) : Instant.MIN;
    }
}
