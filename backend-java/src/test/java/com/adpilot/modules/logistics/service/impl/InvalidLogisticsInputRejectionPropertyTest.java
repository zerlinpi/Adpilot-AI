package com.adpilot.modules.logistics.service.impl;

import com.adpilot.common.exception.BusinessException;
import com.adpilot.common.security.DataScopeService;
import com.adpilot.modules.logistics.dto.CarrierDto;
import com.adpilot.modules.logistics.dto.CartonSpecDto;
import com.adpilot.modules.logistics.dto.CustomsClearanceDto;
import com.adpilot.modules.logistics.dto.FbaFieldsDto;
import com.adpilot.modules.logistics.dto.HandlingCostDto;
import com.adpilot.modules.logistics.dto.ShipmentExceptionDto;
import com.adpilot.modules.logistics.dto.ShipmentLegDto;
import com.adpilot.modules.logistics.dto.TrackingEventDto;
import com.adpilot.modules.logistics.entity.CarrierEntity;
import com.adpilot.modules.logistics.entity.ShipmentEntity;
import com.adpilot.modules.logistics.mapper.CarrierMapper;
import com.adpilot.modules.logistics.mapper.CartonSpecMapper;
import com.adpilot.modules.logistics.mapper.CustomsClearanceMapper;
import com.adpilot.modules.logistics.mapper.HandlingCostMapper;
import com.adpilot.modules.logistics.mapper.ShipmentExceptionMapper;
import com.adpilot.modules.logistics.mapper.ShipmentLineItemMapper;
import com.adpilot.modules.logistics.mapper.ShipmentLegMapper;
import com.adpilot.modules.logistics.mapper.ShipmentMapper;
import com.adpilot.modules.logistics.mapper.TrackingEventMapper;
import com.adpilot.modules.store.mapper.StoreMapper;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Property-based test for the rejection-without-side-effects behaviour of the
 * logistics service layer.
 *
 * <p>Feature: platform-ux-logistics-enhancements, Property 5: Invalid logistics
 * input is rejected without side effects.
 *
 * <p>For any submitted shipment leg, carton spec, carrier, customs clearance,
 * tracking event, shipment exception, FBA-fields set, or handling-cost line
 * that violates its field constraints (missing required field; value outside
 * the documented numeric/length bounds; arrival earlier than departure; leg
 * count &gt; 20; carton count &gt; 100; tracking count &gt; 1000; customs
 * status not in the permitted set; currency code not exactly 3 characters;
 * reference to a non-existent carrier), the backend rejects the request with a
 * {@link BusinessException}, persists no change, leaves prior records
 * unchanged, and returns an error identifying the invalid input.
 *
 * <p>All mapper dependencies are mocked. Because each service validates before
 * any write (validation precedes mapper {@code insert}/{@code updateById}), the
 * "no side effects" guarantee is proven by asserting the persisting methods are
 * {@code never()} invoked when an invalid payload is submitted. Existing
 * records (mocked shipments/carriers) are returned by {@code selectById} so the
 * services reach their validation gate rather than failing on a missing parent.
 *
 * <p>Each generator deliberately produces a payload that violates at least one
 * constraint, so a single property method exercises a whole violation family
 * across many randomized inputs.
 *
 * <b>Validates: Requirements 4.1, 4.5, 4.6, 4.7, 5.1, 5.5, 6.1, 6.2, 6.6, 7.1,
 * 7.3, 8.1, 8.2, 9.1, 9.3, 16.1, 16.2, 16.5, 18.1, 18.4</b>
 */
class InvalidLogisticsInputRejectionPropertyTest {

    private static final String VALID_CARRIER_ID = UUID.randomUUID().toString();

    // ===================================================================== //
    // Shipment legs (Req 4.1, 4.5, 4.6)                                      //
    // ===================================================================== //

    // Feature: platform-ux-logistics-enhancements, Property 5: Invalid logistics input is rejected without side effects
    // Req 4.5, 4.6: invalid leg fields (missing required field, out-of-bounds cost, arrival before departure) are rejected, nothing persisted.
    @Property(tries = 120)
    void invalidShipmentLegFieldsAreRejectedWithoutSideEffects(@ForAll("invalidLegs") ShipmentLegDto dto) {
        ShipmentLegMapper legMapper = mock(ShipmentLegMapper.class);
        ShipmentMapper shipmentMapper = mock(ShipmentMapper.class);
        CarrierMapper carrierMapper = mock(CarrierMapper.class);

        UUID shipmentId = UUID.randomUUID();
        when(shipmentMapper.selectById(any())).thenReturn(shipment(shipmentId));
        when(carrierMapper.selectById(any())).thenReturn(carrier());

        ShipmentLegServiceImpl service = new ShipmentLegServiceImpl(legMapper, shipmentMapper, carrierMapper);

        assertThatThrownBy(() -> service.upsertLeg(shipmentId.toString(), dto))
                .isInstanceOf(BusinessException.class);

        verify(legMapper, never()).insert(any());
        verify(legMapper, never()).updateById(any());
    }

    // Feature: platform-ux-logistics-enhancements, Property 5: Invalid logistics input is rejected without side effects
    // Req 6.6: a leg referencing a non-existent carrier is rejected, nothing persisted.
    @Property(tries = 120)
    void legReferencingNonExistentCarrierIsRejectedWithoutSideEffects(
            @ForAll("validLegs") ShipmentLegDto dto) {
        ShipmentLegMapper legMapper = mock(ShipmentLegMapper.class);
        ShipmentMapper shipmentMapper = mock(ShipmentMapper.class);
        CarrierMapper carrierMapper = mock(CarrierMapper.class);

        UUID shipmentId = UUID.randomUUID();
        when(shipmentMapper.selectById(any())).thenReturn(shipment(shipmentId));
        // The referenced carrier does not exist.
        when(carrierMapper.selectById(any())).thenReturn(null);

        ShipmentLegServiceImpl service = new ShipmentLegServiceImpl(legMapper, shipmentMapper, carrierMapper);

        assertThatThrownBy(() -> service.upsertLeg(shipmentId.toString(), dto))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Carrier not found");

        verify(legMapper, never()).insert(any());
        verify(legMapper, never()).updateById(any());
    }

    // Feature: platform-ux-logistics-enhancements, Property 5: Invalid logistics input is rejected without side effects
    // Req 4.7: adding a leg beyond the 20-leg ceiling is rejected, nothing persisted.
    @Property(tries = 100)
    void legCountBeyondLimitIsRejectedWithoutSideEffects(@ForAll("validLegs") ShipmentLegDto dto) {
        ShipmentLegMapper legMapper = mock(ShipmentLegMapper.class);
        ShipmentMapper shipmentMapper = mock(ShipmentMapper.class);
        CarrierMapper carrierMapper = mock(CarrierMapper.class);

        UUID shipmentId = UUID.randomUUID();
        when(shipmentMapper.selectById(any())).thenReturn(shipment(shipmentId));
        when(carrierMapper.selectById(any())).thenReturn(carrier());
        // No existing leg at this sequence -> insert path; shipment already at the ceiling.
        when(legMapper.selectOne(any())).thenReturn(null);
        when(legMapper.selectCount(any())).thenReturn((long) ShipmentLegServiceImpl.MAX_LEGS_PER_SHIPMENT);

        ShipmentLegServiceImpl service = new ShipmentLegServiceImpl(legMapper, shipmentMapper, carrierMapper);

        assertThatThrownBy(() -> service.upsertLeg(shipmentId.toString(), dto))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("at most");

        verify(legMapper, never()).insert(any());
        verify(legMapper, never()).updateById(any());
    }

    // ===================================================================== //
    // Carton specs (Req 5.1, 5.5)                                            //
    // ===================================================================== //

    // Feature: platform-ux-logistics-enhancements, Property 5: Invalid logistics input is rejected without side effects
    // Req 5.5: invalid carton-spec fields (missing/out-of-bounds dimensions, weight, counts) are rejected, nothing persisted.
    @Property(tries = 120)
    void invalidCartonSpecFieldsAreRejectedWithoutSideEffects(@ForAll("invalidCartonSpecs") CartonSpecDto dto) {
        CartonSpecMapper mapper = mock(CartonSpecMapper.class);
        CartonSpecServiceImpl service = new CartonSpecServiceImpl(mapper);

        assertThatThrownBy(() -> service.addSpec(UUID.randomUUID().toString(), dto))
                .isInstanceOf(BusinessException.class);

        verify(mapper, never()).insert(any());
    }

    // Feature: platform-ux-logistics-enhancements, Property 5: Invalid logistics input is rejected without side effects
    // Req 5.1: adding a carton spec beyond the 100-spec ceiling is rejected, nothing persisted.
    @Property(tries = 100)
    void cartonSpecCountBeyondLimitIsRejectedWithoutSideEffects(@ForAll("validCartonSpecs") CartonSpecDto dto) {
        CartonSpecMapper mapper = mock(CartonSpecMapper.class);
        // Shipment already holds the maximum number of carton specs.
        when(mapper.selectCount(any())).thenReturn(100L);
        CartonSpecServiceImpl service = new CartonSpecServiceImpl(mapper);

        assertThatThrownBy(() -> service.addSpec(UUID.randomUUID().toString(), dto))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("at most");

        verify(mapper, never()).insert(any());
    }

    // ===================================================================== //
    // Carriers (Req 6.1, 6.2)                                                //
    // ===================================================================== //

    // Feature: platform-ux-logistics-enhancements, Property 5: Invalid logistics input is rejected without side effects
    // Req 6.2: invalid carrier fields (blank/over-length name or service type) are rejected, nothing persisted.
    @Property(tries = 120)
    void invalidCarrierFieldsAreRejectedWithoutSideEffects(@ForAll("invalidCarriers") CarrierDto dto) {
        CarrierMapper mapper = mock(CarrierMapper.class);
        CarrierServiceImpl service = new CarrierServiceImpl(mapper);

        assertThatThrownBy(() -> service.create(dto))
                .isInstanceOf(BusinessException.class);

        verify(mapper, never()).insert(any());
    }

    // ===================================================================== //
    // Customs clearance (Req 7.1, 7.3)                                       //
    // ===================================================================== //

    // Feature: platform-ux-logistics-enhancements, Property 5: Invalid logistics input is rejected without side effects
    // Req 7.3: an invalid customs status / out-of-bounds field is rejected, nothing persisted.
    @Property(tries = 120)
    void invalidCustomsClearanceIsRejectedWithoutSideEffects(@ForAll("invalidCustoms") CustomsClearanceDto dto) {
        CustomsClearanceMapper mapper = mock(CustomsClearanceMapper.class);
        // A rejected update must leave any previously-persisted record unchanged.
        when(mapper.selectOne(any())).thenReturn(null);
        CustomsClearanceServiceImpl service = new CustomsClearanceServiceImpl(mapper);

        assertThatThrownBy(() -> service.update(UUID.randomUUID().toString(), dto))
                .isInstanceOf(BusinessException.class);

        verify(mapper, never()).insert(any());
        verify(mapper, never()).updateById(any());
    }

    // ===================================================================== //
    // Tracking events (Req 8.1, 8.2)                                         //
    // ===================================================================== //

    // Feature: platform-ux-logistics-enhancements, Property 5: Invalid logistics input is rejected without side effects
    // Req 8.2: invalid tracking-event fields (missing timestamp, blank/over-length description) are rejected, nothing persisted.
    @Property(tries = 120)
    void invalidTrackingEventFieldsAreRejectedWithoutSideEffects(@ForAll("invalidTrackingEvents") TrackingEventDto dto) {
        TrackingEventMapper trackingMapper = mock(TrackingEventMapper.class);
        ShipmentMapper shipmentMapper = mock(ShipmentMapper.class);

        UUID shipmentId = UUID.randomUUID();
        when(shipmentMapper.selectById(any())).thenReturn(shipment(shipmentId));

        TrackingEventServiceImpl service = new TrackingEventServiceImpl(trackingMapper, shipmentMapper);

        assertThatThrownBy(() -> service.add(shipmentId.toString(), dto))
                .isInstanceOf(BusinessException.class);

        verify(trackingMapper, never()).insert(any());
    }

    // Feature: platform-ux-logistics-enhancements, Property 5: Invalid logistics input is rejected without side effects
    // Req 8.1: adding a tracking event beyond the 1,000-event ceiling is rejected, nothing persisted.
    @Property(tries = 100)
    void trackingEventCountBeyondLimitIsRejectedWithoutSideEffects(@ForAll("validTrackingEvents") TrackingEventDto dto) {
        TrackingEventMapper trackingMapper = mock(TrackingEventMapper.class);
        ShipmentMapper shipmentMapper = mock(ShipmentMapper.class);

        UUID shipmentId = UUID.randomUUID();
        when(shipmentMapper.selectById(any())).thenReturn(shipment(shipmentId));
        // Shipment already holds the maximum number of tracking events.
        when(trackingMapper.selectCount(any())).thenReturn(1000L);

        TrackingEventServiceImpl service = new TrackingEventServiceImpl(trackingMapper, shipmentMapper);

        assertThatThrownBy(() -> service.add(shipmentId.toString(), dto))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("maximum");

        verify(trackingMapper, never()).insert(any());
    }

    // ===================================================================== //
    // Shipment exceptions (Req 9.1, 9.3)                                     //
    // ===================================================================== //

    // Feature: platform-ux-logistics-enhancements, Property 5: Invalid logistics input is rejected without side effects
    // Req 9.3: invalid shipment-exception fields (bad type, blank/over-length description) are rejected, nothing persisted.
    @Property(tries = 120)
    void invalidShipmentExceptionFieldsAreRejectedWithoutSideEffects(
            @ForAll("invalidExceptions") ShipmentExceptionDto dto) {
        ShipmentExceptionMapper exceptionMapper = mock(ShipmentExceptionMapper.class);
        ShipmentMapper shipmentMapper = mock(ShipmentMapper.class);
        StoreMapper storeMapper = mock(StoreMapper.class);
        DataScopeService dataScopeService = mock(DataScopeService.class);

        UUID shipmentId = UUID.randomUUID();
        when(shipmentMapper.selectById(any())).thenReturn(shipment(shipmentId));

        ShipmentExceptionServiceImpl service =
                new ShipmentExceptionServiceImpl(exceptionMapper, shipmentMapper, storeMapper, dataScopeService);

        assertThatThrownBy(() -> service.raise(shipmentId.toString(), dto))
                .isInstanceOf(BusinessException.class);

        verify(exceptionMapper, never()).insert(any());
    }

    // ===================================================================== //
    // FBA fields (Req 16.1, 16.2, 16.5)                                      //
    // ===================================================================== //

    // Feature: platform-ux-logistics-enhancements, Property 5: Invalid logistics input is rejected without side effects
    // Req 16.5: invalid FBA fields / line items (over-length ids, empty values, out-of-bounds quantity) are rejected, nothing persisted.
    @Property(tries = 120)
    void invalidFbaFieldsAreRejectedWithoutSideEffects(@ForAll("invalidFbaFields") FbaFieldsDto dto) {
        ShipmentMapper shipmentMapper = mock(ShipmentMapper.class);
        ShipmentLineItemMapper lineItemMapper = mock(ShipmentLineItemMapper.class);

        UUID shipmentId = UUID.randomUUID();
        when(shipmentMapper.selectById(any())).thenReturn(shipment(shipmentId));

        FbaShipmentServiceImpl service = new FbaShipmentServiceImpl(shipmentMapper, lineItemMapper);

        assertThatThrownBy(() -> service.saveFields(shipmentId.toString(), dto))
                .isInstanceOf(BusinessException.class);

        // No mutation of the shipment row nor of its line items.
        verify(shipmentMapper, never()).updateById(any());
        verify(lineItemMapper, never()).insert(any());
        verify(lineItemMapper, never()).delete(any());
    }

    // ===================================================================== //
    // Handling costs (Req 18.1, 18.4)                                        //
    // ===================================================================== //

    // Feature: platform-ux-logistics-enhancements, Property 5: Invalid logistics input is rejected without side effects
    // Req 18.4: invalid handling-cost fields (out-of-bounds amount, currency not exactly 3 chars, blank/over-length description) are rejected, nothing persisted.
    @Property(tries = 120)
    void invalidHandlingCostFieldsAreRejectedWithoutSideEffects(@ForAll("invalidHandlingCosts") HandlingCostDto dto) {
        HandlingCostMapper handlingCostMapper = mock(HandlingCostMapper.class);
        ShipmentMapper shipmentMapper = mock(ShipmentMapper.class);

        UUID shipmentId = UUID.randomUUID();
        when(shipmentMapper.selectById(any())).thenReturn(shipment(shipmentId));

        HandlingCostServiceImpl service = new HandlingCostServiceImpl(handlingCostMapper, shipmentMapper);

        assertThatThrownBy(() -> service.upsert(shipmentId.toString(), null, dto))
                .isInstanceOf(BusinessException.class);

        verify(handlingCostMapper, never()).insert(any());
        verify(handlingCostMapper, never()).updateById(any());
    }

    // ===================================================================== //
    // Fixtures                                                              //
    // ===================================================================== //

    private static ShipmentEntity shipment(UUID id) {
        return ShipmentEntity.builder()
                .id(id)
                .storeId(UUID.randomUUID())
                .build();
    }

    private static CarrierEntity carrier() {
        return CarrierEntity.builder()
                .id(UUID.fromString(VALID_CARRIER_ID))
                .orgId(UUID.randomUUID())
                .name("DHL Express")
                .serviceType("Air Freight")
                .build();
    }

    // ===================================================================== //
    // Generators                                                            //
    // ===================================================================== //

    /** A salt to keep each generator's value space large enough to run the full try count. */
    private Arbitrary<Integer> salt() {
        return Arbitraries.integers().between(0, 1_000_000);
    }

    // --- shipment legs --------------------------------------------------------

    private static ShipmentLegDto validLegBase() {
        ShipmentLegDto dto = new ShipmentLegDto();
        dto.setLegType("first_leg");
        dto.setSequenceNo(1);
        dto.setCarrierId(VALID_CARRIER_ID);
        dto.setDepartureDate(LocalDate.of(2024, 1, 1));
        dto.setArrivalDate(LocalDate.of(2024, 1, 10));
        dto.setLegCost(new BigDecimal("100.00"));
        return dto;
    }

    @Provide
    Arbitrary<ShipmentLegDto> validLegs() {
        return Combinators.combine(salt(), Arbitraries.integers().between(1, 20))
                .as((s, seq) -> {
                    ShipmentLegDto dto = validLegBase();
                    dto.setSequenceNo(seq);
                    return dto;
                });
    }

    @Provide
    Arbitrary<ShipmentLegDto> invalidLegs() {
        Arbitrary<String> kind = Arbitraries.of(
                "blankType", "nullSeq", "blankCarrier", "nullDeparture",
                "nullArrival", "nullCost", "negativeCost", "hugeCost", "arrivalBeforeDeparture");
        return Combinators.combine(kind, salt()).as((k, s) -> {
            ShipmentLegDto dto = validLegBase();
            switch (k) {
                case "blankType" -> dto.setLegType("   ");
                case "nullSeq" -> dto.setSequenceNo(null);
                case "blankCarrier" -> dto.setCarrierId(null);
                case "nullDeparture" -> dto.setDepartureDate(null);
                case "nullArrival" -> dto.setArrivalDate(null);
                case "nullCost" -> dto.setLegCost(null);
                case "negativeCost" -> dto.setLegCost(new BigDecimal("-0.01"));
                case "hugeCost" -> dto.setLegCost(new BigDecimal("1000000000.00"));
                case "arrivalBeforeDeparture" -> {
                    dto.setDepartureDate(LocalDate.of(2024, 6, 1));
                    dto.setArrivalDate(LocalDate.of(2024, 5, 1));
                }
                default -> throw new IllegalStateException("unknown kind: " + k);
            }
            return dto;
        });
    }

    // --- carton specs ---------------------------------------------------------

    private static CartonSpecDto validCartonBase() {
        CartonSpecDto dto = new CartonSpecDto();
        dto.setBoxLengthCm(new BigDecimal("50.0"));
        dto.setBoxWidthCm(new BigDecimal("40.0"));
        dto.setBoxHeightCm(new BigDecimal("30.0"));
        dto.setBoxWeightKg(new BigDecimal("12.50"));
        dto.setUnitsPerBox(24);
        dto.setBoxCount(10);
        return dto;
    }

    @Provide
    Arbitrary<CartonSpecDto> validCartonSpecs() {
        return Combinators.combine(
                        Arbitraries.integers().between(1, 1_000_000),
                        Arbitraries.integers().between(1, 1_000_000))
                .as((units, boxes) -> {
                    CartonSpecDto dto = validCartonBase();
                    dto.setUnitsPerBox(units);
                    dto.setBoxCount(boxes);
                    return dto;
                });
    }

    @Provide
    Arbitrary<CartonSpecDto> invalidCartonSpecs() {
        Arbitrary<String> kind = Arbitraries.of(
                "nullLength", "lengthTooSmall", "lengthTooLarge",
                "nullWidth", "nullHeight",
                "nullWeight", "weightTooSmall", "weightTooLarge",
                "nullUnits", "unitsZero", "unitsTooLarge",
                "nullBoxCount", "boxCountZero", "boxCountTooLarge");
        return Combinators.combine(kind, salt()).as((k, s) -> {
            CartonSpecDto dto = validCartonBase();
            switch (k) {
                case "nullLength" -> dto.setBoxLengthCm(null);
                case "lengthTooSmall" -> dto.setBoxLengthCm(new BigDecimal("0.05"));
                case "lengthTooLarge" -> dto.setBoxLengthCm(new BigDecimal("1000.1"));
                case "nullWidth" -> dto.setBoxWidthCm(null);
                case "nullHeight" -> dto.setBoxHeightCm(null);
                case "nullWeight" -> dto.setBoxWeightKg(null);
                case "weightTooSmall" -> dto.setBoxWeightKg(new BigDecimal("0.001"));
                case "weightTooLarge" -> dto.setBoxWeightKg(new BigDecimal("10000.01"));
                case "nullUnits" -> dto.setUnitsPerBox(null);
                case "unitsZero" -> dto.setUnitsPerBox(0);
                case "unitsTooLarge" -> dto.setUnitsPerBox(1_000_001);
                case "nullBoxCount" -> dto.setBoxCount(null);
                case "boxCountZero" -> dto.setBoxCount(0);
                case "boxCountTooLarge" -> dto.setBoxCount(1_000_001);
                default -> throw new IllegalStateException("unknown kind: " + k);
            }
            return dto;
        });
    }

    // --- carriers -------------------------------------------------------------

    @Provide
    Arbitrary<CarrierDto> invalidCarriers() {
        Arbitrary<String> kind = Arbitraries.of(
                "blankName", "nameTooLong", "nullServiceType", "serviceTypeTooLong", "bothBlank");
        return Combinators.combine(kind, salt()).as((k, s) -> {
            CarrierDto dto = new CarrierDto();
            dto.setName("DHL Express");
            dto.setServiceType("Air Freight");
            switch (k) {
                case "blankName" -> dto.setName("   ");
                case "nameTooLong" -> dto.setName("a".repeat(201));
                case "nullServiceType" -> dto.setServiceType(null);
                case "serviceTypeTooLong" -> dto.setServiceType("s".repeat(101));
                case "bothBlank" -> {
                    dto.setName(null);
                    dto.setServiceType("  ");
                }
                default -> throw new IllegalStateException("unknown kind: " + k);
            }
            return dto;
        });
    }

    // --- customs clearance ----------------------------------------------------

    @Provide
    Arbitrary<CustomsClearanceDto> invalidCustoms() {
        Arbitrary<String> kind = Arbitraries.of(
                "nullStatus", "blankStatus", "unknownStatus",
                "declarationRefTooLong", "dutiesNegative", "dutiesTooLarge");
        // A random non-permitted status token for the "unknownStatus" case.
        Arbitrary<String> badStatus = Arbitraries.strings()
                .withCharRange('a', 'z').ofMinLength(1).ofMaxLength(20)
                .filter(v -> !List.of("not-started", "declared", "in-review", "cleared", "held").contains(v));
        return Combinators.combine(kind, badStatus).as((k, bad) -> {
            CustomsClearanceDto dto = new CustomsClearanceDto();
            dto.setClearanceStatus("declared");
            dto.setDeclarationRef("REF-123");
            dto.setDutiesTaxes(new BigDecimal("100.00"));
            switch (k) {
                case "nullStatus" -> dto.setClearanceStatus(null);
                case "blankStatus" -> dto.setClearanceStatus("  ");
                case "unknownStatus" -> dto.setClearanceStatus(bad);
                case "declarationRefTooLong" -> dto.setDeclarationRef("r".repeat(101));
                case "dutiesNegative" -> dto.setDutiesTaxes(new BigDecimal("-0.01"));
                case "dutiesTooLarge" -> dto.setDutiesTaxes(new BigDecimal("1000000000.00"));
                default -> throw new IllegalStateException("unknown kind: " + k);
            }
            return dto;
        });
    }

    // --- tracking events ------------------------------------------------------

    private static TrackingEventDto validTrackingBase() {
        TrackingEventDto dto = new TrackingEventDto();
        dto.setEventTime(LocalDateTime.of(2024, 1, 1, 12, 0));
        dto.setDescription("Departed origin facility");
        dto.setLegId(null);
        return dto;
    }

    @Provide
    Arbitrary<TrackingEventDto> validTrackingEvents() {
        return salt().map(s -> validTrackingBase());
    }

    @Provide
    Arbitrary<TrackingEventDto> invalidTrackingEvents() {
        Arbitrary<String> kind = Arbitraries.of(
                "nullEventTime", "nullDescription", "blankDescription", "descriptionTooLong", "badLegId");
        return Combinators.combine(kind, salt()).as((k, s) -> {
            TrackingEventDto dto = validTrackingBase();
            switch (k) {
                case "nullEventTime" -> dto.setEventTime(null);
                case "nullDescription" -> dto.setDescription(null);
                case "blankDescription" -> dto.setDescription("   ");
                case "descriptionTooLong" -> dto.setDescription("d".repeat(501));
                case "badLegId" -> dto.setLegId("not-a-uuid");
                default -> throw new IllegalStateException("unknown kind: " + k);
            }
            return dto;
        });
    }

    // --- shipment exceptions --------------------------------------------------

    @Provide
    Arbitrary<ShipmentExceptionDto> invalidExceptions() {
        Arbitrary<String> kind = Arbitraries.of(
                "nullType", "blankType", "unknownType", "nullDescription", "blankDescription", "descriptionTooLong");
        Arbitrary<String> badType = Arbitraries.strings()
                .withCharRange('a', 'z').ofMinLength(1).ofMaxLength(20)
                .filter(v -> !List.of("delay", "damage", "customs-hold").contains(v));
        return Combinators.combine(kind, badType).as((k, bad) -> {
            ShipmentExceptionDto dto = new ShipmentExceptionDto();
            dto.setExceptionType("delay");
            dto.setDescription("Shipment delayed at customs");
            switch (k) {
                case "nullType" -> dto.setExceptionType(null);
                case "blankType" -> dto.setExceptionType("   ");
                case "unknownType" -> dto.setExceptionType(bad);
                case "nullDescription" -> dto.setDescription(null);
                case "blankDescription" -> dto.setDescription("   ");
                case "descriptionTooLong" -> dto.setDescription("d".repeat(1001));
                default -> throw new IllegalStateException("unknown kind: " + k);
            }
            return dto;
        });
    }

    // --- FBA fields -----------------------------------------------------------

    @Provide
    Arbitrary<FbaFieldsDto> invalidFbaFields() {
        Arbitrary<String> kind = Arbitraries.of(
                "fbaIdTooLong", "fbaIdEmpty", "statusTooLong", "fcCodeTooLong", "fcCodeEmpty",
                "lineItemSkuMissing", "lineItemSkuTooLong", "lineItemAsinTooLong",
                "lineItemQuantityNull", "lineItemQuantityZero", "lineItemQuantityTooLarge");
        return Combinators.combine(kind, salt()).as((k, s) -> {
            FbaFieldsDto dto = new FbaFieldsDto();
            dto.setFbaShipmentId("FBA12345");
            dto.setAmazonShipmentStatus("WORKING");
            dto.setDestinationFcCode("LAX9");
            FbaFieldsDto.FbaLineItemDto item = new FbaFieldsDto.FbaLineItemDto();
            item.setSku("SKU-1");
            item.setMsku("MSKU-1");
            item.setAsin("B0123456789");
            item.setQuantity(5);
            switch (k) {
                case "fbaIdTooLong" -> dto.setFbaShipmentId("F".repeat(101));
                case "fbaIdEmpty" -> dto.setFbaShipmentId("");
                case "statusTooLong" -> dto.setAmazonShipmentStatus("S".repeat(51));
                case "fcCodeTooLong" -> dto.setDestinationFcCode("C".repeat(51));
                case "fcCodeEmpty" -> dto.setDestinationFcCode("");
                case "lineItemSkuMissing" -> item.setSku(null);
                case "lineItemSkuTooLong" -> item.setSku("S".repeat(101));
                case "lineItemAsinTooLong" -> item.setAsin("A".repeat(21));
                case "lineItemQuantityNull" -> item.setQuantity(null);
                case "lineItemQuantityZero" -> item.setQuantity(0);
                case "lineItemQuantityTooLarge" -> item.setQuantity(1_000_001);
                default -> throw new IllegalStateException("unknown kind: " + k);
            }
            List<FbaFieldsDto.FbaLineItemDto> items = new ArrayList<>();
            items.add(item);
            dto.setLineItems(items);
            return dto;
        });
    }

    // --- handling costs -------------------------------------------------------

    @Provide
    Arbitrary<HandlingCostDto> invalidHandlingCosts() {
        Arbitrary<String> kind = Arbitraries.of(
                "nullAmount", "negativeAmount", "hugeAmount",
                "nullCurrency", "blankCurrency", "currencyWrongLength",
                "nullDescription", "blankDescription", "descriptionTooLong");
        // Currency codes whose length is not exactly 3 for the "currencyWrongLength" case.
        Arbitrary<String> wrongLenCurrency = Arbitraries.strings()
                .withCharRange('A', 'Z')
                .ofMinLength(1).ofMaxLength(6)
                .filter(v -> v.length() != 3);
        return Combinators.combine(kind, wrongLenCurrency).as((k, badCurrency) -> {
            HandlingCostDto dto = new HandlingCostDto();
            dto.setAmount(new BigDecimal("100.00"));
            dto.setCurrencyCode("USD");
            dto.setDescription("Freight handling");
            switch (k) {
                case "nullAmount" -> dto.setAmount(null);
                case "negativeAmount" -> dto.setAmount(new BigDecimal("-0.01"));
                case "hugeAmount" -> dto.setAmount(new BigDecimal("1000000000.00"));
                case "nullCurrency" -> dto.setCurrencyCode(null);
                case "blankCurrency" -> dto.setCurrencyCode("   ");
                case "currencyWrongLength" -> dto.setCurrencyCode(badCurrency);
                case "nullDescription" -> dto.setDescription(null);
                case "blankDescription" -> dto.setDescription("   ");
                case "descriptionTooLong" -> dto.setDescription("d".repeat(201));
                default -> throw new IllegalStateException("unknown kind: " + k);
            }
            return dto;
        });
    }
}
