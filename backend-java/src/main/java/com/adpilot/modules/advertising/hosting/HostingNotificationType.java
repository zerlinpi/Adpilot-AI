package com.adpilot.modules.advertising.hosting;

/**
 * Typed notification categories for the AI hosting system (Req 9.1–9.5).
 *
 * <p>Each type maps to a specific event in the hosting lifecycle and determines
 * whether the notification is sent immediately (emergency) or batched into a
 * digest (non-urgent).</p>
 */
public enum HostingNotificationType {

    /** An AI decision requires human approval (Req 9.1). */
    APPROVAL_NEEDED("approval_needed", false),

    /** An operation has been verified as effective on Amazon (Req 9.2). */
    EFFECTIVE_CONFIRMED("effective_confirmed", false),

    /** An operation has failed on Amazon (Req 9.3). */
    FAILED("failed", false),

    /** An emergency stop has been triggered — sent immediately (Req 9.4). */
    EMERGENCY("emergency", true),

    /** A data gap has been detected in report coverage (Req 2.8, 3.6). */
    DATA_GAP("data_gap", false);

    private final String value;
    private final boolean urgent;

    HostingNotificationType(String value, boolean urgent) {
        this.value = value;
        this.urgent = urgent;
    }

    /**
     * The string value stored in {@code notification_delivery_log.notification_type}.
     */
    public String getValue() {
        return value;
    }

    /**
     * Whether this notification type requires immediate delivery (bypasses digest batching).
     */
    public boolean isUrgent() {
        return urgent;
    }
}
