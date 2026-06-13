package io.loadtest.common.model;

import java.time.Duration;
import java.util.Objects;
import java.util.OptionalLong;

import static java.util.Objects.requireNonNull;

/**
 * Load profile configuration defining how traffic is generated over time.
 *
 * Load Patterns Supported:
 * 1. Constant RPS: Sustained load at target rate
 * 2. Ramp-up then hold: Gradual increase to target, then sustain
 * 3. Spike test: Instant burst for resilience testing
 * 4. Step-up: Incremental increases for capacity planning
 *
 * Why OptionalLong for fields?
 * - Some profiles define duration, others define total requests
 * - OptionalLong avoids boxing overhead (vs Optional<Long>)
 * - Pattern matching works cleanly with OptionalLong::isPresent
 */
public sealed interface LoadProfile permits
        LoadProfile.Constant,
        LoadProfile.RampUp,
        LoadProfile.Spike,
        LoadProfile.StepUp {

    /**
     * Target requests per second.
     * This is the peak rate that workers attempt to achieve.
     */
    long targetRps();

    /**
     * Maximum concurrent requests/connections allowed.
     * Acts as a safety limit to prevent unbounded thread creation.
     */
    int maxConcurrency();

    /**
     * Estimate total duration based on configuration.
     */
    OptionalLong estimatedDurationMs();

    /**
     * Estimate total requests based on configuration.
     */
    OptionalLong estimatedTotalRequests();

    // ============================================================
    // LOAD PROFILE IMPLEMENTATIONS
    // ============================================================

    /**
     * Constant load profile - sustained RPS throughout the test.
     *
     * Use case: Baseline performance testing, soak tests.
     *
     * Visualization:
     * RPS
     *  ^
     *  |    ┌─────────────────┐
     *  │    │                 │
     *  │    │                 │
     *  └────┴─────────────────┴──> time
     */
    record Constant(
            long targetRps,
            Duration duration,
            int maxConcurrency
    ) implements LoadProfile {
        public Constant {
            requireNonNull(duration, "Duration cannot be null");
            if (targetRps <= 0) {
                throw new IllegalArgumentException("Target RPS must be positive, got: " + targetRps);
            }
            if (maxConcurrency <= 0) {
                throw new IllegalArgumentException("Max concurrency must be positive, got: " + maxConcurrency);
            }
            if (duration.isNegative() || duration.isZero()) {
                throw new IllegalArgumentException("Duration must be positive, got: " + duration);
            }
        }

        @Override
        public OptionalLong estimatedDurationMs() {
            return OptionalLong.of(duration.toMillis());
        }

        @Override
        public OptionalLong estimatedTotalRequests() {
            return OptionalLong.of(targetRps * duration.toSeconds());
        }
    }

    /**
     * Ramp-up load profile - gradual increase to target RPS.
     *
     * Use case: Finding breaking points, progressive load testing.
     *
     * Visualization:
     * RPS
     *  ^               ┌─────────┐
     *  │              /│         │
     *  │             / │         │
     *  │            /  │         │\
     *  └───────────/   │         │ \───> time
     *             ramp  hold    ramp
     *             up            down
     */
    record RampUp(
            long targetRps,
            Duration rampUpDuration,
            Duration holdDuration,
            Duration rampDownDuration,
            int maxConcurrency
    ) implements LoadProfile {
        public RampUp {
            requireNonNull(rampUpDuration, "Ramp-up duration cannot be null");
            requireNonNull(holdDuration, "Hold duration cannot be null");
            requireNonNull(rampDownDuration, "Ramp-down duration cannot be null");

            if (targetRps <= 0) {
                throw new IllegalArgumentException("Target RPS must be positive");
            }
            if (maxConcurrency <= 0) {
                throw new IllegalArgumentException("Max concurrency must be positive");
            }
        }

        @Override
        public OptionalLong estimatedDurationMs() {
            long totalMs = rampUpDuration.toMillis() + holdDuration.toMillis() + rampDownDuration.toMillis();
            return OptionalLong.of(totalMs);
        }

        @Override
        public OptionalLong estimatedTotalRequests() {
            // Area under the curve (trapezoid + rectangle + trapezoid)
            long rampUpRequests = (targetRps * rampUpDuration.toSeconds()) / 2;
            long holdRequests = targetRps * holdDuration.toSeconds();
            long rampDownRequests = (targetRps * rampDownDuration.toSeconds()) / 2;
            return OptionalLong.of(rampUpRequests + holdRequests + rampDownRequests);
        }

        /**
         * Create a ramp-up profile with default ramp-down.
         */
        public static RampUp create(long targetRps, Duration rampUp, Duration hold, int maxConcurrency) {
            return new RampUp(targetRps, rampUp, hold, Duration.ofSeconds(rampUp.toSeconds() / 2), maxConcurrency);
        }

        /**
         * Calculate current RPS at a given time offset.
         */
        public long calculateRpsAt(Duration elapsed) {
            long elapsedMs = elapsed.toMillis();
            long rampUpMs = rampUpDuration.toMillis();
            long holdMs = holdDuration.toMillis();
            long rampDownMs = rampDownDuration.toMillis();
            long totalMs = rampUpMs + holdMs + rampDownMs;

            if (elapsedMs < 0) return 0;
            if (elapsedMs >= totalMs) return 0;

            // Ramp-up phase: linear increase from 0 to target
            if (elapsedMs < rampUpMs) {
                return (targetRps * elapsedMs) / rampUpMs;
            }

            // Hold phase: constant at target
            if (elapsedMs < rampUpMs + holdMs) {
                return targetRps;
            }

            // Ramp-down phase: linear decrease from target to 0
            long rampDownElapsed = elapsedMs - rampUpMs - holdMs;
            return targetRps - ((targetRps * rampDownElapsed) / rampDownMs);
        }
    }

    /**
     * Spike load profile - instant burst for resilience testing.
     *
     * Use case: Autoscaling validation, circuit breaker testing, chaos engineering.
     *
     * Visualization:
     * RPS
     *  ^         ┌───┐
     *  │         │   │
     *  │         │   │
     *  │_________│   │_________> time
     *            spike
     *
     * Why use Phaser/CyclicBarrier?
     * A true "spike" requires all threads to start simultaneously.
     * Without synchronization, threads stagger over 100-500ms due to
     * scheduling delays. Phaser provides the "starting gun" mechanism.
     */
    record Spike(
            long targetRps,
            Duration spikeDuration,
            int maxConcurrency,
            boolean synchronizedStart
    ) implements LoadProfile {
        public Spike {
            requireNonNull(spikeDuration, "Spike duration cannot be null");
            if (targetRps <= 0) {
                throw new IllegalArgumentException("Target RPS must be positive");
            }
            if (maxConcurrency <= 0) {
                throw new IllegalArgumentException("Max concurrency must be positive");
            }
        }

        /**
         * Default spike configuration with synchronized start.
         */
        public static Spike burst(long targetRps, Duration duration, int maxConcurrency) {
            return new Spike(targetRps, duration, maxConcurrency, true);
        }

        @Override
        public OptionalLong estimatedDurationMs() {
            return OptionalLong.of(spikeDuration.toMillis());
        }

        @Override
        public OptionalLong estimatedTotalRequests() {
            return OptionalLong.of(targetRps * spikeDuration.toSeconds());
        }
    }

    /**
     * Step-up load profile - incremental RPS increases.
     *
     * Use case: Capacity planning, finding the "knee" in performance curve.
     *
     * Visualization:
     * RPS
     *  ^         ┌───┐
     *  │         │   │   ┌───┐
     *  │     ┌───┤   ├───┤   │   ┌───┐
     *  │  ┌──┤   │   │   │   ├───┤   │
     *  └──┤  │   │   │   │   │   │   ├───> time
     *     step1  step2  step3  step4
     */
    record StepUp(
            long initialRps,
            long rpsIncrement,
            Duration stepDuration,
            int totalSteps,
            int maxConcurrency
    ) implements LoadProfile {
        public StepUp {
            requireNonNull(stepDuration, "Step duration cannot be null");
            if (initialRps <= 0) {
                throw new IllegalArgumentException("Initial RPS must be positive");
            }
            if (rpsIncrement < 0) {
                throw new IllegalArgumentException("RPS increment cannot be negative");
            }
            if (totalSteps <= 0) {
                throw new IllegalArgumentException("Total steps must be positive");
            }
        }

        /**
         * Get the RPS for a specific step (1-indexed).
         */
        public long getRpsForStep(int step) {
            if (step < 1 || step > totalSteps) {
                throw new IllegalArgumentException("Step must be between 1 and " + totalSteps);
            }
            return initialRps + (rpsIncrement * (step - 1));
        }

        @Override
        public OptionalLong estimatedDurationMs() {
            return OptionalLong.of(stepDuration.toMillis() * totalSteps);
        }

        @Override
        public OptionalLong estimatedTotalRequests() {
            long total = 0;
            for (int step = 1; step <= totalSteps; step++) {
                total += getRpsForStep(step) * stepDuration.toSeconds();
            }
            return OptionalLong.of(total);
        }

        /**
         * Target RPS is the final step's RPS.
         */
        @Override
        public long targetRps() {
            return getRpsForStep(totalSteps);
        }
    }

    // ============================================================
    // BUILDER FOR FLEXIBLE CONFIGURATION
    // ============================================================

    /**
     * Builder for creating load profiles from configuration files.
     */
    final class Builder {
        private String type = "constant";
        private long targetRps = 100;
        private Duration duration = Duration.ofMinutes(1);
        private Duration rampUp = Duration.ZERO;
        private Duration rampDown = Duration.ZERO;
        private Duration spikeDuration;
        private int maxConcurrency = 1000;
        private boolean synchronizedStart = false;
        private long initialRps = 100;
        private long rpsIncrement = 100;
        private int totalSteps = 5;

        public Builder type(String type) {
            this.type = type;
            return this;
        }

        public Builder targetRps(long targetRps) {
            this.targetRps = targetRps;
            return this;
        }

        public Builder duration(Duration duration) {
            this.duration = duration;
            return this;
        }

        public Builder rampUp(Duration rampUp) {
            this.rampUp = rampUp;
            return this;
        }

        public Builder rampDown(Duration rampDown) {
            this.rampDown = rampDown;
            return this;
        }

        public Builder spikeDuration(Duration spikeDuration) {
            this.spikeDuration = spikeDuration;
            return this;
        }

        public Builder maxConcurrency(int maxConcurrency) {
            this.maxConcurrency = maxConcurrency;
            return this;
        }

        public Builder synchronizedStart(boolean synchronizedStart) {
            this.synchronizedStart = synchronizedStart;
            return this;
        }

        public Builder initialRps(long initialRps) {
            this.initialRps = initialRps;
            return this;
        }

        public Builder rpsIncrement(long rpsIncrement) {
            this.rpsIncrement = rpsIncrement;
            return this;
        }

        public Builder totalSteps(int totalSteps) {
            this.totalSteps = totalSteps;
            return this;
        }

        /**
         * Build the load profile based on configured type.
         */
        public LoadProfile build() {
            return switch (type.toLowerCase()) {
                case "constant" -> new Constant(targetRps, duration, maxConcurrency);
                case "rampup", "ramp-up", "ramp_up" -> {
                    Duration hold = duration.minus(rampUp).minus(rampDown);
                    if (hold.isNegative() || hold.isZero()) {
                        hold = Duration.ofSeconds(30);
                    }
                    yield new RampUp(targetRps, rampUp, hold, rampDown, maxConcurrency);
                }
                case "spike", "burst" -> {
                    Duration dur = spikeDuration != null ? spikeDuration : duration;
                    yield new Spike(targetRps, dur, maxConcurrency, synchronizedStart);
                }
                case "stepup", "step-up", "step_up" -> {
                    Duration stepDur = duration.dividedBy(totalSteps);
                    yield new StepUp(initialRps, rpsIncrement, stepDur, totalSteps, maxConcurrency);
                }
                default -> throw new IllegalArgumentException("Unknown load profile type: " + type);
            };
        }
    }
}
