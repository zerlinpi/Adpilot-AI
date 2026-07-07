package com.adpilot.modules.advertising.hosting;

import com.adpilot.modules.advertising.mapper.MetricQuarantineMapper;
import com.adpilot.modules.advertising.mapper.PerformanceDailyMapper;
import com.adpilot.modules.advertising.mapper.SearchTermDailyMapper;
import com.adpilot.modules.apisync.mapper.ExternalEntityMappingMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.jqwik.api.*;
import net.jqwik.api.constraints.IntRange;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Property-based test for the {@link ReportIngestionServiceImpl#deriveDataStatus} method.
 *
 * <p>Feature: amazon-ads-ai-hosting-system, Property 9: Data status is a pure function of age
 *
 * <p><b>Validates: Requirements 2.4, 32.4</b>
 *
 * <p>Properties tested:
 * <ol>
 *   <li>Rows with report_date older than or equal to (today - finalizationLagDays) → "finalized"</li>
 *   <li>Rows with report_date newer than (today - finalizationLagDays) → "preliminary"</li>
 *   <li>Same date and lag always produces the same status (pure function / deterministic)</li>
 *   <li>The function is monotonic: if date A is older than date B and A is preliminary, then B is also preliminary</li>
 * </ol>
 */
@Tag("pbt")
@Label("Feature: amazon-ads-ai-hosting-system, Property 9: Data status is a pure function of age")
class DataStatusDerivationPropertyTest {

    private final ReportIngestionServiceImpl service;

    DataStatusDerivationPropertyTest() {
        // The deriveDataStatus method only uses LocalDate.now() internally.
        // It does not depend on any injected dependencies, so we can pass mocks.
        this.service = new ReportIngestionServiceImpl(
                mock(PerformanceDailyMapper.class),
                mock(SearchTermDailyMapper.class),
                mock(MetricQuarantineMapper.class),
                mock(ExternalEntityMappingMapper.class),
                new ObjectMapper()
        );
    }

    // ── Property 1: Rows older than finalizationLagDays are finalized ─────────────

    /**
     * Feature: amazon-ads-ai-hosting-system, Property 9: Data status is a pure function of age
     *
     * <p><b>Validates: Requirements 2.4, 32.4</b></p>
     *
     * <p>Any report_date that is strictly before (today - finalizationLagDays) is "finalized".</p>
     */
    @Property(tries = 150)
    void olderThanFinalizationLagDaysIsFinalized(
            @ForAll @IntRange(min = 0, max = 90) int finalizationLagDays,
            @ForAll @IntRange(min = 1, max = 365) int extraDaysOld) {

        // report_date is finalizationLagDays + extraDaysOld days in the past (strictly older)
        LocalDate reportDate = LocalDate.now().minusDays(finalizationLagDays + extraDaysOld);

        String status = service.deriveDataStatus(reportDate, finalizationLagDays);

        assertThat(status)
                .as("Report date %s with lag %d should be finalized (extra %d days old)",
                        reportDate, finalizationLagDays, extraDaysOld)
                .isEqualTo("finalized");
    }

    // ── Property 2: Rows at exactly the finalization cutoff are finalized ──────────

    /**
     * Feature: amazon-ads-ai-hosting-system, Property 9: Data status is a pure function of age
     *
     * <p><b>Validates: Requirements 2.4, 32.4</b></p>
     *
     * <p>A report_date that is exactly (today - finalizationLagDays) is "finalized"
     * (boundary condition: equal dates are finalized).</p>
     */
    @Property(tries = 120)
    void exactlyAtFinalizationCutoffIsFinalized(
            @ForAll @IntRange(min = 0, max = 90) int finalizationLagDays) {

        LocalDate reportDate = LocalDate.now().minusDays(finalizationLagDays);

        String status = service.deriveDataStatus(reportDate, finalizationLagDays);

        assertThat(status)
                .as("Report date exactly at cutoff (today - %d) should be finalized", finalizationLagDays)
                .isEqualTo("finalized");
    }

    // ── Property 3: Rows newer than finalization cutoff are preliminary ────────────

    /**
     * Feature: amazon-ads-ai-hosting-system, Property 9: Data status is a pure function of age
     *
     * <p><b>Validates: Requirements 2.4, 32.4</b></p>
     *
     * <p>Any report_date that is strictly after (today - finalizationLagDays) is "preliminary".</p>
     */
    @Property(tries = 150)
    void newerThanFinalizationLagDaysIsPreliminary(
            @ForAll @IntRange(min = 1, max = 90) int finalizationLagDays,
            @ForAll @IntRange(min = 0, max = 89) int daysBeforeCutoff) {

        // Ensure we generate dates that are newer than the cutoff
        // cutoff = today - finalizationLagDays
        // report_date must be AFTER cutoff, so report_date = today - (finalizationLagDays - 1 - daysBeforeCutoff)
        int daysAgo = finalizationLagDays - 1 - Math.min(daysBeforeCutoff, finalizationLagDays - 1);
        LocalDate reportDate = LocalDate.now().minusDays(daysAgo);

        // Only assert if reportDate is actually after the cutoff
        LocalDate cutoff = LocalDate.now().minusDays(finalizationLagDays);
        Assume.that(reportDate.isAfter(cutoff));

        String status = service.deriveDataStatus(reportDate, finalizationLagDays);

        assertThat(status)
                .as("Report date %s newer than cutoff %s should be preliminary",
                        reportDate, cutoff)
                .isEqualTo("preliminary");
    }

    // ── Property 4: Same inputs always produce the same output (purity) ───────────

    /**
     * Feature: amazon-ads-ai-hosting-system, Property 9: Data status is a pure function of age
     *
     * <p><b>Validates: Requirements 2.4, 32.4</b></p>
     *
     * <p>Calling deriveDataStatus with the same reportDate and finalizationLagDays
     * always returns the same result — the function is deterministic.</p>
     */
    @Property(tries = 150)
    void sameInputsAlwaysProduceSameOutput(
            @ForAll("reportDates") LocalDate reportDate,
            @ForAll @IntRange(min = 0, max = 90) int finalizationLagDays) {

        String result1 = service.deriveDataStatus(reportDate, finalizationLagDays);
        String result2 = service.deriveDataStatus(reportDate, finalizationLagDays);
        String result3 = service.deriveDataStatus(reportDate, finalizationLagDays);

        assertThat(result1)
                .as("Repeated calls with same inputs must return identical results")
                .isEqualTo(result2)
                .isEqualTo(result3);
    }

    // ── Property 5: Monotonicity — older dates are at least as likely to be finalized ─

    /**
     * Feature: amazon-ads-ai-hosting-system, Property 9: Data status is a pure function of age
     *
     * <p><b>Validates: Requirements 2.4, 32.4</b></p>
     *
     * <p>If date A is older than date B and date B is "finalized", then date A
     * must also be "finalized". Equivalently, if A is "preliminary" then B
     * (which is newer or equal) must also be "preliminary".</p>
     */
    @Property(tries = 150)
    void monotonicity_olderDatesFinalizedImpliesNewerOrSame(
            @ForAll("reportDates") LocalDate dateA,
            @ForAll("reportDates") LocalDate dateB,
            @ForAll @IntRange(min = 0, max = 90) int finalizationLagDays) {

        // Ensure dateA is strictly before dateB
        Assume.that(dateA.isBefore(dateB));

        String statusA = service.deriveDataStatus(dateA, finalizationLagDays);
        String statusB = service.deriveDataStatus(dateB, finalizationLagDays);

        // If the older date (A) is preliminary, then the newer date (B) must also be preliminary
        if ("preliminary".equals(statusA)) {
            assertThat(statusB)
                    .as("If older date %s is preliminary, newer date %s must also be preliminary (lag=%d)",
                            dateA, dateB, finalizationLagDays)
                    .isEqualTo("preliminary");
        }

        // If the newer date (B) is finalized, then the older date (A) must also be finalized
        if ("finalized".equals(statusB)) {
            assertThat(statusA)
                    .as("If newer date %s is finalized, older date %s must also be finalized (lag=%d)",
                            dateB, dateA, finalizationLagDays)
                    .isEqualTo("finalized");
        }
    }

    // ── Property 6: Output is always one of the two valid statuses ────────────────

    /**
     * Feature: amazon-ads-ai-hosting-system, Property 9: Data status is a pure function of age
     *
     * <p><b>Validates: Requirements 2.4, 32.4</b></p>
     *
     * <p>The function always returns either "preliminary" or "finalized" — never null
     * or any other value.</p>
     */
    @Property(tries = 120)
    void outputIsAlwaysAValidStatus(
            @ForAll("reportDates") LocalDate reportDate,
            @ForAll @IntRange(min = 0, max = 90) int finalizationLagDays) {

        String status = service.deriveDataStatus(reportDate, finalizationLagDays);

        assertThat(status)
                .as("deriveDataStatus must return a valid status string")
                .isNotNull()
                .isIn("preliminary", "finalized");
    }

    // ── Generators ────────────────────────────────────────────────────────────────

    /**
     * Generates report dates within a reasonable range around today:
     * from 365 days in the past to 30 days in the future.
     * This covers finalized dates, boundary dates, and preliminary/future dates.
     */
    @Provide
    Arbitrary<LocalDate> reportDates() {
        LocalDate today = LocalDate.now();
        return Arbitraries.integers()
                .between(-365, 30)
                .map(today::plusDays);
    }
}
