package com.adpilot.modules.apisync.service.impl;

import com.adpilot.modules.apisync.entity.SyncWatermarkEntity;
import com.adpilot.modules.apisync.mapper.SyncWatermarkMapper;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Property-based test for {@link WatermarkStoreImpl#advance}.
 *
 * Feature: core-platform-completion, Property 3: Successful sync advances the
 * watermark monotonically to the latest processed record.
 *
 * For any batch of successfully processed records, the resulting watermark
 * equals the maximum change timestamp among them and is never less than the
 * prior watermark. Records are advanced one-by-one in arbitrary order to also
 * exercise order-independence and the never-regress guarantee.
 *
 * Validates: Requirements 1.1.8
 */
class WatermarkStorePropertyTest {

    private static final UUID STORE_ID = UUID.randomUUID();
    private static final String ENTITY_TYPE = "order";

    /** A generated scenario: an optional prior watermark and a non-empty batch of change timestamps. */
    record Scenario(Optional<Instant> prior, List<Instant> changedAts) {
    }

    // Feature: core-platform-completion, Property 3: Successful sync advances the watermark monotonically to the latest processed record
    @Property(tries = 200)
    void advanceMovesWatermarkToBatchMaxAndNeverRegresses(@ForAll("scenarios") Scenario scenario) {
        // Stateful in-memory backing for the (STORE_ID, ENTITY_TYPE) watermark row.
        SyncWatermarkEntity[] row = new SyncWatermarkEntity[1];
        scenario.prior().ifPresent(p -> row[0] = SyncWatermarkEntity.builder()
                .id(UUID.randomUUID())
                .storeId(STORE_ID)
                .entityType(ENTITY_TYPE)
                .watermarkAt(LocalDateTime.ofInstant(p, ZoneOffset.UTC))
                .build());

        SyncWatermarkMapper mapper = mock(SyncWatermarkMapper.class);
        when(mapper.selectOne(any())).thenAnswer(inv -> row[0]);
        when(mapper.insert(any(SyncWatermarkEntity.class))).thenAnswer(inv -> {
            row[0] = inv.getArgument(0);
            return 1;
        });
        when(mapper.updateById(any(SyncWatermarkEntity.class))).thenAnswer(inv -> {
            row[0] = inv.getArgument(0);
            return 1;
        });

        WatermarkStoreImpl store = new WatermarkStoreImpl(mapper);

        // Process the batch: each successfully processed record advances the watermark.
        for (Instant changedAt : scenario.changedAts()) {
            store.advance(STORE_ID, ENTITY_TYPE, changedAt);
        }

        Instant batchMax = scenario.changedAts().stream().max(Comparator.naturalOrder()).orElseThrow();
        Instant expected = scenario.prior()
                .map(p -> p.isAfter(batchMax) ? p : batchMax)
                .orElse(batchMax);

        Optional<Instant> result = store.get(STORE_ID, ENTITY_TYPE);

        // Resulting watermark equals max(prior, latest processed record).
        assertThat(result).contains(expected);

        // Never regresses below the prior watermark.
        scenario.prior().ifPresent(p -> assertThat(result.orElseThrow()).isAfterOrEqualTo(p));

        // Never less than any processed record's change timestamp (advanced to the latest).
        Instant finalWatermark = result.orElseThrow();
        for (Instant changedAt : scenario.changedAts()) {
            assertThat(finalWatermark).isAfterOrEqualTo(changedAt);
        }
    }

    // --- generators -----------------------------------------------------------

    @Provide
    Arbitrary<Scenario> scenarios() {
        return Combinators.combine(priorWatermarks(), changeTimestamps())
                .as(Scenario::new);
    }

    private Arbitrary<Optional<Instant>> priorWatermarks() {
        Arbitrary<Optional<Instant>> present = instants().map(Optional::of);
        Arbitrary<Optional<Instant>> absent = Arbitraries.just(Optional.empty());
        return Arbitraries.oneOf(present, absent);
    }

    private Arbitrary<List<Instant>> changeTimestamps() {
        return instants().list().ofMinSize(1).ofMaxSize(50);
    }

    /** UTC instants at millisecond resolution so LocalDateTime round-trips are exact. */
    private Arbitrary<Instant> instants() {
        return Arbitraries.longs().between(0L, 4_000_000_000_000L).map(Instant::ofEpochMilli);
    }
}
