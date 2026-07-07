package com.adpilot.modules.advertising.operation;

import com.adpilot.common.exception.BusinessException;

import java.util.UUID;

/**
 * Typed optimistic-lock version conflict raised when an Operation's entity update affects zero rows
 * because the object's {@code version} changed since the operator's view loaded it (Req 5.4, 5.5).
 *
 * <p>The guarded update runs {@code UPDATE ... SET ..., version = version + 1 WHERE id = ? AND
 * version = ?}. A zero-row result means another writer already advanced the version, so this
 * Operation is working from a stale view and MUST be rejected rather than overwriting the newer
 * change (no last-writer-wins). The {@code createOperation} pipeline (task 6.1) catches this typed
 * exception to reject the Operation and inform the operator that the object changed underneath them.</p>
 *
 * <p>It extends {@link BusinessException} with HTTP status {@code 409 Conflict} and a stable error
 * code so it flows through the existing {@code GlobalExceptionHandler}, while remaining a distinct
 * type the Operation pipeline can catch specifically. The {@code entityType}, {@code entityId}, and
 * {@code expectedVersion} are retained for auditing and for building the operator-facing rejection
 * reason.</p>
 *
 * <p>Validates: Requirements 5.4, 5.5.</p>
 */
public class VersionConflictException extends BusinessException {

    /** Stable, human-readable error code surfaced in the API error envelope. */
    public static final String ERROR_CODE = "VERSION_CONFLICT";

    /** HTTP 409 Conflict — the request conflicts with the current state of the target resource. */
    private static final int CONFLICT_STATUS = 409;

    private final String entityType;
    private final UUID entityId;
    private final long expectedVersion;

    public VersionConflictException(String entityType, UUID entityId, long expectedVersion) {
        super(CONFLICT_STATUS, ERROR_CODE, buildMessage(entityType, entityId, expectedVersion));
        this.entityType = entityType;
        this.entityId = entityId;
        this.expectedVersion = expectedVersion;
    }

    private static String buildMessage(String entityType, UUID entityId, long expectedVersion) {
        String type = (entityType == null || entityType.isBlank()) ? "object" : entityType;
        return "操作失败：该" + type + "在您查看后已被其他更改修改（期望版本 " + expectedVersion
                + "，id=" + entityId + "）。请刷新后重试。";
    }

    /** @return the entity type label of the conflicting object (e.g. {@code campaign}); may be null. */
    public String getEntityType() {
        return entityType;
    }

    /** @return the id of the conflicting object. */
    public UUID getEntityId() {
        return entityId;
    }

    /** @return the version the operator's view was loaded with (the version the update guarded on). */
    public long getExpectedVersion() {
        return expectedVersion;
    }
}
