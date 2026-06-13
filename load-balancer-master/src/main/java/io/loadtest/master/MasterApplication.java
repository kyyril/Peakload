package io.loadtest.master;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Master Node Entry Point.
 *
 * Responsibilities:
 * - REST API for test scenario management
 * - Worker registry via Redis
 * - gRPC server for worker communication
 * - Metrics aggregation and streaming
 *
 * Startup Command:
 * ───────────────
 * java --enable-preview \\
 *      -XX:+UseZGC -XX:+ZGenerational \\
 *      -Xms1g -Xmx4g \\
 *      -jar load-balancer-master.jar
 */
@SpringBootApplication
@EnableAsync
@EnableScheduling
public class MasterApplication {

    public static void main(String[] args) {
        SpringApplication.run(MasterApplication.class, args);
    }
}
