package com.adpilot.modules.alert.model;

import com.adpilot.modules.alert.enums.AlertType;

import java.util.UUID;

/**
 * Immutable description of a single evaluated business condition for one subject
 * (Req 10.1). It is the unit fed to {@link com.adpilot.modules.alert.service.AlertEngine#evaluate}.
 *
 * <p>{@link #active} captures whether the condition currently holds: when true the
 * engine raises or updates a single open alert (Req 10.1.7); when false it resolves
 * any existing open alert (Req 10.1.8).</p>
 *
 * @param storeId   store the condition belongs to (used for scoped display, Req 10.1.5)
 * @param type      alert category (Req 10.1.1&ndash;10.1.4)
 * @param subjectId product/campaign identifier the alert concerns
 * @param active    whether the condition currently holds
 * @param severity  alert severity (e.g. {@code info|warning|critical}); defaults applied if null
 * @param message   human-readable description shown in the alert center
 */
public record AlertCondition(
        UUID storeId,
        AlertType type,
        String subjectId,
        boolean active,
        String severity,
        String message) {

    public AlertCondition {
        if (storeId == null) {
            throw new IllegalArgumentException("storeId is required");
        }
        if (type == null) {
            throw new IllegalArgumentException("type is required");
        }
    }

    /** Builds an active condition with the default {@code warning} severity. */
    public static AlertCondition active(UUID storeId, AlertType type, String subjectId, String message) {
        return new AlertCondition(storeId, type, subjectId, true, "warning", message);
    }

    /** Builds a cleared (inactive) condition that resolves any matching open alert. */
    public static AlertCondition cleared(UUID storeId, AlertType type, String subjectId) {
        return new AlertCondition(storeId, type, subjectId, false, null, null);
    }
}
