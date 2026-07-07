package com.adpilot.modules.scheduler.service.impl;

import com.adpilot.modules.scheduler.service.CronEvaluator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;

/**
 * {@link CronEvaluator} backed by Spring's {@link CronExpression} parser.
 *
 * <p>Spring's parser supports the standard six-field cron syntax
 * (second, minute, hour, day-of-month, month, day-of-week) as well as the
 * convenience macros ({@code @hourly}, {@code @daily}, {@code @weekly}, etc.),
 * which is sufficient for sync schedule recurrence.</p>
 *
 * <p>Cron fields are interpreted in a fixed zone so that next-run computation is
 * deterministic and independent of the host's default time zone. UTC is used.</p>
 */
@Slf4j
@Service
public class CronEvaluatorImpl implements CronEvaluator {

    /** Zone used to interpret cron fields. UTC keeps computation deterministic. */
    private static final ZoneId ZONE = ZoneOffset.UTC;

    @Override
    public boolean isValid(String expression) {
        if (expression == null || expression.isBlank()) {
            return false;
        }
        // CronExpression.isValidExpression returns false (never throws) for malformed input.
        return CronExpression.isValidExpression(expression.trim());
    }

    @Override
    public Instant nextRunAfter(String expression, Instant from) {
        if (from == null) {
            throw new IllegalArgumentException("Base time 'from' must not be null");
        }
        if (!isValid(expression)) {
            throw new IllegalArgumentException("Invalid recurrence expression: " + expression);
        }

        CronExpression cron = CronExpression.parse(expression.trim());
        ZonedDateTime base = from.atZone(ZONE);

        // CronExpression.next() returns the next match strictly after the supplied
        // temporal, or null if the expression can never fire again.
        ZonedDateTime next = cron.next(base);
        if (next == null) {
            log.debug("Recurrence expression '{}' has no next run after {}", expression, from);
            return null;
        }
        return next.toInstant();
    }
}
