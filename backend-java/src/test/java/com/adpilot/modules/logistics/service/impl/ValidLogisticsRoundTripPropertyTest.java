package com.adpilot.modules.logistics.service.impl;

import com.adpilot.modules.logistics.dto.CartonSpecDto;
import com.adpilot.modules.logistics.dto.FbaFieldsDto;
import com.adpilot.modules.logistics.dto.HandlingCostDto;
import com.adpilot.modules.logistics.dto.ShipmentExceptionDto;
import com.adpilot.modules.logistics.dto.ShipmentLegDto;
import com.adpilot.modules.logistics.entity.CarrierEntity;
import com.adpilot.modules.logistics.entity.CartonSpecEntity;
import com.adpilot.modules.logistics.entity.HandlingCostEntity;
import com.adpilot.modules.logistics.entity.ShipmentEntity;
import com.adpilot.modules.logistics.entity.ShipmentExceptionEntity;
import com.adpilot.modules.logistics.entity.ShipmentLegEntity;
import com.adpilot.modules.logistics.entity.ShipmentLineItemEntity;
import com.adpilot.modules.logistics.mapper.CarrierMapper;
import com.adpilot.modules.logistics.mapper.CartonSpecMapper;
import com.adpilot.modules.logistics.mapper.HandlingCostMapper;
import com.adpilot.modules.logistics.mapper.ShipmentExceptionMapper;
import com.adpilot.modules.logistics.mapper.ShipmentLineItemMapper;
import com.adpilot.modules.logistics.mapper.ShipmentLegMapper;
import com.adpilot.modules.logistics.mapper.ShipmentMapper;
import com.adpilot.modules.logistics.vo.CartonSpecVo;
import com.adpilot.modules.logistics.vo.FbaFieldsVo;
import com.adpilot.modules.logistics.vo.HandlingCostVo;
import com.adpilot.modules.logistics.vo.ShipmentExceptionVo;
import com.adpilot.modules.logistics.vo.ShipmentLegVo;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Property-based test for the round-trip persistence behaviour of the logistics
 * service layer.
 *
 * <p>Feature: platform-ux-logistics-enhancements, Property 4: Valid logistics
 * records round-trip through persistence.
 *
 * <p>For any valid shipment leg, carton spec, FBA-fields set, or handling-cost
 * line, saving it and then reading it back returns a record whose field values
 * equal those submitted (with a generated identifier assigned), and a newly
 * created shipment exception is returned in the open state.
 *
 * <p>All mapper dependencies are mocked. The MyBatis-Plus UUID-insert
 * interceptor that assigns a generated identifier at persistence time is not
 * present in a unit test, so the {@code insert} stubs assign a random
 * {@link UUID} to the inserted entity exactly as the interceptor would; the
 * {@code selectById} stubs then return that same entity so the services'
 * read-back path is exercised. This proves the submitted field values survive
 * the save → read-back cycle unchanged and that a generated identifier is
 * present on the returned record.
 *
 * <b>Validates: Requirements 4.3, 5.2, 6.3, 9.2, 16.3, 18.2, 18.3</b>
 */
class ValidLogisticsRoundTripPropertyTest {

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    // ===================================================================== //
    // Shipment legs (Req 4.3, 6.3)                                           //
    // ===================================================================== //

    // Feature: platform-ux-logistics-enhancements, Property 4: Valid logistics records round-trip through persistence
    // Req 4.3, 6.3: a valid leg is persisted with its assigned carrier and returned with every submitted field value and a generated id.
    @Property(tries = 200)
    void validShipmentLegRoundTrips(@ForAll("validLegs") ShipmentLegDto dto) {
        ShipmentLegMapper legMapper = mock(ShipmentLegMapper.class);
        ShipmentMapper shipmentMapper = mock(ShipmentMapper.class);
        CarrierMapper carrierMapper = mock(CarrierMapper.class);

        UUID shipmentId = UUID.randomUUID();
        when(shipmentMapper.selectById(any())).thenReturn(shipment(shipmentId));

        UUID carrierUuid = UUID.fromString(dto.getCarrierId());
        CarrierEntity carrier = CarrierEntity.builder()
                .id(carrierUuid)
                .orgId(UUID.randomUUID())
                .name("DHL Express")
                .serviceType("Air Freight")
                .build();
        when(carrierMapper.selectById(any())).thenReturn(carrier);

        // No existing leg at this sequence (insert path) and shipment under the leg ceiling.
        when(legMapper.selectOne(any())).thenReturn(null);
        when(legMapper.selectCount(any())).thenReturn(0L);
        // The UUID-insert interceptor assigns a generated id at persistence time.
        when(legMapper.insert(any())).thenAnswer(inv -> {
            ShipmentLegEntity e = inv.getArgument(0);
            e.setId(UUID.randomUUID());
            return 1;
        });

        ShipmentLegServiceImpl service = new ShipmentLegServiceImpl(legMapper, shipmentMapper, carrierMapper);

        ShipmentLegVo vo = service.upsertLeg(shipmentId.toString(), dto);

        assertThat(vo.getId()).isNotNull();
        assertThat(vo.getShipmentId()).isEqualTo(shipmentId.toString());
        assertThat(vo.getLegType()).isEqualTo(dto.getLegType());
        assertThat(vo.getSequenceNo()).isEqualTo(dto.getSequenceNo());
        assertThat(vo.getCarrierId()).isEqualTo(dto.getCarrierId());
        assertThat(vo.getCarrierName()).isEqualTo(carrier.getName());
        assertThat(vo.getDepartureDate()).isEqualTo(dto.getDepartureDate().format(DATE_FORMATTER));
        assertThat(vo.getArrivalDate()).isEqualTo(dto.getArrivalDate().format(DATE_FORMATTER));
        assertThat(vo.getLegCost()).isEqualByComparingTo(dto.getLegCost());
    }

    // ===================================================================== //
    // Carton specs (Req 5.2)                                                 //
    // ===================================================================== //

    // Feature: platform-ux-logistics-enhancements, Property 4: Valid logistics records round-trip through persistence
    // Req 5.2: a valid carton spec is persisted and returned with its generated id and every submitted field value.
    @Property(tries = 200)
    void validCartonSpecRoundTrips(@ForAll("validCartonSpecs") CartonSpecDto dto) {
        CartonSpecMapper mapper = mock(CartonSpecMapper.class);
        Map<UUID, CartonSpecEntity> store = new HashMap<>();

        when(mapper.selectCount(any())).thenReturn(0L);
        when(mapper.insert(any())).thenAnswer(inv -> {
            CartonSpecEntity e = inv.getArgument(0);
            e.setId(UUID.randomUUID());
            store.put(e.getId(), e);
            return 1;
        });
        when(mapper.selectById(any())).thenAnswer(inv -> store.get(inv.getArgument(0)));

        CartonSpecServiceImpl service = new CartonSpecServiceImpl(mapper);

        String shipmentId = UUID.randomUUID().toString();
        CartonSpecVo vo = service.addSpec(shipmentId, dto);

        assertThat(vo.getId()).isNotNull();
        assertThat(vo.getShipmentId()).isEqualTo(shipmentId);
        assertThat(vo.getBoxLengthCm()).isEqualByComparingTo(dto.getBoxLengthCm());
        assertThat(vo.getBoxWidthCm()).isEqualByComparingTo(dto.getBoxWidthCm());
        assertThat(vo.getBoxHeightCm()).isEqualByComparingTo(dto.getBoxHeightCm());
        assertThat(vo.getBoxWeightKg()).isEqualByComparingTo(dto.getBoxWeightKg());
        assertThat(vo.getUnitsPerBox()).isEqualTo(dto.getUnitsPerBox());
        assertThat(vo.getBoxCount()).isEqualTo(dto.getBoxCount());
    }

    // ===================================================================== //
    // FBA fields (Req 16.3)                                                  //
    // ===================================================================== //

    // Feature: platform-ux-logistics-enhancements, Property 4: Valid logistics records round-trip through persistence
    // Req 16.3: a valid FBA-fields set (with line items) is persisted and read back with every submitted value and generated line-item ids.
    @Property(tries = 200)
    void validFbaFieldsRoundTrip(@ForAll("validFbaFields") FbaFieldsDto dto) {
        ShipmentMapper shipmentMapper = mock(ShipmentMapper.class);
        ShipmentLineItemMapper lineItemMapper = mock(ShipmentLineItemMapper.class);

        UUID shipmentId = UUID.randomUUID();
        ShipmentEntity shipment = shipment(shipmentId);
        when(shipmentMapper.selectById(any())).thenReturn(shipment);

        List<ShipmentLineItemEntity> inserted = new ArrayList<>();
        when(lineItemMapper.insert(any())).thenAnswer(inv -> {
            ShipmentLineItemEntity e = inv.getArgument(0);
            e.setId(UUID.randomUUID());
            inserted.add(e);
            return 1;
        });
        // The read-back loads the line items previously inserted, in insertion order.
        when(lineItemMapper.selectList(any())).thenReturn(inserted);

        FbaShipmentServiceImpl service = new FbaShipmentServiceImpl(shipmentMapper, lineItemMapper);

        FbaFieldsVo vo = service.saveFields(shipmentId.toString(), dto);

        assertThat(vo.getShipmentId()).isEqualTo(shipmentId.toString());
        assertThat(vo.getFbaShipmentId()).isEqualTo(dto.getFbaShipmentId());
        assertThat(vo.getAmazonShipmentStatus()).isEqualTo(dto.getAmazonShipmentStatus());
        assertThat(vo.getDestinationFcCode()).isEqualTo(dto.getDestinationFcCode());

        List<FbaFieldsDto.FbaLineItemDto> submitted = dto.getLineItems();
        assertThat(vo.getLineItems()).hasSameSizeAs(submitted);
        for (int i = 0; i < submitted.size(); i++) {
            FbaFieldsDto.FbaLineItemDto in = submitted.get(i);
            FbaFieldsVo.FbaLineItemVo out = vo.getLineItems().get(i);
            assertThat(out.getId()).isNotNull();
            assertThat(out.getShipmentId()).isEqualTo(shipmentId.toString());
            assertThat(out.getSku()).isEqualTo(in.getSku());
            assertThat(out.getMsku()).isEqualTo(in.getMsku());
            assertThat(out.getAsin()).isEqualTo(in.getAsin());
            assertThat(out.getQuantity()).isEqualTo(in.getQuantity());
        }
    }

    // ===================================================================== //
    // Handling costs (Req 18.2, 18.3)                                        //
    // ===================================================================== //

    // Feature: platform-ux-logistics-enhancements, Property 4: Valid logistics records round-trip through persistence
    // Req 18.2, 18.3: a valid handling-cost line is persisted and returned with its generated id, amount, currency, and description.
    @Property(tries = 200)
    void validHandlingCostRoundTrips(@ForAll("validHandlingCosts") HandlingCostDto dto) {
        HandlingCostMapper handlingCostMapper = mock(HandlingCostMapper.class);
        ShipmentMapper shipmentMapper = mock(ShipmentMapper.class);

        UUID shipmentId = UUID.randomUUID();
        when(shipmentMapper.selectById(any())).thenReturn(shipment(shipmentId));

        Map<UUID, HandlingCostEntity> store = new HashMap<>();
        when(handlingCostMapper.insert(any())).thenAnswer(inv -> {
            HandlingCostEntity e = inv.getArgument(0);
            e.setId(UUID.randomUUID());
            store.put(e.getId(), e);
            return 1;
        });
        when(handlingCostMapper.selectById(any())).thenAnswer(inv -> store.get(inv.getArgument(0)));

        HandlingCostServiceImpl service = new HandlingCostServiceImpl(handlingCostMapper, shipmentMapper);

        HandlingCostVo vo = service.upsert(shipmentId.toString(), null, dto);

        assertThat(vo.getId()).isNotNull();
        assertThat(vo.getShipmentId()).isEqualTo(shipmentId.toString());
        assertThat(vo.getAmount()).isEqualByComparingTo(dto.getAmount());
        assertThat(vo.getCurrencyCode()).isEqualTo(dto.getCurrencyCode());
        assertThat(vo.getDescription()).isEqualTo(dto.getDescription());
        if (dto.getExchangeRate() != null) {
            assertThat(vo.getExchangeRate()).isEqualByComparingTo(dto.getExchangeRate());
        }
        if (dto.getCostDate() != null) {
            assertThat(vo.getCostDate()).isEqualTo(dto.getCostDate().format(DATE_FORMATTER));
        }
    }

    // ===================================================================== //
    // Shipment exceptions (Req 9.2)                                          //
    // ===================================================================== //

    // Feature: platform-ux-logistics-enhancements, Property 4: Valid logistics records round-trip through persistence
    // Req 9.2: a newly raised exception is persisted and returned in the open state with a generated id and the submitted fields.
    @Property(tries = 200)
    void newShipmentExceptionRoundTripsInOpenState(@ForAll("validExceptions") ShipmentExceptionDto dto) {
        ShipmentExceptionMapper exceptionMapper = mock(ShipmentExceptionMapper.class);
        ShipmentMapper shipmentMapper = mock(ShipmentMapper.class);

        UUID shipmentId = UUID.randomUUID();
        when(shipmentMapper.selectById(any())).thenReturn(shipment(shipmentId));

        Map<UUID, ShipmentExceptionEntity> store = new HashMap<>();
        when(exceptionMapper.insert(any())).thenAnswer(inv -> {
            ShipmentExceptionEntity e = inv.getArgument(0);
            e.setId(UUID.randomUUID());
            store.put(e.getId(), e);
            return 1;
        });
        when(exceptionMapper.selectById(any())).thenAnswer(inv -> store.get(inv.getArgument(0)));

        ShipmentExceptionServiceImpl service = new ShipmentExceptionServiceImpl(
                exceptionMapper, shipmentMapper, mock(com.adpilot.modules.store.mapper.StoreMapper.class),
                mock(com.adpilot.common.security.DataScopeService.class));

        ShipmentExceptionVo vo = service.raise(shipmentId.toString(), dto);

        assertThat(vo.getId()).isNotNull();
        assertThat(vo.getShipmentId()).isEqualTo(shipmentId.toString());
        assertThat(vo.getExceptionType()).isEqualTo(dto.getExceptionType());
        assertThat(vo.getDescription()).isEqualTo(dto.getDescription());
        // Req 9.2: a newly created shipment exception is returned in the open state.
        assertThat(vo.getResolutionState()).isEqualTo("open");
        assertThat(vo.getResolvedBy()).isNull();
        assertThat(vo.getResolvedAt()).isNull();
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

    // ===================================================================== //
    // Generators                                                            //
    // ===================================================================== //

    /** Monetary value in [0.00, 999,999,999.99] with scale 2. */
    private Arbitrary<BigDecimal> money() {
        return Arbitraries.longs().between(0L, 99_999_999_999L).map(c -> BigDecimal.valueOf(c, 2));
    }

    // --- shipment legs --------------------------------------------------------

    @Provide
    Arbitrary<ShipmentLegDto> validLegs() {
        Arbitrary<String> legType = Arbitraries.of(
                "first_leg", "last_leg", "ocean_freight", "air_freight", "customs", "forwarder");
        Arbitrary<Integer> sequenceNo = Arbitraries.integers().between(1, 20);
        Arbitrary<Integer> departOffset = Arbitraries.integers().between(0, 3650);
        Arbitrary<Integer> arriveOffset = Arbitraries.integers().between(0, 365);
        return Combinators.combine(legType, sequenceNo, departOffset, arriveOffset, money())
                .as((type, seq, dOff, aOff, cost) -> {
                    LocalDate departure = LocalDate.of(2020, 1, 1).plusDays(dOff);
                    ShipmentLegDto dto = new ShipmentLegDto();
                    dto.setLegType(type);
                    dto.setSequenceNo(seq);
                    dto.setCarrierId(UUID.randomUUID().toString());
                    dto.setDepartureDate(departure);
                    dto.setArrivalDate(departure.plusDays(aOff));
                    dto.setLegCost(cost);
                    return dto;
                });
    }

    // --- carton specs ---------------------------------------------------------

    @Provide
    Arbitrary<CartonSpecDto> validCartonSpecs() {
        // Dimensions 0.1–1000.0 (scale 1); weight 0.01–10000.00 (scale 2); counts 1–1,000,000.
        Arbitrary<BigDecimal> dimension = Arbitraries.longs().between(1L, 10_000L).map(t -> BigDecimal.valueOf(t, 1));
        Arbitrary<BigDecimal> weight = Arbitraries.longs().between(1L, 1_000_000L).map(c -> BigDecimal.valueOf(c, 2));
        Arbitrary<Integer> count = Arbitraries.integers().between(1, 1_000_000);
        return Combinators.combine(dimension, dimension, dimension, weight, count, count)
                .as((length, width, height, w, units, boxes) -> {
                    CartonSpecDto dto = new CartonSpecDto();
                    dto.setBoxLengthCm(length);
                    dto.setBoxWidthCm(width);
                    dto.setBoxHeightCm(height);
                    dto.setBoxWeightKg(w);
                    dto.setUnitsPerBox(units);
                    dto.setBoxCount(boxes);
                    return dto;
                });
    }

    // --- FBA fields -----------------------------------------------------------

    @Provide
    Arbitrary<FbaFieldsDto> validFbaFields() {
        Arbitrary<String> fbaId = Arbitraries.strings().withCharRange('A', 'Z').ofMinLength(1).ofMaxLength(100);
        Arbitrary<String> status = Arbitraries.strings().withCharRange('a', 'z').ofMinLength(1).ofMaxLength(50);
        Arbitrary<String> fcCode = Arbitraries.strings().withCharRange('A', 'Z').ofMinLength(1).ofMaxLength(50);
        Arbitrary<List<FbaFieldsDto.FbaLineItemDto>> items = validLineItems().list().ofMinSize(0).ofMaxSize(5);
        return Combinators.combine(fbaId, status, fcCode, items)
                .as((id, st, fc, lineItems) -> {
                    FbaFieldsDto dto = new FbaFieldsDto();
                    dto.setFbaShipmentId(id);
                    dto.setAmazonShipmentStatus(st);
                    dto.setDestinationFcCode(fc);
                    dto.setLineItems(lineItems);
                    return dto;
                });
    }

    @Provide
    Arbitrary<FbaFieldsDto.FbaLineItemDto> validLineItems() {
        Arbitrary<String> sku = Arbitraries.strings().withCharRange('A', 'Z').ofMinLength(1).ofMaxLength(100);
        Arbitrary<String> msku = Arbitraries.strings().withCharRange('A', 'Z').ofMinLength(1).ofMaxLength(100);
        Arbitrary<String> asin = Arbitraries.strings().withCharRange('A', 'Z').ofMinLength(1).ofMaxLength(20);
        Arbitrary<Integer> quantity = Arbitraries.integers().between(1, 1_000_000);
        return Combinators.combine(sku, msku, asin, quantity).as((s, m, a, q) -> {
            FbaFieldsDto.FbaLineItemDto item = new FbaFieldsDto.FbaLineItemDto();
            item.setSku(s);
            item.setMsku(m);
            item.setAsin(a);
            item.setQuantity(q);
            return item;
        });
    }

    // --- handling costs -------------------------------------------------------

    @Provide
    Arbitrary<HandlingCostDto> validHandlingCosts() {
        Arbitrary<String> currency = Arbitraries.strings().withCharRange('A', 'Z').ofLength(3);
        Arbitrary<String> description = Arbitraries.strings().withCharRange('a', 'z').ofMinLength(1).ofMaxLength(200);
        return Combinators.combine(money(), currency, description)
                .as((amount, cur, desc) -> {
                    HandlingCostDto dto = new HandlingCostDto();
                    dto.setAmount(amount);
                    dto.setCurrencyCode(cur);
                    dto.setDescription(desc);
                    return dto;
                });
    }

    // --- shipment exceptions --------------------------------------------------

    @Provide
    Arbitrary<ShipmentExceptionDto> validExceptions() {
        Arbitrary<String> type = Arbitraries.of("delay", "damage", "customs-hold");
        Arbitrary<String> description = Arbitraries.strings().withCharRange('a', 'z').ofMinLength(1).ofMaxLength(1000);
        return Combinators.combine(type, description).as((t, d) -> {
            ShipmentExceptionDto dto = new ShipmentExceptionDto();
            dto.setExceptionType(t);
            dto.setDescription(d);
            return dto;
        });
    }
}
