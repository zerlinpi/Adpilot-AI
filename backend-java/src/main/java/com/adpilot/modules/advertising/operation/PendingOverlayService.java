package com.adpilot.modules.advertising.operation;

import java.util.Map;

/**
 * Produces the Pending_Overlay for an advertising entity row (Req 7).
 *
 * <p>The Amazon-confirmed value of every writable field lives ONCE on the entity table (Req 7.1).
 * A pending change lives on its owning Operation, never in a second physical column per field. This
 * service materializes the overlay by left-joining each entity field to the latest Operation in any
 * Unsettled_State for {@code (entityType, entityId, field)} (Req 7.2, 7.3): for a field with such an
 * Operation it surfaces the entity's confirmed value alongside the Operation's pending value and
 * Sync_State; for a field with no Unsettled_State Operation it surfaces only the confirmed value
 * (Req 7.6, 7.7).</p>
 *
 * <p>Validates: Requirements 7.3, 7.6, 7.7.</p>
 */
public interface PendingOverlayService {

    /**
     * Build the Pending_Overlay for one entity row.
     *
     * @param entityType      the entity type (for example {@code campaign}, {@code keyword})
     * @param entityId        the target object id (char(36))
     * @param confirmedValues the entity's Amazon-confirmed value per writable field; the keys define
     *                        exactly which fields the overlay covers
     * @return an {@link OverlayField} per supplied field — carrying only the confirmed value when no
     *         Unsettled_State Operation targets the field, or the confirmed value plus the latest
     *         Unsettled_State Operation's pending value and Sync_State when one does
     */
    Map<String, OverlayField> overlayFor(String entityType,
                                         String entityId,
                                         Map<String, Object> confirmedValues);
}
