package com.adpilot.modules.logistics.service.impl;

import com.adpilot.modules.logistics.entity.CartonSpecEntity;
import com.adpilot.modules.logistics.mapper.CartonSpecMapper;
import com.adpilot.modules.logistics.vo.CartonTotalsVo;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Property-based test for carton totals computation.
 *
 * Feature: platform-ux-logistics-enhancements, Property 3: Carton totals equal
 * the per-spec sums.
 *
 * <p>For any set of carton-spec entries on a shipment, the total box count
 * equals the sum of box counts across all entries, and the total unit quantity
 * equals the sum of (units per box × box count) across all entries; an empty
 * set yields totals of 0 and 0.</p>
 *
 * <p>{@link CartonSpecMapper#selectList} is mocked to return an arbitrary list
 * of carton specs, so {@link CartonSpecServiceImpl#totals} is exercised over
 * many inputs without a database. Box counts and units-per-box are generated
 * within the Requirement 5.1 bounds (1–1,000,000). The oracle uses
 * {@code long} arithmetic to match the service and to avoid {@code int}
 * overflow when summing the products.</p>
 *
 * Validates: Requirements 5.3, 5.4
 */
class CartonTotalsPropertyTest {

    /** Req 5.1 integer bounds for units-per-box and box-count. */
    private static final int MIN_COUNT = 1;
    private static final int MAX_COUNT = 1_000_000;

    /** A single carton-spec entry's generated counts. */
    record SpecCounts(int boxCount, int unitsPerBox) {
    }

    // Feature: platform-ux-logistics-enhancements, Property 3: Carton totals equal the per-spec sums
    // Req 5.3, 5.4: total box count == sum(boxCount); total unit quantity == sum(unitsPerBox * boxCount).
    @Property(tries = 100)
    void totalsEqualThePerSpecSums(@ForAll("specLists") List<SpecCounts> specs) {
        String shipmentId = UUID.randomUUID().toString();

        CartonSpecMapper mapper = mock(CartonSpecMapper.class);
        when(mapper.selectList(any())).thenReturn(toEntities(shipmentId, specs));
        CartonSpecServiceImpl service = new CartonSpecServiceImpl(mapper);

        // Independent oracle using long arithmetic to avoid overflow.
        long expectedBoxCount = 0L;
        long expectedUnitQuantity = 0L;
        for (SpecCounts s : specs) {
            expectedBoxCount += s.boxCount();
            expectedUnitQuantity += (long) s.unitsPerBox() * (long) s.boxCount();
        }

        CartonTotalsVo totals = service.totals(shipmentId);

        assertThat(totals.getTotalBoxCount()).isEqualTo(expectedBoxCount);
        assertThat(totals.getTotalUnitQuantity()).isEqualTo(expectedUnitQuantity);
        assertThat(totals.getShipmentId()).isEqualTo(shipmentId);
    }

    // Feature: platform-ux-logistics-enhancements, Property 3: an empty set yields totals of 0 and 0.
    // Req 5.4: zero carton specs -> total box count 0, total unit quantity 0.
    @Property(tries = 100)
    void emptySetYieldsZeroTotals(@ForAll("shipmentIds") String shipmentId) {
        CartonSpecMapper mapper = mock(CartonSpecMapper.class);
        when(mapper.selectList(any())).thenReturn(List.of());
        CartonSpecServiceImpl service = new CartonSpecServiceImpl(mapper);

        CartonTotalsVo totals = service.totals(shipmentId);

        assertThat(totals.getTotalBoxCount()).isZero();
        assertThat(totals.getTotalUnitQuantity()).isZero();
        assertThat(totals.getShipmentId()).isEqualTo(shipmentId);
    }

    // --- helpers --------------------------------------------------------------

    private static List<CartonSpecEntity> toEntities(String shipmentId, List<SpecCounts> specs) {
        UUID shipmentUuid = UUID.fromString(shipmentId);
        return specs.stream()
                .map(s -> CartonSpecEntity.builder()
                        .id(UUID.randomUUID())
                        .shipmentId(shipmentUuid)
                        .boxCount(s.boxCount())
                        .unitsPerBox(s.unitsPerBox())
                        .build())
                .toList();
    }

    // --- generators -----------------------------------------------------------

    @Provide
    Arbitrary<List<SpecCounts>> specLists() {
        Arbitrary<Integer> boxCount = Arbitraries.integers().between(MIN_COUNT, MAX_COUNT);
        Arbitrary<Integer> unitsPerBox = Arbitraries.integers().between(MIN_COUNT, MAX_COUNT);
        Arbitrary<SpecCounts> spec = Combinators.combine(boxCount, unitsPerBox).as(SpecCounts::new);
        // 0..100 entries per shipment (Req 5.1 allows 1..100; include the empty set for the 0/0 case).
        return spec.list().ofMinSize(0).ofMaxSize(100);
    }

    @Provide
    Arbitrary<String> shipmentIds() {
        // Build random UUIDs from two longs so the property runs the full set of tries.
        Arbitrary<Long> hi = Arbitraries.longs();
        Arbitrary<Long> lo = Arbitraries.longs();
        return Combinators.combine(hi, lo).as((h, l) -> new UUID(h, l).toString());
    }
}
