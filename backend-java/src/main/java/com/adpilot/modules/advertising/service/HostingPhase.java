package com.adpilot.modules.advertising.service;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

/**
 * The phased-delivery gate for executable AI hosting (Req 54). Each phase enables a strictly larger
 * set of {@link HostingAdjustmentType} capabilities:
 *
 * <ul>
 *   <li>{@link #V1} — bid adjustment only (matches the current optimizer reach, Req 54.1);</li>
 *   <li>{@link #V2} — adds budget adjustment (Req 54.1);</li>
 *   <li>{@link #V3} — adds keyword expansion and negative-keyword addition (the full target set of
 *       Req 22.1).</li>
 * </ul>
 *
 * <p>The active phase is a configurable backend value ({@code adpilot.hosting.phase}, default
 * {@code V1}). The optimizer consults {@link #supports(HostingAdjustmentType)} before emitting any
 * adjustment so a capability not yet implemented for the active phase is never emitted as an
 * executable Operation, and the UI never presents it as executable (Req 22.1/22.2, 54.2/54.3/54.4).</p>
 *
 * <p>Validates: Requirements 22.1, 22.2, 54.1, 54.2, 54.3, 54.4.</p>
 */
public enum HostingPhase {

    /** Bid adjustment only (Req 54.1). */
    V1(EnumSet.of(HostingAdjustmentType.BID)),

    /** Bid + budget adjustment (Req 54.1). */
    V2(EnumSet.of(HostingAdjustmentType.BID, HostingAdjustmentType.BUDGET)),

    /** Bid + budget + keyword + negative-keyword addition — the full target set (Req 22.1, 54.1). */
    V3(EnumSet.allOf(HostingAdjustmentType.class));

    /** The configured fallback phase when {@code adpilot.hosting.phase} is unset or unrecognized. */
    public static final HostingPhase DEFAULT = V1;

    private final Set<HostingAdjustmentType> capabilities;

    HostingPhase(Set<HostingAdjustmentType> capabilities) {
        this.capabilities = capabilities;
    }

    /**
     * Whether the given adjustment capability is implemented and executable in this phase.
     *
     * @param type the adjustment capability; {@code null} is never supported
     * @return {@code true} when the active phase implements {@code type}
     */
    public boolean supports(HostingAdjustmentType type) {
        return type != null && capabilities.contains(type);
    }

    /** The capabilities executable in this phase (immutable view). */
    public Set<HostingAdjustmentType> capabilities() {
        return EnumSet.copyOf(capabilities);
    }

    /**
     * Parse a configured phase string ({@code V1}/{@code V2}/{@code V3}, case-insensitively) into a
     * {@link HostingPhase}, falling back to {@link #DEFAULT} for a {@code null}, blank, or
     * unrecognized value rather than throwing — so a misconfiguration degrades safely to the most
     * conservative phase.
     *
     * @param value the configured phase value
     * @return the matching phase, or {@link #DEFAULT} when unresolved
     */
    public static HostingPhase parse(String value) {
        if (value == null) {
            return DEFAULT;
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            return DEFAULT;
        }
        for (HostingPhase phase : values()) {
            if (phase.name().equals(normalized)) {
                return phase;
            }
        }
        return DEFAULT;
    }
}
