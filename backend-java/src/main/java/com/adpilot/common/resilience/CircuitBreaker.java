package com.adpilot.common.resilience;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A small, in-house, per-key circuit breaker used to stop scheduled/worker paths from
 * hammering an external dependency that is already failing (reliability fix H3).
 *
 * <p>This mirrors the lightweight, in-memory, thread-safe style of
 * {@code AmazonAdsRateLimiterImpl}: state lives in a {@link ConcurrentHashMap} of small
 * per-key state objects and every transition on a single key is guarded by that object's
 * monitor. No new third-party dependency is introduced.</p>
 *
 * <h2>State machine (per key)</h2>
 * <ul>
 *   <li><b>CLOSED</b> — calls flow through. {@code failure-threshold} consecutive failures
 *       trip the breaker to {@code OPEN}.</li>
 *   <li><b>OPEN</b> — calls are short-circuited ({@link #allow(String)} returns {@code false})
 *       until the {@code cooldown-seconds} window elapses, after which a single probe is
 *       permitted by moving to {@code HALF_OPEN}.</li>
 *   <li><b>HALF_OPEN</b> — exactly one probe call is allowed; its outcome decides the next
 *       state. {@link #recordSuccess(String)} closes the breaker; {@link #recordFailure(String)}
 *       re-opens it for another cooldown window.</li>
 * </ul>
 *
 * <h2>Safety contract</h2>
 * <ul>
 *   <li><b>Fail-open</b>: any internal error while evaluating {@link #allow(String)} returns
 *       {@code true} (call is permitted). A breaker must never turn a transient blip into a
 *       hard outage for a healthy dependency.</li>
 *   <li><b>Toggleable</b>: when {@code adpilot.resilience.circuit.enabled=false},
 *       {@link #allow(String)} always returns {@code true} and the {@code record*} methods are
 *       no-ops.</li>
 *   <li><b>Per-key isolation</b>: a down store connection only opens the breaker for its own
 *       key, never for a healthy dependency's key.</li>
 * </ul>
 *
 * <p>Keys are logical dependency identifiers chosen by callers, e.g.
 * {@code "amazon_ads:token:<connectionId>"}, {@code "amazon_ads:report:<connectionId>"},
 * {@code "amazon_ads:status:<connectionId>"}, {@code "amazon_ads:verify:<connectionId>"},
 * or {@code "feishu:<integrationId>"}.</p>
 */
@Component
public class CircuitBreaker {

    private static final Logger log = LoggerFactory.getLogger(CircuitBreaker.class);

    /** Whether the breaker is active at all. When false everything is a pass-through. */
    private final boolean enabled;

    /** Consecutive failures on a key that trip CLOSED -> OPEN. */
    private final int failureThreshold;

    /** How long a key stays OPEN before a single HALF_OPEN probe is permitted. */
    private final Duration cooldown;

    /** Per-key breaker state; created lazily on first use. */
    private final ConcurrentHashMap<String, KeyState> states = new ConcurrentHashMap<>();

    public CircuitBreaker(
            @Value("${adpilot.resilience.circuit.enabled:true}") boolean enabled,
            @Value("${adpilot.resilience.circuit.failure-threshold:5}") int failureThreshold,
            @Value("${adpilot.resilience.circuit.cooldown-seconds:30}") long cooldownSeconds) {
        this.enabled = enabled;
        this.failureThreshold = failureThreshold > 0 ? failureThreshold : 5;
        // A negative cooldown is nonsensical (default it); zero is valid (probe immediately).
        this.cooldown = Duration.ofSeconds(cooldownSeconds >= 0 ? cooldownSeconds : 30);
        log.debug("CircuitBreaker initialized (enabled={}, failureThreshold={}, cooldownSeconds={})",
                this.enabled, this.failureThreshold, this.cooldown.toSeconds());
    }

    /**
     * @return {@code true} when a call for {@code key} should proceed. Returns {@code true} when
     *         disabled, when the breaker is CLOSED, or when an OPEN breaker's cooldown has elapsed
     *         (permitting one HALF_OPEN probe). Returns {@code false} only while a breaker is OPEN
     *         within its cooldown, or HALF_OPEN with a probe already in flight. Never throws:
     *         any internal error fails open ({@code true}).
     */
    public boolean allow(String key) {
        if (!enabled || key == null) {
            return true;
        }
        try {
            return evaluateAllow(key);
        } catch (RuntimeException ex) {
            // Fail-open: a broken breaker must never block a healthy dependency.
            log.debug("CircuitBreaker.allow failed open for key {}: {}", key, ex.getMessage());
            return true;
        }
    }

    /**
     * Evaluate the allow decision for a key. Package-private so the fail-open contract of
     * {@link #allow(String)} can be exercised by a test that forces this to throw.
     */
    boolean evaluateAllow(String key) {
        return stateFor(key).allow(Instant.now(), cooldown);
    }

    /** Record a successful call for {@code key}, closing the breaker and clearing failures. */
    public void recordSuccess(String key) {
        if (!enabled || key == null) {
            return;
        }
        try {
            stateFor(key).recordSuccess();
        } catch (RuntimeException ex) {
            log.debug("CircuitBreaker.recordSuccess ignored error for key {}: {}", key, ex.getMessage());
        }
    }

    /** Record a failed call for {@code key}, tripping the breaker OPEN once the threshold is reached. */
    public void recordFailure(String key) {
        if (!enabled || key == null) {
            return;
        }
        try {
            stateFor(key).recordFailure(Instant.now(), failureThreshold);
        } catch (RuntimeException ex) {
            log.debug("CircuitBreaker.recordFailure ignored error for key {}: {}", key, ex.getMessage());
        }
    }

    /** Whether the breaker is enabled (exposed for callers that want to skip key construction). */
    public boolean isEnabled() {
        return enabled;
    }

    /** Current state name for a key ({@code CLOSED} when never seen); intended for tests/observability. */
    public String stateName(String key) {
        KeyState s = states.get(key);
        return s == null ? State.CLOSED.name() : s.currentName();
    }

    private KeyState stateFor(String key) {
        return states.computeIfAbsent(key, k -> new KeyState());
    }

    enum State { CLOSED, OPEN, HALF_OPEN }

    /**
     * Per-key state. All mutating transitions are synchronized on the instance so state moves
     * are atomic and consistent under concurrent scheduler ticks.
     */
    static final class KeyState {

        private State state = State.CLOSED;
        private int consecutiveFailures = 0;
        private Instant openedAt = Instant.MIN;

        synchronized boolean allow(Instant now, Duration cooldown) {
            switch (state) {
                case CLOSED:
                    return true;
                case OPEN:
                    if (!now.isBefore(openedAt.plus(cooldown))) {
                        // Cooldown elapsed: permit exactly one probe.
                        state = State.HALF_OPEN;
                        return true;
                    }
                    return false;
                case HALF_OPEN:
                default:
                    // A probe is already in flight; hold everything else back until it resolves.
                    return false;
            }
        }

        synchronized void recordSuccess() {
            consecutiveFailures = 0;
            state = State.CLOSED;
        }

        synchronized void recordFailure(Instant now, int failureThreshold) {
            if (state == State.HALF_OPEN) {
                // Probe failed: back to OPEN for another cooldown window.
                state = State.OPEN;
                openedAt = now;
                return;
            }
            consecutiveFailures++;
            if (state == State.CLOSED && consecutiveFailures >= failureThreshold) {
                state = State.OPEN;
                openedAt = now;
            }
        }

        synchronized String currentName() {
            return state.name();
        }
    }
}
