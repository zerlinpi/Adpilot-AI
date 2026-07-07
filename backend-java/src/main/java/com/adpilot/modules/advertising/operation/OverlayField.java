package com.adpilot.modules.advertising.operation;

import java.util.Objects;
import java.util.Optional;

/**
 * The Pending_Overlay view of a single writable advertising field (Req 7).
 *
 * <p>It carries the entity's Amazon-confirmed value (the value stored ONCE on the entity table,
 * Req 7.1) and, when an Operation against this field is in any Unsettled_State, the Operation's
 * pending value plus that Operation's Sync_State. There is no second physical column per field: the
 * pending value is materialized from the latest Unsettled_State Operation's after value through the
 * {@code operation_pending_changes} join (Req 7.2, 7.3).</p>
 *
 * <ul>
 *   <li>{@link #getConfirmedValue()} — always present; the Amazon-confirmed (entity) value.</li>
 *   <li>{@link #getPendingValue()} — present only while an Unsettled_State Operation exists for the
 *       field; otherwise empty (Req 7.7).</li>
 *   <li>{@link #getPendingSyncState()} — the Sync_State of that Operation, present iff a pending
 *       value is present.</li>
 * </ul>
 *
 * <p>Immutable value type. Validates: Requirements 7.3, 7.6, 7.7.</p>
 */
public final class OverlayField {

    private final Object confirmedValue;
    private final Object pendingValue;
    private final SyncState pendingSyncState;

    private OverlayField(Object confirmedValue, Object pendingValue, SyncState pendingSyncState) {
        this.confirmedValue = confirmedValue;
        this.pendingValue = pendingValue;
        this.pendingSyncState = pendingSyncState;
    }

    /**
     * A field with only its confirmed value — no Operation is in progress for it (Req 7.7).
     */
    public static OverlayField confirmed(Object confirmedValue) {
        return new OverlayField(confirmedValue, null, null);
    }

    /**
     * A field with a confirmed value and a pending value drawn from an Unsettled_State Operation
     * (Req 7.3, 7.6).
     *
     * @param pendingSyncState the Unsettled_State; must not be {@code null}
     */
    public static OverlayField withPending(Object confirmedValue, Object pendingValue, SyncState pendingSyncState) {
        return new OverlayField(confirmedValue, pendingValue, Objects.requireNonNull(pendingSyncState,
                "pendingSyncState must not be null when a pending value is present"));
    }

    /** The Amazon-confirmed value stored on the entity. */
    public Object getConfirmedValue() {
        return confirmedValue;
    }

    /** The pending value from the latest Unsettled_State Operation, when present. */
    public Optional<Object> getPendingValue() {
        return Optional.ofNullable(pendingValue);
    }

    /** The Sync_State of the Operation supplying the pending value, when present. */
    public Optional<SyncState> getPendingSyncState() {
        return Optional.ofNullable(pendingSyncState);
    }

    /** @return {@code true} when an Unsettled_State Operation supplies a pending value for this field. */
    public boolean hasPending() {
        return pendingSyncState != null;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof OverlayField other)) {
            return false;
        }
        return Objects.equals(confirmedValue, other.confirmedValue)
                && Objects.equals(pendingValue, other.pendingValue)
                && pendingSyncState == other.pendingSyncState;
    }

    @Override
    public int hashCode() {
        return Objects.hash(confirmedValue, pendingValue, pendingSyncState);
    }

    @Override
    public String toString() {
        return "OverlayField{confirmedValue=" + confirmedValue
                + ", pendingValue=" + pendingValue
                + ", pendingSyncState=" + pendingSyncState + '}';
    }
}
