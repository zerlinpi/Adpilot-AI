package com.adpilot.modules.apisync.model;

import java.util.UUID;

/**
 * A normalized, platform-agnostic description of a single change to be written
 * back to a live platform through a {@link com.adpilot.modules.apisync.connector.PlatformWriteConnector}.
 *
 * <p>It is produced from an internal source (an applied AI recommendation in
 * {@code WriteBackService}, or an automation rule in {@code AutomationRunner})
 * and submitted to the platform that owns the originating
 * {@link com.adpilot.modules.apisync.entity.PlatformConnectionEntity}
 * (Req 13.1.1, 13.2.x). The connector translates it into the platform's native
 * write call.
 *
 * @param platform         the platform key the change targets (e.g.
 *                         {@code "amazon_ads"}); matches the connection's platform
 * @param storeId          the store whose connection the change is submitted through
 * @param changeType       the kind of change (e.g. {@code "bid_change"},
 *                         {@code "negative_keyword"}, {@code "keyword"},
 *                         {@code "budget_change"}, {@code "pause_target"})
 * @param subjectType      the entity the change applies to (e.g. {@code "keyword"},
 *                         {@code "target"}, {@code "campaign"})
 * @param subjectId        the platform/internal identifier of the subject, may be {@code null}
 * @param currentValue     the value before the change, for provenance; may be {@code null}
 * @param recommendedValue the value to set on the platform; may be {@code null}
 * @param sourceType       the origin of the change ({@code "recommendation"} or
 *                         {@code "automation"}) for audit attribution (Req 13.1.2, 13.2.4)
 * @param sourceId         the identifier of the originating record (e.g. the
 *                         recommendation id)
 * @param submissionIdempotencyKey the per-submission key the change is submitted with, so the
 *                         platform can dedupe the exact submission (advertising-workspace-rework
 *                         Req 5.2/5.7); {@code null} for legacy callers that do not carry one
 */
public record PlatformChange(String platform,
                             UUID storeId,
                             String changeType,
                             String subjectType,
                             String subjectId,
                             String currentValue,
                             String recommendedValue,
                             String sourceType,
                             String sourceId,
                             String submissionIdempotencyKey) {

    /**
     * Backward-compatible constructor for callers (the recommendation write-back and automation
     * runner) that do not carry a per-submission idempotency key; the key defaults to {@code null}.
     */
    public PlatformChange(String platform,
                          UUID storeId,
                          String changeType,
                          String subjectType,
                          String subjectId,
                          String currentValue,
                          String recommendedValue,
                          String sourceType,
                          String sourceId) {
        this(platform, storeId, changeType, subjectType, subjectId,
                currentValue, recommendedValue, sourceType, sourceId, null);
    }
}
