package com.adpilot.modules.advertising.operation.alert;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Label;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;
import net.jqwik.api.constraints.IntRange;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property-based test for the write-back failure alerting threshold rule (task 10.14).
 *
 * <p>Feature: advertising-workspace-rework, Property 80: Write-back failure alerting threshold.
 *
 * <p>Validates: Requirements 51.7, 51.11.
 *
 * <p>Property 80 (from Requirement 51.7): a write-back failure alert fires when, over the configured
 * rolling window with the configured minimum sample, the failure rate is at or above the configured
 * threshold, OR when the configured number of consecutive write-back failures occur for the same
 * Store + Write_Connector. Per Requirement 51.11 the window duration, minimum sample, failure-rate
 * threshold, and consecutive-failure count are all configurable backend values — modelled here as
 * the generated {@link WriteBackAlertThresholds} that the test varies across the full input space.</p>
 *
 * <p>The subject under test is the pure, side-effect-free {@link WriteBackFailureEvaluator} from task
 * 10.4. The central property compares its decision against an independent reference oracle that
 * re-derives the two rules straight from the requirement text; directed properties then pin the
 * specific boundary behaviours called out in 51.7 (the minimum-sample guard, the failure-rate
 * trigger, the consecutive-failure trigger, and the configurability of every threshold).</p>
 */
@Tag("pbt")
@Label("Feature: advertising-workspace-rework, Property 80: Write-back failure alerting threshold")
class WriteBackFailureAlertingThresholdPropertyTest {

    private static final int MIN_ITERATIONS = 200;

    /** Fixed evaluation point; attempts are positioned relative to it. */
    private static final Instant NOW = Instant.parse("2025-01-01T12:00:00Z");

    // ------------------------------------------------------------------
    // Central property: evaluator matches an independent reference oracle
    // ------------------------------------------------------------------

    /**
     * For any sequence of attempts, evaluation point, and configurable thresholds, the evaluator's
     * decision (whether to alert, which rule fired, and the figures it reports) matches a reference
     * oracle derived directly from Requirement 51.7. This exercises the full input space, including
     * attempts after {@code now} (which must be ignored), the strictly-after window boundary, the
     * {@code >=} comparisons, and the minimum-sample guard.
     */
    @Property(tries = MIN_ITERATIONS)
    @Label("Property 80: evaluator decision matches the requirement-derived oracle for all inputs")
    void evaluatorMatchesReferenceOracle(@ForAll("attempts") List<WriteBackAttempt> attempts,
                                         @ForAll("thresholds") WriteBackAlertThresholds thresholds) {
        WriteBackAlertDecision decision = WriteBackFailureEvaluator.evaluate(attempts, NOW, thresholds);
        Expected expected = oracle(attempts, NOW, thresholds);

        assertThat(decision.windowedAttempts()).isEqualTo(expected.windowedAttempts);
        assertThat(decision.windowedFailures()).isEqualTo(expected.windowedFailures);
        assertThat(decision.trailingConsecutiveFailures()).isEqualTo(expected.trailingFailures);
        assertThat(decision.failureRateBreached()).isEqualTo(expected.failureRateBreached);
        assertThat(decision.consecutiveFailuresBreached()).isEqualTo(expected.consecutiveBreached);
        assertThat(decision.shouldAlert())
                .isEqualTo(expected.failureRateBreached || expected.consecutiveBreached);
    }

    // ------------------------------------------------------------------
    // Directed facets of Property 80
    // ------------------------------------------------------------------

    /**
     * The failure-rate rule never fires while the number of in-window attempts is below the
     * configured minimum sample, no matter how high the failure rate — even with every attempt
     * failing (Req 51.7 minimum-sample guard). The consecutive rule is disabled here to isolate the
     * rate rule.
     */
    @Property(tries = MIN_ITERATIONS)
    @Label("Property 80: below the minimum sample the failure-rate rule cannot fire")
    void belowMinimumSampleNeverFiresRateRule(@ForAll @IntRange(min = 2, max = 40) int minSample,
                                              @ForAll @IntRange(min = 0, max = 39) int rawCount) {
        int count = Math.min(rawCount, minSample - 1); // strictly fewer attempts than minSample
        // Window wide enough that every 60s-spaced attempt sits strictly inside it.
        Duration window = Duration.ofHours(24);
        // All attempts inside the window and all failing => maximal failure rate of 1.0.
        List<WriteBackAttempt> attempts = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            attempts.add(WriteBackAttempt.failure(NOW.minusSeconds(60L * (i + 1))));
        }
        // consecutiveFailureThreshold = 0 disables the consecutive rule.
        WriteBackAlertThresholds thresholds =
                new WriteBackAlertThresholds(window, minSample, 0.01d, 0);

        WriteBackAlertDecision decision = WriteBackFailureEvaluator.evaluate(attempts, NOW, thresholds);

        assertThat(decision.windowedAttempts()).isEqualTo(count);
        assertThat(decision.failureRateBreached()).isFalse();
        assertThat(decision.shouldAlert()).isFalse();
    }

    /**
     * With at least the minimum sample of in-window attempts, the failure-rate rule fires exactly
     * when the actual failure rate is at or above the configured threshold (Req 51.7 rate rule). The
     * consecutive rule is disabled to isolate the rate rule.
     */
    @Property(tries = MIN_ITERATIONS)
    @Label("Property 80: in-window failure rate >= threshold triggers the rate rule, < threshold does not")
    void failureRateRuleFiresAtOrAboveThreshold(@ForAll @IntRange(min = 1, max = 40) int total,
                                                @ForAll @IntRange(min = 0, max = 40) int rawFailures,
                                                @ForAll @IntRange(min = 0, max = 100) int thresholdPct) {
        int failures = Math.min(rawFailures, total);
        // Window wide enough that every 60s-spaced attempt sits strictly inside it.
        Duration window = Duration.ofHours(24);
        double threshold = thresholdPct / 100.0d;

        // Build `total` attempts inside the window; the first `failures` of them fail.
        List<WriteBackAttempt> attempts = new ArrayList<>();
        for (int i = 0; i < total; i++) {
            Instant t = NOW.minusSeconds(60L * (total - i)); // chronological, all within window
            attempts.add(i < failures ? WriteBackAttempt.failure(t) : WriteBackAttempt.success(t));
        }
        // minSample = total so the sample guard is satisfied; consecutive rule disabled.
        WriteBackAlertThresholds thresholds =
                new WriteBackAlertThresholds(window, total, threshold, 0);

        WriteBackAlertDecision decision = WriteBackFailureEvaluator.evaluate(attempts, NOW, thresholds);

        double actualRate = (double) failures / total;
        boolean expectedFired = actualRate >= threshold;
        assertThat(decision.windowedAttempts()).isEqualTo(total);
        assertThat(decision.windowedFailures()).isEqualTo(failures);
        assertThat(decision.failureRateBreached()).isEqualTo(expectedFired);
        assertThat(decision.shouldAlert()).isEqualTo(expectedFired);
    }

    /**
     * The consecutive-failure rule fires exactly when the number of trailing consecutive failures is
     * at or above the configured count, independent of the rolling window (Req 51.7 consecutive
     * rule). A single trailing success resets the streak. The rate rule is kept dormant by an
     * unreachable threshold/sample.
     */
    @Property(tries = MIN_ITERATIONS)
    @Label("Property 80: trailing consecutive failures >= configured count triggers the consecutive rule")
    void consecutiveFailureRuleFiresAtOrAboveCount(@ForAll @IntRange(min = 1, max = 10) int configured,
                                                   @ForAll @IntRange(min = 0, max = 12) int trailingFailures,
                                                   @ForAll boolean precedingSuccess) {
        Duration window = Duration.ofMinutes(15);
        List<WriteBackAttempt> attempts = new ArrayList<>();
        // Optional older success that ends any earlier streak (well outside the window too).
        int slot = trailingFailures + 1;
        if (precedingSuccess) {
            attempts.add(WriteBackAttempt.success(NOW.minusSeconds(3600L * slot)));
        }
        // Then `trailingFailures` failures right up to now, newest last.
        for (int i = 0; i < trailingFailures; i++) {
            attempts.add(WriteBackAttempt.failure(NOW.minusSeconds(60L * (trailingFailures - i))));
        }
        // Rate rule dormant: minSample unreachably high.
        WriteBackAlertThresholds thresholds =
                new WriteBackAlertThresholds(window, 1000, 1.0d, configured);

        WriteBackAlertDecision decision = WriteBackFailureEvaluator.evaluate(attempts, NOW, thresholds);

        boolean expectedFired = trailingFailures >= configured;
        assertThat(decision.trailingConsecutiveFailures()).isEqualTo(trailingFailures);
        assertThat(decision.consecutiveFailuresBreached()).isEqualTo(expectedFired);
        assertThat(decision.failureRateBreached()).isFalse();
        assertThat(decision.shouldAlert()).isEqualTo(expectedFired);
    }

    /**
     * Thresholds are configurable values (Req 51.11): an attempt history that alerts under permissive
     * thresholds stops alerting once every threshold is raised beyond reach. This pins the dependence
     * of the decision on the configuration rather than on hard-coded constants.
     */
    @Property(tries = MIN_ITERATIONS)
    @Label("Property 80: raising every threshold beyond reach suppresses an otherwise-firing alert")
    void thresholdsAreConfigurableAndCanSuppressAlerts(@ForAll("attempts") List<WriteBackAttempt> attempts) {
        // Permissive config: tiny sample, zero rate, single consecutive failure => fires readily.
        WriteBackAlertThresholds permissive =
                new WriteBackAlertThresholds(Duration.ofMinutes(15), 1, 0.0d, 1);
        WriteBackAlertDecision sensitive = WriteBackFailureEvaluator.evaluate(attempts, NOW, permissive);

        // Strict config: unreachable sample, rate above 1.0 is impossible (use 1.0 + huge sample),
        // and consecutive rule disabled => can never fire for any finite history.
        WriteBackAlertThresholds strict =
                new WriteBackAlertThresholds(Duration.ofMinutes(15), Integer.MAX_VALUE, 1.0d, 0);
        WriteBackAlertDecision strictDecision = WriteBackFailureEvaluator.evaluate(attempts, NOW, strict);

        // The strict configuration never alerts, regardless of the permissive outcome.
        assertThat(strictDecision.shouldAlert()).isFalse();
        // Sanity: the permissive config alerts whenever there is at least one in-window failure
        // (rate rule) or any trailing failure (consecutive rule).
        Expected exp = oracle(attempts, NOW, permissive);
        assertThat(sensitive.shouldAlert()).isEqualTo(exp.failureRateBreached || exp.consecutiveBreached);
    }

    // ------------------------------------------------------------------
    // Reference oracle (independent re-derivation of Requirement 51.7)
    // ------------------------------------------------------------------

    private record Expected(int windowedAttempts,
                            int windowedFailures,
                            int trailingFailures,
                            boolean failureRateBreached,
                            boolean consecutiveBreached) {
    }

    private static Expected oracle(List<WriteBackAttempt> attempts,
                                   Instant now,
                                   WriteBackAlertThresholds t) {
        // Keep only attempts at or before `now`, sorted chronologically.
        List<WriteBackAttempt> upToNow = new ArrayList<>();
        for (WriteBackAttempt a : attempts) {
            if (a != null && !a.timestamp().isAfter(now)) {
                upToNow.add(a);
            }
        }
        upToNow.sort((x, y) -> x.timestamp().compareTo(y.timestamp()));

        Instant windowStart = now.minus(t.window());
        int windowedAttempts = 0;
        int windowedFailures = 0;
        for (WriteBackAttempt a : upToNow) {
            if (a.timestamp().isAfter(windowStart)) {
                windowedAttempts++;
                if (!a.success()) {
                    windowedFailures++;
                }
            }
        }
        boolean rateBreached = windowedAttempts > 0
                && windowedAttempts >= t.minSample()
                && ((double) windowedFailures / windowedAttempts) >= t.failureRateThreshold();

        int trailing = 0;
        for (int i = upToNow.size() - 1; i >= 0; i--) {
            if (upToNow.get(i).success()) {
                break;
            }
            trailing++;
        }
        boolean consecutiveBreached = t.consecutiveFailureThreshold() > 0
                && trailing >= t.consecutiveFailureThreshold();

        return new Expected(windowedAttempts, windowedFailures, trailing, rateBreached, consecutiveBreached);
    }

    // ------------------------------------------------------------------
    // Generators
    // ------------------------------------------------------------------

    /**
     * A write-back history: each attempt is placed at an offset relative to {@code NOW} ranging from
     * 60 minutes before to 10 minutes after, so the generated space spans attempts well inside the
     * window, on the window boundary, outside the window, and in the future (which must be ignored).
     */
    @Provide
    Arbitrary<List<WriteBackAttempt>> attempts() {
        Arbitrary<Integer> offsetSeconds = Arbitraries.integers().between(-3600, 600);
        Arbitrary<Boolean> success = Arbitraries.of(true, false);
        Arbitrary<WriteBackAttempt> attempt = Combinators.combine(offsetSeconds, success)
                .as((off, ok) -> new WriteBackAttempt(NOW.plusSeconds(off), ok));
        return attempt.list().ofMinSize(0).ofMaxSize(30);
    }

    /**
     * Configurable thresholds across their valid domains (Req 51.11): window 1–60 min, minimum sample
     * 0–20, failure-rate threshold 0.00–1.00 in 0.01 steps, and consecutive-failure count 0–10 (0
     * disables the consecutive rule).
     */
    @Provide
    Arbitrary<WriteBackAlertThresholds> thresholds() {
        Arbitrary<Duration> window = Arbitraries.integers().between(1, 60).map(Duration::ofMinutes);
        Arbitrary<Integer> minSample = Arbitraries.integers().between(0, 20);
        Arbitrary<Double> rate = Arbitraries.integers().between(0, 100).map(p -> p / 100.0d);
        Arbitrary<Integer> consecutive = Arbitraries.integers().between(0, 10);
        return Combinators.combine(window, minSample, rate, consecutive)
                .as(WriteBackAlertThresholds::new);
    }
}
