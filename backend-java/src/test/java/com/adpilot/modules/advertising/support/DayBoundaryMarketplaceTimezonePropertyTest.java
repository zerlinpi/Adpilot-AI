package com.adpilot.modules.advertising.support;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Label;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Property-based test for {@link MarketplaceTimezone}, the single source of truth for "today",
 * day-boundary, and date-range computation in the Active_Store's Marketplace_Timezone.
 *
 * <p>Feature: advertising-workspace-rework, Property 74: Day boundaries are computed in the
 * Marketplace_Timezone.
 *
 * <p>Validates: Requirements 30.4, 51.13, 51.14.
 *
 * <p>For any IANA Marketplace_Timezone and any instant "now", the property asserts that:
 * <ul>
 *   <li>"today" is the calendar date of {@code now} resolved in the marketplace zone — never the
 *       server (JVM) zone (Req 51.13);</li>
 *   <li>the current day boundary is the half-open instant range {@code [localMidnight,
 *       nextLocalMidnight)} in the marketplace zone, it brackets {@code now}, and its start lands on
 *       local midnight of that zone;</li>
 *   <li>consecutive day boundaries tile the timeline with no gap or overlap (a day's
 *       {@code endExclusive} equals the next day's {@code startInclusive}), which holds across DST
 *       transitions;</li>
 *   <li>an inclusive date range resolves to the marketplace-zone instants spanning every included
 *       local day (Req 30.4);</li>
 *   <li>an unset/blank/invalid Marketplace_Timezone is rejected with a
 *       {@link MarketplaceTimezoneNotConfiguredException} configuration error and NEVER silently
 *       falls back to the server timezone (Req 51.14).</li>
 * </ul>
 *
 * <p>The zone set deliberately spans the date line (UTC+14 down to UTC-11), half- and quarter-hour
 * offsets, and DST zones in both hemispheres so the midnight and date-line boundary cases required
 * by the testing requirement are exercised. The oracle computes the expected values directly from
 * {@link java.time} using the supplied zone, independent of the production code path.
 */
@Tag("pbt")
@Label("Feature: advertising-workspace-rework, Property 74: Day boundaries are computed in the Marketplace_Timezone")
class DayBoundaryMarketplaceTimezonePropertyTest {

    /**
     * Feature: advertising-workspace-rework, Property 74: Day boundaries are computed in the
     * Marketplace_Timezone.
     *
     * <p>Validates: Requirements 30.4, 51.13, 51.14.
     */
    @Property(tries = 200)
    void dayBoundariesAreComputedInTheMarketplaceTimezone(
            @ForAll("ianaZones") String ianaTimezone,
            @ForAll("instants") Instant now,
            @ForAll("rangeSpanDays") int rangeSpanDays,
            @ForAll("unsetTimezones") String unsetTimezone) {

        ZoneId expectedZone = ZoneId.of(ianaTimezone);

        // --- "today" is computed in the Marketplace_Timezone, not the server zone (Req 51.13) ---
        LocalDate today = MarketplaceTimezone.today(ianaTimezone, now);
        assertThat(today)
                .as("today must be the calendar date of now in the marketplace zone")
                .isEqualTo(LocalDate.ofInstant(now, expectedZone));

        // --- the current day boundary brackets now and aligns to local midnight in the zone ---
        InstantRange currentDay = MarketplaceTimezone.currentDayBoundary(ianaTimezone, now);
        assertThat(currentDay.contains(now))
                .as("the current-day boundary must contain now")
                .isTrue();
        assertThat(currentDay.startInclusive())
                .as("day start must be local midnight of today in the marketplace zone")
                .isEqualTo(today.atStartOfDay(expectedZone).toInstant());
        assertThat(currentDay.endExclusive())
                .as("day end must be local midnight of tomorrow in the marketplace zone")
                .isEqualTo(today.plusDays(1).atStartOfDay(expectedZone).toInstant());
        assertThat(LocalDateTime.ofInstant(currentDay.startInclusive(), expectedZone).toLocalTime())
                .as("the boundary start must fall on the start of the local day — local midnight, or "
                        + "the first valid local time when midnight falls in a DST spring-forward gap")
                .isEqualTo(today.atStartOfDay(expectedZone).toLocalTime());

        // --- consecutive day boundaries tile the timeline with no gap or overlap (DST-safe) ---
        InstantRange dayOf = MarketplaceTimezone.dayBoundary(ianaTimezone, today);
        InstantRange nextDay = MarketplaceTimezone.dayBoundary(ianaTimezone, today.plusDays(1));
        assertThat(dayOf).isEqualTo(currentDay);
        assertThat(dayOf.endExclusive())
                .as("a day's endExclusive must equal the next day's startInclusive")
                .isEqualTo(nextDay.startInclusive());

        // --- an inclusive date range resolves in the marketplace zone (Req 30.4) ---
        LocalDate rangeEnd = today.plusDays(rangeSpanDays);
        InstantRange dateRange = MarketplaceTimezone.dateRange(ianaTimezone, today, rangeEnd);
        assertThat(dateRange.startInclusive())
                .as("date-range start must be local midnight of the first day in the marketplace zone")
                .isEqualTo(today.atStartOfDay(expectedZone).toInstant());
        assertThat(dateRange.endExclusive())
                .as("date-range end must be local midnight after the last day in the marketplace zone")
                .isEqualTo(rangeEnd.plusDays(1).atStartOfDay(expectedZone).toInstant());
        assertThat(dateRange.contains(now))
                .as("a range starting today must contain now")
                .isTrue();

        // --- an unset/invalid Marketplace_Timezone is rejected, never server-zone fallback (51.14) ---
        assertThatThrownBy(() -> MarketplaceTimezone.requireZone(unsetTimezone))
                .as("an unset/invalid timezone must be rejected with a configuration error")
                .isInstanceOf(MarketplaceTimezoneNotConfiguredException.class);
        assertThatThrownBy(() -> MarketplaceTimezone.today(unsetTimezone, now))
                .as("today must not be computed (and must never fall back to the server zone) when the timezone is unset")
                .isInstanceOf(MarketplaceTimezoneNotConfiguredException.class);
        assertThatThrownBy(() -> MarketplaceTimezone.currentDayBoundary(unsetTimezone, now))
                .as("the current-day boundary must not fall back to the server zone when the timezone is unset")
                .isInstanceOf(MarketplaceTimezoneNotConfiguredException.class);
        assertThatThrownBy(() -> MarketplaceTimezone.dateRange(unsetTimezone, today, rangeEnd))
                .as("the date range must not fall back to the server zone when the timezone is unset")
                .isInstanceOf(MarketplaceTimezoneNotConfiguredException.class);
    }

    // --- generators --------------------------------------------------------

    /**
     * IANA zones spanning the date line and DST behaviours so midnight and date-line boundary cases
     * are exercised: UTC+14 (Kiritimati) down to UTC-11 (Pago Pago), half-/quarter-hour offsets
     * (Kolkata +5:30, Kathmandu +5:45, Chatham +12:45), and DST zones in both hemispheres.
     */
    @Provide
    Arbitrary<String> ianaZones() {
        return Arbitraries.of(
                "Pacific/Kiritimati",
                "Pacific/Chatham",
                "Pacific/Apia",
                "Pacific/Pago_Pago",
                "Pacific/Auckland",
                "Australia/Sydney",
                "Asia/Tokyo",
                "Asia/Kathmandu",
                "Asia/Kolkata",
                "Europe/Berlin",
                "Europe/London",
                "Atlantic/Azores",
                "America/Sao_Paulo",
                "America/New_York",
                "America/Los_Angeles",
                "UTC");
    }

    /** Instants spread across decades (1990-2099) at second granularity to explore midnight edges. */
    @Provide
    Arbitrary<Instant> instants() {
        long minEpochSecond = LocalDate.of(1990, 1, 1).atStartOfDay(ZoneId.of("UTC")).toEpochSecond();
        long maxEpochSecond = LocalDate.of(2099, 12, 31).atStartOfDay(ZoneId.of("UTC")).toEpochSecond();
        return Combinators.combine(
                        Arbitraries.longs().between(minEpochSecond, maxEpochSecond),
                        Arbitraries.integers().between(0, 999_999_999))
                .as(Instant::ofEpochSecond);
    }

    /** Inclusive date-range spans from a single day up to a full year. */
    @Provide
    Arbitrary<Integer> rangeSpanDays() {
        return Arbitraries.integers().between(0, 365);
    }

    /** Unset/blank/invalid Marketplace_Timezone values that must be rejected. */
    @Provide
    Arbitrary<String> unsetTimezones() {
        return Arbitraries.of(
                null,
                "",
                "   ",
                "\t",
                "Not/AZone",
                "Invalid",
                "Foo/Bar",
                "Earth/Moon",
                "12345",
                "GMT+99:00");
    }
}
