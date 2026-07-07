package com.adpilot.modules.logistics.service.impl;

import com.adpilot.modules.logistics.dto.CustomsClearanceDto;
import com.adpilot.modules.logistics.entity.CustomsClearanceEntity;
import com.adpilot.modules.logistics.mapper.CustomsClearanceMapper;
import com.adpilot.modules.logistics.vo.CustomsClearanceVo;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Property-based test for {@link CustomsClearanceServiceImpl}.
 *
 * Feature: platform-ux-logistics-enhancements, Property 6: Customs status update round-trips for permitted values
 *
 * <p>For any shipment and any clearance status in the permitted set
 * (not-started, declared, in-review, cleared, held), updating the customs
 * clearance persists that status and returns the saved record with that
 * status; reading a shipment that has no customs record returns a not-started
 * state.</p>
 *
 * <p>The {@link CustomsClearanceMapper} is mocked: {@code selectOne} returns
 * {@code null} initially so {@code update} takes the insert path; the inserted
 * entity is captured and fed back to a subsequent read to prove the status
 * round-trips through persistence.</p>
 *
 * <b>Validates: Requirements 7.2, 7.6</b>
 */
class CustomsClearanceServiceImplPropertyTest {

    // Feature: platform-ux-logistics-enhancements, Property 6: Customs status update round-trips for permitted values
    // Req 7.2: updating with a permitted status persists it and returns the saved record with that status.
    @Property(tries = 200)
    void permittedStatusUpdateRoundTrips(
            @ForAll("shipmentIds") String shipmentId,
            @ForAll("permittedStatuses") String status,
            @ForAll("declarationRefs") String declarationRef,
            @ForAll("dutiesAmounts") BigDecimal duties) {

        CustomsClearanceMapper mapper = mock(CustomsClearanceMapper.class);
        CustomsClearanceServiceImpl service = new CustomsClearanceServiceImpl(mapper);

        // No existing record -> update inserts a new one.
        when(mapper.selectOne(any())).thenReturn(null);

        CustomsClearanceDto dto = new CustomsClearanceDto();
        dto.setClearanceStatus(status);
        dto.setDeclarationRef(declarationRef);
        dto.setDutiesTaxes(duties);

        CustomsClearanceVo saved = service.update(shipmentId, dto);

        // The saved VO carries the submitted status (Req 7.2).
        assertThat(saved.getClearanceStatus()).isEqualTo(status);
        assertThat(saved.getShipmentId()).isEqualTo(shipmentId);

        // Capture the inserted entity; it carries the submitted status.
        ArgumentCaptor<CustomsClearanceEntity> captor =
                ArgumentCaptor.forClass(CustomsClearanceEntity.class);
        verify(mapper).insert(captor.capture());
        CustomsClearanceEntity persisted = captor.getValue();
        assertThat(persisted.getClearanceStatus()).isEqualTo(status);

        // A subsequent read returns the persisted record with the same status,
        // proving the status round-trips through persistence (Req 7.2).
        when(mapper.selectOne(any())).thenReturn(persisted);
        CustomsClearanceVo readBack = service.get(shipmentId);
        assertThat(readBack.getClearanceStatus()).isEqualTo(status);
    }

    // Feature: platform-ux-logistics-enhancements, Property 6: Customs status update round-trips for permitted values
    // Req 7.6: reading a shipment with no customs record returns a not-started state.
    @Property(tries = 200)
    void noCustomsRecordReadsAsNotStarted(@ForAll("shipmentIds") String shipmentId) {
        CustomsClearanceMapper mapper = mock(CustomsClearanceMapper.class);
        CustomsClearanceServiceImpl service = new CustomsClearanceServiceImpl(mapper);

        // No record exists for this shipment.
        when(mapper.selectOne(any())).thenReturn(null);

        CustomsClearanceVo vo = service.get(shipmentId);

        assertThat(vo.getClearanceStatus()).isEqualTo("not-started");
        assertThat(vo.getShipmentId()).isEqualTo(shipmentId);
    }

    // --- generators ----------------------------------------------------------

    @Provide
    Arbitrary<String> shipmentIds() {
        // Build a random UUID from two random longs so jqwik treats the
        // generator as randomized (and runs the full iteration count) rather
        // than as a single exhaustive value.
        return Combinators.combine(Arbitraries.longs(), Arbitraries.longs())
                .as((hi, lo) -> new UUID(hi, lo).toString());
    }

    @Provide
    Arbitrary<String> permittedStatuses() {
        return Arbitraries.of("not-started", "declared", "in-review", "cleared", "held");
    }

    @Provide
    Arbitrary<String> declarationRefs() {
        // Optional declaration reference; when present it must be 1–100 chars (Req 7.1).
        Arbitrary<String> present = Arbitraries.strings()
                .withCharRange('a', 'z')
                .withCharRange('0', '9')
                .ofMinLength(1)
                .ofMaxLength(100);
        return Arbitraries.oneOf(Arbitraries.just(null), present);
    }

    @Provide
    Arbitrary<BigDecimal> dutiesAmounts() {
        // Optional duties/taxes within the documented 0.00–999,999,999.99 bound (Req 7.1).
        Arbitrary<BigDecimal> present = Arbitraries
                .bigDecimals()
                .between(new BigDecimal("0.00"), new BigDecimal("999999999.99"))
                .ofScale(2);
        return Arbitraries.oneOf(Arbitraries.just(null), present);
    }
}
