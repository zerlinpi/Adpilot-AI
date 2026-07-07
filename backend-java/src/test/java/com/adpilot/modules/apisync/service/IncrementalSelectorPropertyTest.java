package com.adpilot.modules.apisync.service;

import com.adpilot.modules.apisync.model.ExternalRecord;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property-based test for {@link IncrementalSelector}, the pure incremental
 * selection logic backing the watermark store (task 3.1).
 *
 * Feature: core-platform-completion, Property 2: Incremental retrieval selects
 * only post-watermark records.
 *
 * For any set of external records each carrying a change timestamp and any
 * watermark value, the records selected for an incremental sync are exactly
 * those whose change timestamp is strictly after the watermark; when no
 * watermark exists or a full resync is requested, all records are selected.
 *
 * Validates: Requirements 1.1.6, 1.1.7
 */
class IncrementalSelectorPropertyTest {

    // Feature: core-platform-completion, Property 2: Incremental retrieval selects only post-watermark records
    @Property(tries = 200)
    void incrementalSelectionPicksExactlyStrictlyAfterWatermark(
            @ForAll("recordLists") List<ExternalRecord> records,
            @ForAll("watermarks") Instant watermark) {

        List<ExternalRecord> selected =
                IncrementalSelector.selectIncremental(records, Optional.of(watermark), false);

        // Selected set is exactly the records strictly after the watermark (Req 1.1.6).
        List<ExternalRecord> expected = records.stream()
                .filter(r -> r.changedAt() != null && r.changedAt().isAfter(watermark))
                .toList();
        assertThat(selected).containsExactlyElementsOf(expected);

        // Every selected record is strictly after the watermark; none are at/before it.
        assertThat(selected).allMatch(r -> r.changedAt() != null && r.changedAt().isAfter(watermark));
        assertThat(selected).noneMatch(r -> r.changedAt() != null && !r.changedAt().isAfter(watermark));
    }

    // Feature: core-platform-completion, Property 2: Incremental retrieval selects only post-watermark records
    @Property(tries = 200)
    void noWatermarkSelectsAllRecords(@ForAll("recordLists") List<ExternalRecord> records) {
        // Req 1.1.7: no prior watermark -> full pull selects every record.
        List<ExternalRecord> selected =
                IncrementalSelector.selectIncremental(records, Optional.empty(), false);

        assertThat(selected).containsExactlyElementsOf(records);
    }

    // Feature: core-platform-completion, Property 2: Incremental retrieval selects only post-watermark records
    @Property(tries = 200)
    void fullResyncSelectsAllRecordsRegardlessOfWatermark(
            @ForAll("recordLists") List<ExternalRecord> records,
            @ForAll("watermarks") Instant watermark) {
        // Req 1.1.7: an explicit full resync ignores the watermark and selects all.
        List<ExternalRecord> selected =
                IncrementalSelector.selectIncremental(records, Optional.of(watermark), true);

        assertThat(selected).containsExactlyElementsOf(records);
    }

    // --- generators -----------------------------------------------------------

    @Provide
    Arbitrary<List<ExternalRecord>> recordLists() {
        return records().list().ofMaxSize(30);
    }

    private Arbitrary<ExternalRecord> records() {
        Arbitrary<String> ids = Arbitraries.strings().withCharRange('a', 'z').numeric()
                .ofMinLength(1).ofMaxLength(12);
        Arbitrary<String> entityTypes = Arbitraries.of("order", "product");
        Arbitrary<String> statuses = Arbitraries.of("active", "cancelled", "processing", "draft");
        // Bound timestamps around the watermark range so cases land on both
        // sides of (and exactly on) the watermark, exercising the strict bound.
        Arbitrary<Instant> changedAt = timestamps();
        return Combinators.combine(ids, entityTypes, changedAt, statuses)
                .as((id, type, ts, status) ->
                        new ExternalRecord(id, type, ts, status, Map.of()));
    }

    @Provide
    Arbitrary<Instant> watermarks() {
        return timestamps();
    }

    /**
     * Timestamps drawn from a small epoch-second window so that generated
     * records frequently coincide with, precede, or follow the watermark,
     * giving the strictly-after boundary meaningful coverage.
     */
    private Arbitrary<Instant> timestamps() {
        return Arbitraries.longs().between(1_700_000_000L, 1_700_000_050L)
                .map(Instant::ofEpochSecond);
    }
}
