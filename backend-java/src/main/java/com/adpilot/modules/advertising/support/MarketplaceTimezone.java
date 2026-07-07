package com.adpilot.modules.advertising.support;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Objects;

/**
 * Pure, side-effect-free computation of "today", day boundaries, date ranges,
 * and scheduling instants in an Active_Store's <strong>Marketplace_Timezone</strong>
 * (the IANA {@code timezone} on the {@code MarketplaceEntity}).
 *
 * <p>This is the single source of truth for day-boundary logic required by
 * Requirements 30.4, 51.13, and 51.14:
 * <ul>
 *   <li><strong>51.13</strong> — every "today"/day-boundary metric and every
 *       scheduled recurring job MUST resolve its boundary in the Active_Store's
 *       Marketplace_Timezone, not the server (JVM) timezone.</li>
 *   <li><strong>51.14</strong> — if the Marketplace_Timezone is unset (or holds
 *       an invalid IANA id) the computation MUST be rejected with a
 *       configuration error identifying the missing timezone, and MUST NEVER
 *       fall back to the server timezone. Accordingly this class takes the IANA
 *       id explicitly and never reads {@link ZoneId#systemDefault()}.</li>
 *   <li><strong>30.4</strong> — the KPI panel's date range / comparison period
 *       is computed in the Marketplace_Timezone via {@link #dateRange}.</li>
 * </ul>
 *
 * <p>The class holds no Spring dependencies and reads no ambient clock or zone,
 * so it can be unit- and property-tested in isolation (it is the target of the
 * day-boundary timezone property test, Property 74 / task 14.26). Callers pass
 * the marketplace's {@code timezone} string and an explicit {@link Instant}
 * "now"; the service layer is responsible for loading the Marketplace_Timezone
 * for the Active_Store and supplying the current instant.
 *
 * <p>All ranges are returned as half-open {@link InstantRange} intervals
 * {@code [startInclusive, endExclusive)} so consecutive days/ranges tile the
 * timeline without overlap or gaps. Day starts are computed with
 * {@link LocalDate#atStartOfDay(ZoneId)}, which resolves daylight-saving gaps
 * and overlaps correctly for the zone.
 */
public final class MarketplaceTimezone {

    private MarketplaceTimezone() {
        // Utility class — not instantiable.
    }

    /**
     * Resolve the Active_Store's Marketplace_Timezone to a {@link ZoneId}.
     *
     * @param ianaTimezone the IANA timezone id stored on the Marketplace
     * @return the resolved {@link ZoneId} (never the server default)
     * @throws MarketplaceTimezoneNotConfiguredException when {@code ianaTimezone}
     *         is {@code null}, blank, or not a recognized IANA id (Req 51.14)
     */
    public static ZoneId requireZone(String ianaTimezone) {
        if (ianaTimezone == null || ianaTimezone.isBlank()) {
            throw new MarketplaceTimezoneNotConfiguredException(ianaTimezone);
        }
        try {
            // Strict parsing: do NOT allow the system-default short-id aliases that
            // could mask a misconfiguration. ZoneId.of validates against the IANA db.
            return ZoneId.of(ianaTimezone.trim());
        } catch (DateTimeException ex) {
            throw new MarketplaceTimezoneNotConfiguredException(ianaTimezone);
        }
    }

    /**
     * The current calendar date ("today") in the Marketplace_Timezone.
     *
     * @param ianaTimezone the Marketplace_Timezone IANA id
     * @param now          the current instant (supplied by the caller's clock)
     * @return the local date at {@code now} in the marketplace's zone
     * @throws MarketplaceTimezoneNotConfiguredException when the timezone is unset/invalid
     */
    public static LocalDate today(String ianaTimezone, Instant now) {
        ZoneId zone = requireZone(ianaTimezone);
        Objects.requireNonNull(now, "now");
        return LocalDate.ofInstant(now, zone);
    }

    /**
     * The day-boundary instant range for a specific local date in the
     * Marketplace_Timezone, i.e. {@code [startOfDay, startOfNextDay)}.
     *
     * @param ianaTimezone the Marketplace_Timezone IANA id
     * @param date         the local calendar date in the marketplace's zone
     * @return the half-open instant range covering that local day
     * @throws MarketplaceTimezoneNotConfiguredException when the timezone is unset/invalid
     */
    public static InstantRange dayBoundary(String ianaTimezone, LocalDate date) {
        ZoneId zone = requireZone(ianaTimezone);
        Objects.requireNonNull(date, "date");
        Instant start = date.atStartOfDay(zone).toInstant();
        Instant endExclusive = date.plusDays(1).atStartOfDay(zone).toInstant();
        return new InstantRange(start, endExclusive);
    }

    /**
     * The day-boundary instant range covering "today" in the Marketplace_Timezone,
     * derived from {@code now}.
     *
     * @param ianaTimezone the Marketplace_Timezone IANA id
     * @param now          the current instant
     * @return the half-open instant range covering the marketplace's current day
     * @throws MarketplaceTimezoneNotConfiguredException when the timezone is unset/invalid
     */
    public static InstantRange currentDayBoundary(String ianaTimezone, Instant now) {
        return dayBoundary(ianaTimezone, today(ianaTimezone, now));
    }

    /**
     * The instant range for an inclusive local date range
     * {@code [startInclusive .. endInclusive]} in the Marketplace_Timezone,
     * returned as half-open instants {@code [startOfStartDay, startOfDayAfterEnd)}.
     *
     * <p>Used by the KPI panel to compute a date range and its comparison period
     * in the Marketplace_Timezone (Req 30.4).
     *
     * @param ianaTimezone   the Marketplace_Timezone IANA id
     * @param startInclusive the first local date in the range
     * @param endInclusive   the last local date in the range (must not precede start)
     * @return the half-open instant range spanning every included local day
     * @throws MarketplaceTimezoneNotConfiguredException when the timezone is unset/invalid
     * @throws IllegalArgumentException                  when {@code endInclusive} precedes {@code startInclusive}
     */
    public static InstantRange dateRange(String ianaTimezone, LocalDate startInclusive, LocalDate endInclusive) {
        ZoneId zone = requireZone(ianaTimezone);
        Objects.requireNonNull(startInclusive, "startInclusive");
        Objects.requireNonNull(endInclusive, "endInclusive");
        if (endInclusive.isBefore(startInclusive)) {
            throw new IllegalArgumentException(
                    "endInclusive (" + endInclusive + ") must not precede startInclusive (" + startInclusive + ")");
        }
        Instant start = startInclusive.atStartOfDay(zone).toInstant();
        Instant endExclusive = endInclusive.plusDays(1).atStartOfDay(zone).toInstant();
        return new InstantRange(start, endExclusive);
    }
}
