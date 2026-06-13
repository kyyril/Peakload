# Virtual Threads Pinning Avoidance Guidelines

## Mengapa `synchronized` Harus Dihindari

Virtual Threads di Java 21 berjalan di atas "carrier threads" (platform threads). Ketika sebuah virtual thread melakukan operasi yang mem-pin carrier thread, virtual thread tersebut tidak dapat dipindahkan ke carrier thread lain, mengurangi efisiensi scheduling.

### Operasi yang Mem-Pin Carrier Thread:

1. **`synchronized` blocks/methods** - Virtual thread terpin saat memasuki synchronized block
2. **`Object.wait()`** - Native operation yang mem-pin
3. **`Thread.sleep()` inside synchronized** - Mem-pin selama durasi sleep
4. **Blocking I/O inside synchronized** - File operations, socket operations

### Alternatif untuk `synchronized`:

| Pattern Lama | Pattern Baru (Virtual Thread Safe) |
|--------------|-----------------------------------|
| `synchronized(lock) { ... }` | `lock.lock(); try { ... } finally { lock.unlock(); }` |
| `synchronized method()` | `ReentrantLock` dengan `lock()`/`unlock()` |
| `wait()`/`notify()` | `Condition.await()`/`Condition.signal()` |
| `AtomicInteger.getAndIncrement()` inside synchronized | `LongAdder.increment()` (lock-free) |

## Implementasi di Platform Ini

### 1. MetricsCollector - Lock-Free dengan LongAdder
```java
// BENAR: Lock-free, tidak mem-pin carrier thread
private final LongAdder totalRequests = new LongAdder();
totalRequests.increment(); // No synchronization!

// SALAH: Akan mem-pin carrier thread
private final AtomicLong totalRequests = new AtomicLong();
synchronized(this) { totalRequests.incrementAndGet(); } // PIN!
```

### 2. Phaser untuk Synchronized Burst
```java
// BENAR: Phaser tidak menggunakan synchronized
Phaser phaser = new Phaser(parties);
phaser.arriveAndAwaitAdvance(); // Uses internal lock-free mechanisms

// SALAH: Object.wait() di dalam synchronized akan mem-pin
synchronized(lock) {
    while (!condition) {
        lock.wait(); // PIN! Carrier thread blocked
    }
}
```

### 3. Rate Limiter dengan CAS
```java
// BENAR: CAS loop, tidak mem-pin
while (true) {
    long current = tokens.get();
    if (tokens.compareAndSet(current, current - 1)) {
        return true;
    }
}

// SALAH: synchronized increment
synchronized(this) {
    tokens--;
    return tokens >= 0;
}
```

### 4. ConcurrentHashMap untuk Shared State
```java
// BENAR: ConcurrentHashMap tidak mem-pin virtual threads
ConcurrentHashMap<String, LongAdder> counts = new ConcurrentHashMap<>();
counts.computeIfAbsent(key, k -> new LongAdder()).increment();

// SALAH: synchronized HashMap access
Map<String, Long> counts = new HashMap<>();
synchronized(counts) {
    counts.put(key, counts.getOrDefault(key, 0L) + 1);
}
```

## Monitoring Pinning

Untuk mendeteksi pinning, gunakan JDK Flight Recorder atau:

```bash
# Enable pinning event logging
java -Djdk.tracePinnedThreads=full \
     --enable-preview \
     -jar load-balancer-worker.jar
```

Output akan menunjukkan stack trace ketika virtual thread di-pin:
```
Thread[#...,virtual-worker-123] pinned for 5ms
  at com.example.MyClass.slowMethod(MyClass.java:42)
  - locked 0x... (a com.example.MyClass)  <-- Ini yang menyebabkan pin!
```

## Checklist untuk Code Review

- [ ] Tidak ada `synchronized` keyword
- [ ] Tidak ada `Object.wait()` / `notify()`
- [ ] Gunakan `LongAdder` untuk counters
- [ ] Gunakan `ConcurrentHashMap` untuk shared maps
- [ ] Gunakan `ReentrantLock` jika locking diperlukan
- [ ] Hindari blocking I/O di dalam lock

## Catatan Penting

Java 21+ telah mengoptimasi beberapa kasus:
- `Thread.sleep()` di luar synchronized tidak mem-pin
- Blocking I/O di luar synchronized tidak mem-pin
- `ReentrantLock.lock()` di Java 21+ sudah virtual-thread friendly

Namun, **`synchronized` selalu mem-pin** karena alasan kompatibilitas dengan native code.
