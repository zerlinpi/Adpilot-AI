package com.adpilot.modules.scheduler.service.impl;

import com.adpilot.modules.scheduler.service.CronEvaluator;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property-based test for {@link CronEvaluatorImpl}, the recurrence evaluator
 * backing the scheduling engine (task 13.1).
 *
 * Feature: core-platform-completion, Property 13: Recurrence validation and
 * next-run computation are well-defined.
 *
 * For any recurrence expression, validation accepts it if and only if it is
 * well-formed, and for any well-formed expression and base time the computed
 * next run time is strictly after the base time (so a failed or completed
 * execution never blocks subsequent runs).
 *
 * Validates: Requirements 4.1.2, 4.1.4, 4.1.5
 */
class CronEvaluatorPropertyTest {

    private final CronEvaluator evaluator = new CronEvaluatorImpl();

    // Feature: core-platform-completion, Property 13: Recurrence validation and next-run computation are well-defined
    // Req 4.1.4: a well-formed recurrence expression validates as accepted.
    @Property(tries = 200)
    void validationAcceptsWellFormedExpressions(@ForAll("wellFormedCron") String expression) {
        assertThat(evaluator.isValid(expression))
                .as("well-formed expression '%s' must validate", expression)
                .isTrue();
    }

    // Feature: core-platform-completion, Property 13: Recurrence validation and next-run computation are well-defined
    // Req 4.1.4: a malformed recurrence expression is rejected by validation.
    @Property(tries = 200)
    void validationRejectsMalformedExpressions(@ForAll("malformedCron") String expression) {
        assertThat(evaluator.isValid(expression))
                .as("malformed expression '%s' must be rejected", expression)
                .isFalse();
    }

    // Feature: core-platform-completion, Property 13: Recurrence validation and next-run computation are well-defined
    // Req 4.1.2 / 4.1.5: for any well-formed expression and base time, the next run is
    // strictly after the base time, so a failed/completed run never blocks the next one.
    @Property(tries = 200)
    void nextRunIsStrictlyAfterBaseTime(
            @ForAll("wellFormedCron") String expression,
            @ForAll("baseInstants") Instant from) {

        Instant next = evaluator.nextRunAfter(expression, from);

        // The generated expressions recur unconditionally, so a next run always exists.
        assertThat(next)
                .as("well-formed recurring expression '%s' must yield a next run after %s",
                        expression, from)
                .isNotNull();

        // The core guarantee: the next run is strictly after the base time, even when
        // the base time falls exactly on a fire time.
        assertThat(next)
                .as("next run for '%s' must be strictly after base %s", expression, from)
                .isAfter(from);
    }

    // --- generators -----------------------------------------------------------

    /**
     * Well-formed, always-recurring expressions: a mix of six-field cron strings
     * (with day-of-month, month, and day-of-week left as wildcards so the
     * expression always has a future fire time) and the convenience macros.
     */
    @Provide
    Arbitrary<String> wellFormedCron() {
        return Arbitraries.oneOf(sixFieldCron(), macros());
    }

    private Arbitrary<String> sixFieldCron() {
        Arbitrary<String> second = field(0, 59);
        Arbitrary<String> minute = field(0, 59);
        Arbitrary<String> hour = field(0, 23);
        // Keep date/day-of-week fields wildcard so the expression always recurs.
        return Combinators.combine(second, minute, hour)
                .as((s, m, h) -> s + " " + m + " " + h + " * * *");
    }

    /**
     * A single cron field rendered as a wildcard, a single in-range value, a
     * step, or an in-range range, exercising the parser's accepted syntaxes.
     */
    private Arbitrary<String> field(int min, int max) {
        Arbitrary<String> wildcard = Arbitraries.just("*");
        Arbitrary<String> single = Arbitraries.integers().between(min, max).map(String::valueOf);
        Arbitrary<String> step = Arbitraries.integers().between(1, Math.max(1, max))
                .map(n -> "*/" + n);
        Arbitrary<String> range = Combinators.combine(
                        Arbitraries.integers().between(min, max),
                        Arbitraries.integers().between(min, max))
                .as((a, b) -> Math.min(a, b) + "-" + Math.max(a, b));
        return Arbitraries.oneOf(wildcard, single, step, range);
    }

    @Provide
    Arbitrary<String> macros() {
        return Arbitraries.of(
                "@hourly", "@daily", "@midnight", "@weekly", "@monthly", "@yearly", "@annually");
    }

    /**
     * Malformed expressions guaranteed to be rejected: blanks, garbage tokens,
     * wrong field counts, and out-of-range numeric fields.
     */
    @Provide
    Arbitrary<String> malformedCron() {
        Arbitrary<String> blanks = Arbitraries.of("", "   ", "\t");
        Arbitrary<String> garbage = Arbitraries.strings()
                .withCharRange('a', 'z').ofMinLength(1).ofMaxLength(10);
        Arbitrary<String> wrongFieldCount = Arbitraries.of(
                "* * *", "* *", "*", "0 0 0 0 0 0 0", "0 0 * * *");
        // Out-of-range numeric values in otherwise positionally valid expressions.
        Arbitrary<String> badSecond = Arbitraries.integers().between(60, 999)
                .map(n -> n + " * * * * *");
        Arbitrary<String> badHour = Arbitraries.integers().between(24, 999)
                .map(n -> "0 0 " + n + " * * *");
        Arbitrary<String> badMonth = Arbitraries.integers().between(13, 999)
                .map(n -> "0 0 0 1 " + n + " *");
        Arbitrary<String> badDayOfMonth = Arbitraries.integers().between(32, 999)
                .map(n -> "0 0 0 " + n + " * *");
        return Arbitraries.oneOf(
                blanks, garbage, wrongFieldCount, badSecond, badHour, badMonth, badDayOfMonth);
    }

    /**
     * Base instants drawn from a wide window (years ~2001..2065 in epoch seconds)
     * so next-run computation is exercised across many wall-clock positions,
     * including instants that land exactly on a fire time.
     */
    @Provide
    Arbitrary<Instant> baseInstants() {
        return Arbitraries.longs().between(1_000_000_000L, 3_000_000_000L)
                .map(Instant::ofEpochSecond);
    }
}
