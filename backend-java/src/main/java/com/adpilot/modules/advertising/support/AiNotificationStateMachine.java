package com.adpilot.modules.advertising.support;

/**
 * Pure, side-effect-free state machine for AI Notification work items
 * (Requirement 23.1, 23.3, 23.4).
 *
 * <p>An AI Notification is a work item the AI advertising module raises for
 * operator attention. It lives in exactly one of two states — {@code pending}
 * (待处理) or {@code closed} (已结束) — and is closed with a {@link Resolution}
 * describing how the operator disposed of it (applied a one-click optimization,
 * confirmed an AI target correction, rejected it, or dismissed it).
 *
 * <p>This helper embodies the transition contract validated by Property 7
 * (task 13.4):
 * <ul>
 *   <li><strong>Closing is the only transition.</strong> A pending item moves
 *       to {@code closed} carrying the supplied resolution.</li>
 *   <li><strong>Closed is terminal.</strong> Once an item is closed it never
 *       changes state or resolution again.</li>
 *   <li><strong>Closing is idempotent.</strong> Re-applying any close
 *       (apply/confirm/reject/dismiss) to an already-closed item is a no-op: it
 *       returns the same {@link Status} it started from, regardless of the
 *       resolution requested the second time.</li>
 * </ul>
 *
 * <p>The class is stateless, immutable, and free of Spring/JSON/persistence
 * dependencies so it can be unit- and property-tested in isolation. Callers
 * (the {@code AiNotificationController} and its service, task 13.6) persist the
 * resulting {@link Status} to the {@code ai_notifications} table; the database's
 * generated {@code open_dedup_key} column then becomes {@code NULL} for closed
 * rows (see {@link AiNotificationDedup}).
 */
public final class AiNotificationStateMachine {

    /** The two states an AI Notification can occupy. */
    public enum State {
        /** 待处理 — awaiting operator action; counts toward dedup. */
        PENDING,
        /** 已结束 — terminal; the item has been disposed of. */
        CLOSED
    }

    /**
     * How a pending notification was disposed of when it was closed. Mirrors the
     * {@code resolution} column of {@code ai_notifications}
     * ({@code applied|confirmed|rejected|dismissed}).
     */
    public enum Resolution {
        /** A one-click optimization was applied (Req 23.3). */
        APPLIED,
        /** An AI target correction was confirmed and applied (Req 23.4). */
        CONFIRMED,
        /** An AI target correction was rejected / discarded (Req 23.4). */
        REJECTED,
        /** The notification was dismissed without action. */
        DISMISSED
    }

    /**
     * Immutable snapshot of a notification's lifecycle position.
     *
     * <p>Invariant: {@code resolution} is {@code null} while {@code state} is
     * {@link State#PENDING}, and non-null once {@link State#CLOSED}. The factory
     * methods and {@link AiNotificationStateMachine#close} are the only way to
     * construct instances, so the invariant always holds.
     *
     * @param state      the current lifecycle state; never {@code null}
     * @param resolution the disposition when closed; {@code null} while pending
     */
    public record Status(State state, Resolution resolution) {

        public Status {
            if (state == null) {
                throw new IllegalArgumentException("state must not be null");
            }
            if (state == State.PENDING && resolution != null) {
                throw new IllegalArgumentException("a pending notification must not carry a resolution");
            }
            if (state == State.CLOSED && resolution == null) {
                throw new IllegalArgumentException("a closed notification must carry a resolution");
            }
        }

        /** @return {@code true} iff this notification is still pending. */
        public boolean isPending() {
            return state == State.PENDING;
        }

        /** @return {@code true} iff this notification is closed (terminal). */
        public boolean isClosed() {
            return state == State.CLOSED;
        }
    }

    private AiNotificationStateMachine() {
        // Utility class — not instantiable.
    }

    /**
     * The initial status of a freshly-raised notification.
     *
     * @return a pending {@link Status} with no resolution
     */
    public static Status pending() {
        return new Status(State.PENDING, null);
    }

    /**
     * Close a notification with the given resolution.
     *
     * <p>If {@code current} is already {@link State#CLOSED} the call is a no-op
     * and the original status is returned unchanged — this is what makes closing
     * <strong>idempotent</strong> and the closed state <strong>terminal</strong>.
     * A pending item transitions to {@code closed} carrying {@code resolution}.
     *
     * @param current    the notification's current status; must not be {@code null}
     * @param resolution the disposition to record when closing a pending item;
     *                   must not be {@code null}
     * @return the resulting status: closed-with-{@code resolution} when
     *         {@code current} was pending, otherwise {@code current} unchanged
     * @throws IllegalArgumentException if {@code current} or {@code resolution} is null
     */
    public static Status close(Status current, Resolution resolution) {
        if (current == null) {
            throw new IllegalArgumentException("current status must not be null");
        }
        if (resolution == null) {
            throw new IllegalArgumentException("resolution must not be null");
        }
        if (current.isClosed()) {
            // Terminal & idempotent: a closed item never changes again.
            return current;
        }
        return new Status(State.CLOSED, resolution);
    }

    /**
     * Convenience for Req 23.3: apply a one-click optimization, closing the item
     * with {@link Resolution#APPLIED}.
     */
    public static Status apply(Status current) {
        return close(current, Resolution.APPLIED);
    }

    /**
     * Convenience for Req 23.4: confirm an AI target correction, closing the item
     * with {@link Resolution#CONFIRMED}.
     */
    public static Status confirm(Status current) {
        return close(current, Resolution.CONFIRMED);
    }

    /**
     * Convenience for Req 23.4: reject an AI target correction, closing the item
     * with {@link Resolution#REJECTED}.
     */
    public static Status reject(Status current) {
        return close(current, Resolution.REJECTED);
    }
}
