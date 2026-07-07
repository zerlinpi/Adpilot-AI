package com.adpilot.modules.logistics.service.impl;

import com.adpilot.common.exception.BusinessException;
import com.adpilot.modules.logistics.dto.CartonSpecDto;
import com.adpilot.modules.logistics.entity.CartonSpecEntity;
import com.adpilot.modules.logistics.mapper.CartonSpecMapper;
import com.adpilot.modules.logistics.vo.CartonSpecVo;
import com.adpilot.modules.logistics.vo.CartonTotalsVo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link CartonSpecServiceImpl} covering the Requirement 5
 * bounds, field-level rejection without side effects, and totals computation.
 */
@ExtendWith(MockitoExtension.class)
class CartonSpecServiceImplTest {

    @Mock
    private CartonSpecMapper cartonSpecMapper;

    @InjectMocks
    private CartonSpecServiceImpl service;

    private String shipmentId;

    @BeforeEach
    void setUp() {
        shipmentId = UUID.randomUUID().toString();
    }

    private CartonSpecDto validDto() {
        CartonSpecDto dto = new CartonSpecDto();
        dto.setBoxLengthCm(new BigDecimal("40.0"));
        dto.setBoxWidthCm(new BigDecimal("30.0"));
        dto.setBoxHeightCm(new BigDecimal("25.0"));
        dto.setBoxWeightKg(new BigDecimal("12.50"));
        dto.setUnitsPerBox(24);
        dto.setBoxCount(10);
        return dto;
    }

    // --- addSpec: happy path -------------------------------------------------

    @Test
    void addSpec_persistsAndReturnsAllFieldValues() {
        when(cartonSpecMapper.selectCount(any())).thenReturn(0L);
        when(cartonSpecMapper.selectById(any())).thenReturn(null);

        CartonSpecDto dto = validDto();
        CartonSpecVo vo = service.addSpec(shipmentId, dto);

        ArgumentCaptor<CartonSpecEntity> captor = ArgumentCaptor.forClass(CartonSpecEntity.class);
        verify(cartonSpecMapper).insert(captor.capture());
        CartonSpecEntity persisted = captor.getValue();

        assertThat(persisted.getShipmentId()).isEqualTo(UUID.fromString(shipmentId));
        assertThat(persisted.getBoxLengthCm()).isEqualByComparingTo("40.0");
        assertThat(persisted.getBoxWidthCm()).isEqualByComparingTo("30.0");
        assertThat(persisted.getBoxHeightCm()).isEqualByComparingTo("25.0");
        assertThat(persisted.getBoxWeightKg()).isEqualByComparingTo("12.50");
        assertThat(persisted.getUnitsPerBox()).isEqualTo(24);
        assertThat(persisted.getBoxCount()).isEqualTo(10);

        assertThat(vo.getShipmentId()).isEqualTo(shipmentId);
        assertThat(vo.getUnitsPerBox()).isEqualTo(24);
        assertThat(vo.getBoxCount()).isEqualTo(10);
        assertThat(vo.getBoxWeightKg()).isEqualByComparingTo("12.50");
    }

    @Test
    void addSpec_acceptsBoundaryValues() {
        when(cartonSpecMapper.selectCount(any())).thenReturn(0L);
        when(cartonSpecMapper.selectById(any())).thenReturn(null);

        CartonSpecDto dto = new CartonSpecDto();
        dto.setBoxLengthCm(new BigDecimal("0.1"));
        dto.setBoxWidthCm(new BigDecimal("1000.0"));
        dto.setBoxHeightCm(new BigDecimal("0.1"));
        dto.setBoxWeightKg(new BigDecimal("0.01"));
        dto.setUnitsPerBox(1);
        dto.setBoxCount(1_000_000);

        service.addSpec(shipmentId, dto);
        verify(cartonSpecMapper).insert(any(CartonSpecEntity.class));
    }

    // --- addSpec: validation rejects without persisting ----------------------

    @Test
    void addSpec_rejectsDimensionBelowMin_noInsert() {
        CartonSpecDto dto = validDto();
        dto.setBoxLengthCm(new BigDecimal("0.05"));

        assertThatThrownBy(() -> service.addSpec(shipmentId, dto))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("boxLengthCm");

        verify(cartonSpecMapper, never()).insert(any());
    }

    @Test
    void addSpec_rejectsDimensionAboveMax_noInsert() {
        CartonSpecDto dto = validDto();
        dto.setBoxHeightCm(new BigDecimal("1000.1"));

        assertThatThrownBy(() -> service.addSpec(shipmentId, dto))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("boxHeightCm");

        verify(cartonSpecMapper, never()).insert(any());
    }

    @Test
    void addSpec_rejectsWeightOutOfBounds_noInsert() {
        CartonSpecDto dto = validDto();
        dto.setBoxWeightKg(new BigDecimal("10000.01"));

        assertThatThrownBy(() -> service.addSpec(shipmentId, dto))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("boxWeightKg");

        verify(cartonSpecMapper, never()).insert(any());
    }

    @Test
    void addSpec_rejectsNonPositiveCounts_noInsert() {
        CartonSpecDto dto = validDto();
        dto.setUnitsPerBox(0);
        dto.setBoxCount(0);

        assertThatThrownBy(() -> service.addSpec(shipmentId, dto))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("unitsPerBox")
                .hasMessageContaining("boxCount");

        verify(cartonSpecMapper, never()).insert(any());
    }

    @Test
    void addSpec_rejectsCountAboveMax_noInsert() {
        CartonSpecDto dto = validDto();
        dto.setBoxCount(1_000_001);

        assertThatThrownBy(() -> service.addSpec(shipmentId, dto))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("boxCount");

        verify(cartonSpecMapper, never()).insert(any());
    }

    @Test
    void addSpec_rejectsMissingFields_noInsert() {
        CartonSpecDto dto = new CartonSpecDto(); // all null

        assertThatThrownBy(() -> service.addSpec(shipmentId, dto))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("required");

        verify(cartonSpecMapper, never()).insert(any());
    }

    @Test
    void addSpec_rejectsWhenLimitReached_noInsert() {
        when(cartonSpecMapper.selectCount(any())).thenReturn(100L);

        assertThatThrownBy(() -> service.addSpec(shipmentId, validDto()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("at most 100");

        verify(cartonSpecMapper, never()).insert(any());
    }

    // --- totals --------------------------------------------------------------

    @Test
    void totals_emptySet_returnsZeroZero() {
        when(cartonSpecMapper.selectList(any())).thenReturn(Collections.emptyList());

        CartonTotalsVo totals = service.totals(shipmentId);

        assertThat(totals.getTotalBoxCount()).isZero();
        assertThat(totals.getTotalUnitQuantity()).isZero();
        assertThat(totals.getShipmentId()).isEqualTo(shipmentId);
    }

    @Test
    void totals_sumsBoxCountsAndUnitQuantity() {
        List<CartonSpecEntity> specs = Arrays.asList(
                spec(10, 24),  // 24*10 = 240
                spec(5, 100),  // 100*5 = 500
                spec(2, 3)     // 3*2 = 6
        );
        when(cartonSpecMapper.selectList(any())).thenReturn(specs);

        CartonTotalsVo totals = service.totals(shipmentId);

        assertThat(totals.getTotalBoxCount()).isEqualTo(17L);        // 10+5+2
        assertThat(totals.getTotalUnitQuantity()).isEqualTo(746L);   // 240+500+6
    }

    private CartonSpecEntity spec(int boxCount, int unitsPerBox) {
        return CartonSpecEntity.builder()
                .id(UUID.randomUUID())
                .shipmentId(UUID.fromString(shipmentId))
                .boxCount(boxCount)
                .unitsPerBox(unitsPerBox)
                .build();
    }
}
