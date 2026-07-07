package com.adpilot.modules.advertising.support;

import com.adpilot.modules.advertising.support.AiNotificationStateMachine.State;

import java.util.Set;

/**
 * Pure, side-effect-free dedup-key logic for AI Notifications
 * (Requirement 23.1, 23.3).
 *
 * <p>The platform guarantees <strong>at most one pending notification per
 * {@code (store, category, subject)} key</strong> (Property 8, task 13.5). In
 * the schema this is enforced by the {@code ai_notifications.open_dedup_key}
 * generated column plus its {@code uk_open_ai_notification} unique index:
 *
 * <pre>{@code
 * open_dedup_key = CASE WHEN state = 'pending'
 *                       THEN CONCAT(store_id, ':', category, ':', COALESCE(subject_id, ''))
 *                       ELSE NULL
 *                  END
 * }</pre>
 *
 * <p>Because MySQL unique indexes do not compare {@code NULL}s, closed rows drop
 * out of the uniqueness constraint and unbounded closed history is retained,
 * while only one <em>pending</em> row can ever exist per open key — mirroring the
 * V1 {@code alerts} table pattern.
 *
 * <p>This class reproduces exactly that derivation in Java so the application
 * can pre-check dedup before attempting an insert (and so the behaviour is
 * directly property-testable without a database). The methods are the single
 * source of truth for the open-key format; keep them in lock-step with the V3
 * migration.
 */
public final class AiNotificationDedup {

    /** Separator between the components of an open dedup key (matches the SQL {@code ':'}). */
    public static final String SEPARATOR = ":";

    private AiNotificationDedup() {
        // Utility class — not instantiable.
    }

    /**
     * The dedup key for a notification with the given identity, computed exactly
     * as the database's generated column would compute it: the composite key
     * while {@code state} is {@link State#PENDING}, otherwise {@code null}.
     *
     * @param state     the notification's state; must not be {@code null}
     * @param storeId   the owning store id; must not be {@code null}/blank (NOT NULL in schema)
     * @param category  the notification category; must not be {@code null}/blank (NOT NULL in schema)
     * @param subjectId the subject (campaign/target) id the item concerns; may be
     *                  {@code null}, treated as the empty string (matches
     *                  {@code COALESCE(subject_id, '')})
     * @return the open dedup key while pending, or {@code null} once closed
     * @throws IllegalArgumentException if {@code state} is null, or {@code storeId}
     *                                  / {@code category} is null or blank
     */
    public static String openDedupKey(State state, String storeId, String category, String subjectId) {
        if (state == null) {
            throw new IllegalArgumentException("state must not be null");
        }
        if (state != State.PENDING) {
            // Closed rows have a NULL open key and never collide.
            return null;
        }
        return dedupKeyFor(storeId, category, subjectId);
    }

    /**
     * The composite open-key value for a {@code (store, category, subject)}
     * identity, independent of state. This is the value the database stores while
     * the notification is pending.
     *
     * @param storeId   the owning store id; must not be {@code null}/blank
     * @param category  the notification category; must not be {@code null}/blank
     * @param subjectId the subject id; may be {@code null} (treated as empty)
     * @return {@code storeId + ":" + category + ":" + (subjectId or "")}
     * @throws IllegalArgumentException if {@code storeId} / {@code category} is null or blank
     */
    public static String dedupKeyFor(String storeId, String category, String subjectId) {
        if (isBlank(storeId)) {
            throw new IllegalArgumentException("storeId must not be null or blank");
        }
        if (isBlank(category)) {
            throw new IllegalArgumentException("category must not be null or blank");
        }
        String subject = subjectId == null ? "" : subjectId;
        return storeId + SEPARATOR + category + SEPARATOR + subject;
    }

    /**
     * Pure dedup decision: whether a new pending notification may be opened for
     * the given identity, given the set of dedup keys already held by pending
     * notifications.
     *
     * <p>Returns {@code true} iff no currently-pending notification shares the
     * same open key — exactly the condition the unique index enforces. Callers
     * use this to decide whether to raise a new notification or to fold the event
     * into the existing pending one.
     *
     * @param openKeys  the open dedup keys of currently-pending notifications;
     *                  must not be {@code null} (use an empty set for "none open")
     * @param storeId   the owning store id; must not be {@code null}/blank
     * @param category  the notification category; must not be {@code null}/blank
     * @param subjectId the subject id; may be {@code null}
     * @return {@code true} iff a new pending notification with this identity would
     *         not collide with an existing pending one
     * @throws IllegalArgumentException if {@code openKeys} is null, or
     *                                  {@code storeId} / {@code category} is null or blank
     */
    public static boolean canOpen(Set<String> openKeys, String storeId, String category, String subjectId) {
        if (openKeys == null) {
            throw new IllegalArgumentException("openKeys must not be null");
        }
        return !openKeys.contains(dedupKeyFor(storeId, category, subjectId));
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
