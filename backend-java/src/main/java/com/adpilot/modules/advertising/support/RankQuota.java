package com.adpilot.modules.advertising.support;

/**
 * Pure, side-effect-free rank-monitoring quota arithmetic (Requirement 28.4).
 *
 * <p>The single rule, stated once here and reused everywhere, is: a new
 * rank-monitor task may be added <strong>iff the consumed count is strictly
 * less than the total quota</strong>. Equivalently, once {@code consumed}
 * reaches {@code total} any further add is rejected. All methods are
 * <strong>total</strong>: every {@code int} pair produces a defined result and
 * no method throws or has side effects.
 *
 * <p>This class is the single source of truth targeted by the rank-monitor
 * quota boundary property test (Property 9, task 14.5) and consumed by the
 * rank-monitor service when admitting new tasks (task 14.4).
 */
public final class RankQuota {

    private RankQuota() {
        // Utility class — not instantiable.
    }

    /**
     * Whether adding one more task is permitted.
     *
     * @param consumed the number of monitoring slots already consumed
     * @param total    the total monitoring quota
     * @return {@code true} iff {@code consumed < total}
     */
    public static boolean permitsAdd(int consumed, int total) {
        return consumed < total;
    }

    /**
     * Whether the quota is exhausted (no further add permitted). This is the
     * exact complement of {@link #permitsAdd(int, int)}.
     *
     * @param consumed the number of monitoring slots already consumed
     * @param total    the total monitoring quota
     * @return {@code true} iff {@code consumed >= total}
     */
    public static boolean isExhausted(int consumed, int total) {
        return !permitsAdd(consumed, total);
    }

    /**
     * The number of remaining slots, never negative. When {@code consumed}
     * exceeds {@code total} (e.g. the quota was lowered after tasks were
     * created) the remaining count clamps to {@code 0}.
     *
     * @param consumed the number of monitoring slots already consumed
     * @param total    the total monitoring quota
     * @return {@code max(0, total - consumed)}
     */
    public static int remaining(int consumed, int total) {
        return Math.max(0, total - consumed);
    }
}
