package com.adpilot.modules.advertising.support;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * Projection of the latest open {@code operation_pending_changes} row joined to its owning
 * {@code operations} row, for a single {@code (entity_type, entity_id, field)}. Backs the
 * Pending_Overlay (Req 7): the pending value lives in {@code after_value} on the pending-change /
 * Operation record, never in a second physical column on the entity.
 *
 * <p>Column aliases map to these fields via MyBatis map-underscore-to-camel-case.</p>
 */
@Data
public class PendingOverlayRow {

    /** The writable advertising field this pending change targets. */
    private String field;

    /** The requested pending value, as the stored JSON text of the Operation's after value. */
    private String afterValue;

    /** The owning Operation's Sync_State, as its canonical lowercase machine value. */
    private String syncState;

    /** Creation timestamp of the owning Operation, used to pick the latest pending change. */
    private LocalDateTime createdAt;
}
