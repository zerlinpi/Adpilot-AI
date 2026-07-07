package com.adpilot.modules.logistics.service.impl;

import com.adpilot.modules.logistics.entity.ShipmentLegEntity;
import com.adpilot.modules.logistics.mapper.CarrierMapper;
import com.adpilot.modules.logistics.mapper.ShipmentLegMapper;
import com.adpilot.modules.logistics.mapper.ShipmentMapper;
import com.adpilot.modules.logistics.vo.ShipmentLegVo;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.apache.ibatis.builder.MapperBuilderAssistant;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Property-based test for the ordering behaviour of
 * {@link ShipmentLegServiceImpl#listLegs(String)}.
 *
 * <p>Feature: platform-ux-logistics-enhancements, Property 8: Shipment legs are
 * ordered by sequence.
 *
 * <p>For any set of shipment legs on a shipment, listing them returns the legs
 * in non-decreasing order of sequence number (Requirement 4.4).
 *
 * <p>There is no live database in unit tests, so the {@link ShipmentLegMapper}
 * is mocked to behave like a faithful relational table: its {@code selectList}
 * honours the {@link LambdaQueryWrapper}'s {@code ORDER BY} clause. The legs are
 * stored in an arbitrary (typically unsorted) generated order, and the mock
 * sorts them according to whatever ordering the wrapper requests — ascending,
 * descending, or insertion order when no {@code ORDER BY} is present. This means
 * the property genuinely exercises the service's own
 * {@code orderByAsc(sequenceNo)} wrapper construction: had the service forgotten
 * to request the ascending order, the mock would return the legs unsorted and
 * the non-decreasing assertion would fail for any non-pre-sorted input.
 *
 * Validates: Requirements 4.4
 */
class ShipmentLegOrderingPropertyTest {

    static {
        // listLegs builds a LambdaQueryWrapper<ShipmentLegEntity> whose lambda
        // column references (sequence_no, shipment_id) require MyBatis-Plus entity
        // metadata that is normally populated during Spring mapper scanning.
        // Register it once for this standalone (no-context) test so the wrapper's
        // SQL segment — and therefore its ORDER BY clause — resolves.
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), ShipmentLegEntity.class);
    }

    // Feature: platform-ux-logistics-enhancements, Property 8: Shipment legs are ordered by sequence
    @Property(tries = 200)
    void listLegsReturnsLegsInNonDecreasingSequenceOrder(@ForAll("sequenceNumbers") List<Integer> sequences) {
        UUID shipmentId = UUID.randomUUID();

        ShipmentLegMapper shipmentLegMapper = mock(ShipmentLegMapper.class);
        ShipmentMapper shipmentMapper = mock(ShipmentMapper.class);
        CarrierMapper carrierMapper = mock(CarrierMapper.class);

        // Materialize legs in the generated (arbitrary, possibly unsorted) order.
        List<ShipmentLegEntity> stored = new ArrayList<>();
        for (Integer sequenceNo : sequences) {
            stored.add(ShipmentLegEntity.builder()
                    .id(UUID.randomUUID())
                    .shipmentId(shipmentId)
                    .legType("first_leg")
                    .sequenceNo(sequenceNo)
                    .carrierId(null) // listLegs resolves a null carrier to null carrierName
                    .legCost(BigDecimal.ZERO)
                    .build());
        }

        // Faithful DB: return rows ordered exactly as the wrapper's ORDER BY asks.
        when(shipmentLegMapper.selectList(any())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            LambdaQueryWrapper<ShipmentLegEntity> wrapper =
                    (LambdaQueryWrapper<ShipmentLegEntity>) invocation.getArgument(0);
            return applyWrapperOrdering(stored, wrapper);
        });

        ShipmentLegServiceImpl service =
                new ShipmentLegServiceImpl(shipmentLegMapper, shipmentMapper, carrierMapper);

        List<ShipmentLegVo> result = service.listLegs(shipmentId.toString());

        // Every stored leg is returned (the mock neither drops nor adds rows).
        assertThat(result).hasSize(sequences.size());

        // Property 8 (Req 4.4): the returned legs are in non-decreasing sequence order.
        for (int i = 1; i < result.size(); i++) {
            assertThat(result.get(i).getSequenceNo())
                    .as("leg at index %d must have a sequence >= the previous leg", i)
                    .isGreaterThanOrEqualTo(result.get(i - 1).getSequenceNo());
        }
    }

    /**
     * Simulates the relational engine: sorts the stored rows according to the
     * {@code ORDER BY} clause encoded in the wrapper. Ascending and descending on
     * {@code sequence_no} are honoured; with no recognised ordering the rows are
     * returned in their original (insertion) order.
     */
    private List<ShipmentLegEntity> applyWrapperOrdering(List<ShipmentLegEntity> rows,
                                                         LambdaQueryWrapper<ShipmentLegEntity> wrapper) {
        String sql = wrapper.getSqlSegment() == null ? "" : wrapper.getSqlSegment().toUpperCase();
        List<ShipmentLegEntity> copy = new ArrayList<>(rows);
        if (sql.contains("ORDER BY") && sql.contains("SEQUENCE_NO")) {
            Comparator<ShipmentLegEntity> bySequence =
                    Comparator.comparingInt(ShipmentLegEntity::getSequenceNo);
            // Locate the direction keyword that follows the sequence_no column.
            int seqIdx = sql.lastIndexOf("SEQUENCE_NO");
            boolean descending = sql.indexOf("DESC", seqIdx) >= 0;
            copy.sort(descending ? bySequence.reversed() : bySequence);
        }
        return copy;
    }

    // --- generators -----------------------------------------------------------

    @Provide
    Arbitrary<List<Integer>> sequenceNumbers() {
        // Arbitrary sequence numbers (including duplicates and negatives) over a
        // set of legs; an empty set is allowed and lists may exceed the typical
        // 1..20 range to stress the ordering invariant beyond business bounds.
        return Arbitraries.integers().between(-1000, 1000).list().ofMinSize(0).ofMaxSize(30);
    }
}
