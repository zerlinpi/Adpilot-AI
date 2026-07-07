package com.adpilot.modules.upload.publish;

/**
 * Outcome of a direct product publish to an independent-site platform
 * (Shopify / WooCommerce).
 *
 * <p>This is a business-level result, distinct from a thrown exception. The
 * honesty contract (mirrored from the write connectors) is:</p>
 * <ul>
 *   <li>{@link #published(String, String, String)} — the platform accepted the
 *       create call (2xx) and returned a product id.</li>
 *   <li>{@link #refused(String, String)} — the publish was refused before any
 *       HTTP call because the store has no connected connection or is missing
 *       required credentials. Never faked as success, never downgraded to an
 *       export.</li>
 *   <li>{@link #rejected(String, String)} — the platform rejected the create
 *       call (non-2xx) and the platform's own readable reason is carried.</li>
 * </ul>
 *
 * <p>Genuine transport/credential failures (connection refused, TLS, etc.) are
 * surfaced as exceptions by the publish service rather than as an outcome, per
 * the codebase-wide honesty principle.</p>
 *
 * @param published          {@code true} only when the platform accepted the create (2xx)
 * @param platformProductId  the platform-assigned product id, when published
 * @param publishMode        the publish mode recorded on success (e.g. {@code "direct"})
 * @param errorCode          a structured error code when not published
 * @param reason             a human-readable reason (success message or failure reason)
 */
public record ProductPublishOutcome(
        boolean published,
        String platformProductId,
        String publishMode,
        String errorCode,
        String reason) {

    /** The direct (API) publish mode recorded on a successful publish. */
    public static final String PUBLISH_MODE_DIRECT = "direct";

    public static ProductPublishOutcome published(String platformProductId, String publishMode, String reason) {
        return new ProductPublishOutcome(true, platformProductId, publishMode, null, reason);
    }

    /** Refused before any HTTP call (no connected connection / missing creds). */
    public static ProductPublishOutcome refused(String errorCode, String reason) {
        return new ProductPublishOutcome(false, null, null, errorCode, reason);
    }

    /** The platform rejected the create call (non-2xx). */
    public static ProductPublishOutcome rejected(String errorCode, String reason) {
        return new ProductPublishOutcome(false, null, null, errorCode, reason);
    }
}
