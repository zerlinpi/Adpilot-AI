package com.adpilot.modules.logistics.service.impl;

import com.adpilot.common.exception.BusinessException;
import com.adpilot.common.utils.SecurityUtils;
import com.adpilot.modules.logistics.dto.CarrierDto;
import com.adpilot.modules.logistics.entity.CarrierEntity;
import com.adpilot.modules.logistics.mapper.CarrierMapper;
import com.adpilot.modules.logistics.vo.CarrierVo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

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
 * Unit tests for {@link CarrierServiceImpl} covering the Requirement 6 bounds
 * (name 1–200, service type 1–100), field-level rejection without side
 * effects, list semantics (empty list when none), and Requirement 17.1 org
 * scoping.
 */
@ExtendWith(MockitoExtension.class)
class CarrierServiceImplTest {

    @Mock
    private CarrierMapper carrierMapper;

    @InjectMocks
    private CarrierServiceImpl service;

    private MockedStatic<SecurityUtils> securityUtils;
    private UUID orgId;

    @BeforeEach
    void setUp() {
        orgId = UUID.randomUUID();
        securityUtils = Mockito.mockStatic(SecurityUtils.class);
        securityUtils.when(SecurityUtils::getCurrentOrgId).thenReturn(orgId.toString());
    }

    @AfterEach
    void tearDown() {
        securityUtils.close();
    }

    private CarrierDto validDto() {
        CarrierDto dto = new CarrierDto();
        dto.setName("DHL Express");
        dto.setServiceType("Air Freight");
        return dto;
    }

    // --- create: happy path --------------------------------------------------

    @Test
    void create_persistsScopedToOrgAndReturnsFieldValues() {
        when(carrierMapper.selectById(any())).thenReturn(null);

        CarrierVo vo = service.create(validDto());

        ArgumentCaptor<CarrierEntity> captor = ArgumentCaptor.forClass(CarrierEntity.class);
        verify(carrierMapper).insert(captor.capture());
        CarrierEntity persisted = captor.getValue();

        assertThat(persisted.getOrgId()).isEqualTo(orgId);
        assertThat(persisted.getName()).isEqualTo("DHL Express");
        assertThat(persisted.getServiceType()).isEqualTo("Air Freight");

        assertThat(vo.getOrgId()).isEqualTo(orgId.toString());
        assertThat(vo.getName()).isEqualTo("DHL Express");
        assertThat(vo.getServiceType()).isEqualTo("Air Freight");
    }

    @Test
    void create_acceptsBoundaryLengths() {
        when(carrierMapper.selectById(any())).thenReturn(null);

        CarrierDto dto = new CarrierDto();
        dto.setName("a".repeat(200));        // max name
        dto.setServiceType("s".repeat(100)); // max service type

        service.create(dto);
        verify(carrierMapper).insert(any(CarrierEntity.class));
    }

    // --- create: validation rejects without persisting -----------------------

    @Test
    void create_rejectsBlankName_noInsert() {
        CarrierDto dto = validDto();
        dto.setName("  ");

        assertThatThrownBy(() -> service.create(dto))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("name");

        verify(carrierMapper, never()).insert(any());
    }

    @Test
    void create_rejectsNameTooLong_noInsert() {
        CarrierDto dto = validDto();
        dto.setName("a".repeat(201));

        assertThatThrownBy(() -> service.create(dto))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("name");

        verify(carrierMapper, never()).insert(any());
    }

    @Test
    void create_rejectsBlankServiceType_noInsert() {
        CarrierDto dto = validDto();
        dto.setServiceType(null);

        assertThatThrownBy(() -> service.create(dto))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("serviceType");

        verify(carrierMapper, never()).insert(any());
    }

    @Test
    void create_rejectsServiceTypeTooLong_noInsert() {
        CarrierDto dto = validDto();
        dto.setServiceType("s".repeat(101));

        assertThatThrownBy(() -> service.create(dto))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("serviceType");

        verify(carrierMapper, never()).insert(any());
    }

    @Test
    void create_rejectsBothInvalid_identifiesEachField_noInsert() {
        CarrierDto dto = new CarrierDto(); // both null

        assertThatThrownBy(() -> service.create(dto))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("name")
                .hasMessageContaining("serviceType");

        verify(carrierMapper, never()).insert(any());
    }

    // --- update --------------------------------------------------------------

    @Test
    void update_persistsChangesForOwnedCarrier() {
        UUID carrierId = UUID.randomUUID();
        CarrierEntity existing = CarrierEntity.builder()
                .id(carrierId).orgId(orgId).name("Old").serviceType("Old Service").build();
        when(carrierMapper.selectById(carrierId)).thenReturn(existing);

        CarrierDto dto = new CarrierDto();
        dto.setName("FedEx");
        dto.setServiceType("Ocean Freight");

        CarrierVo vo = service.update(carrierId.toString(), dto);

        verify(carrierMapper).updateById(any(CarrierEntity.class));
        assertThat(vo.getName()).isEqualTo("FedEx");
        assertThat(vo.getServiceType()).isEqualTo("Ocean Freight");
    }

    @Test
    void update_rejectsCarrierFromAnotherOrg_asNotFound_noUpdate() {
        UUID carrierId = UUID.randomUUID();
        CarrierEntity otherOrgCarrier = CarrierEntity.builder()
                .id(carrierId).orgId(UUID.randomUUID()).name("Other").serviceType("X").build();
        when(carrierMapper.selectById(carrierId)).thenReturn(otherOrgCarrier);

        assertThatThrownBy(() -> service.update(carrierId.toString(), validDto()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("not found");

        verify(carrierMapper, never()).updateById(any());
    }

    @Test
    void update_rejectsMissingCarrier_asNotFound_noUpdate() {
        UUID carrierId = UUID.randomUUID();
        when(carrierMapper.selectById(carrierId)).thenReturn(null);

        assertThatThrownBy(() -> service.update(carrierId.toString(), validDto()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("not found");

        verify(carrierMapper, never()).updateById(any());
    }

    @Test
    void update_rejectsInvalidPayload_noUpdate() {
        CarrierDto dto = validDto();
        dto.setName("");

        assertThatThrownBy(() -> service.update(UUID.randomUUID().toString(), dto))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("name");

        verify(carrierMapper, never()).updateById(any());
    }

    // --- list ----------------------------------------------------------------

    @Test
    void list_returnsEmptyList_whenNoneExist() {
        when(carrierMapper.selectList(any())).thenReturn(Collections.emptyList());

        List<CarrierVo> result = service.list();

        assertThat(result).isEmpty();
    }

    @Test
    void list_returnsOrgScopedCarriers() {
        List<CarrierEntity> carriers = Arrays.asList(
                CarrierEntity.builder().id(UUID.randomUUID()).orgId(orgId).name("A").serviceType("Air").build(),
                CarrierEntity.builder().id(UUID.randomUUID()).orgId(orgId).name("B").serviceType("Sea").build()
        );
        when(carrierMapper.selectList(any())).thenReturn(carriers);

        List<CarrierVo> result = service.list();

        assertThat(result).hasSize(2);
        assertThat(result).allSatisfy(vo -> assertThat(vo.getOrgId()).isEqualTo(orgId.toString()));
    }
}
