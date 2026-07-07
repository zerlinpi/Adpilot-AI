package com.adpilot.modules.procurement.service.impl;

import com.adpilot.common.exception.BusinessException;
import com.adpilot.common.security.CurrentUser;
import com.adpilot.common.security.DataScopeService;
import com.adpilot.modules.procurement.dto.PurchaseOrderDto;
import com.adpilot.modules.procurement.dto.SupplierDto;
import com.adpilot.modules.procurement.entity.PurchaseOrderEntity;
import com.adpilot.modules.procurement.mapper.PurchaseOrderItemMapper;
import com.adpilot.modules.procurement.mapper.PurchaseOrderMapper;
import com.adpilot.modules.procurement.vo.SupplierVo;
import com.adpilot.modules.supplier.entity.SupplierEntity;
import com.adpilot.modules.supplier.mapper.SupplierMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Verifies the procurement service (H4) enforces multi-tenant isolation:
 * <ul>
 *   <li>suppliers are org-scoped — cross-org by-id reads are rejected (404) and
 *       {@code createSupplier} derives the org from the caller (mass-assignment
 *       blocked), never from the request body;</li>
 *   <li>purchase orders are store-scoped through the shared {@link DataScopeService}
 *       — by-id reads, create, and status changes outside the caller's scope are
 *       rejected (cross-tenant BOLA/BFLA blocked).</li>
 * </ul>
 */
class ProcurementServiceImplScopeTest {

    private SupplierMapper supplierMapper;
    private PurchaseOrderMapper purchaseOrderMapper;
    private PurchaseOrderItemMapper purchaseOrderItemMapper;
    private DataScopeService dataScopeService;
    private ProcurementServiceImpl service;

    private UUID callerOrg;

    @BeforeEach
    void setUp() {
        supplierMapper = mock(SupplierMapper.class);
        purchaseOrderMapper = mock(PurchaseOrderMapper.class);
        purchaseOrderItemMapper = mock(PurchaseOrderItemMapper.class);
        dataScopeService = mock(DataScopeService.class);
        service = new ProcurementServiceImpl(
                supplierMapper, purchaseOrderMapper, purchaseOrderItemMapper, dataScopeService);
        callerOrg = UUID.randomUUID();
        authenticate(callerOrg);
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    // ---- Suppliers (org-scoped) ----

    @Test
    void getSupplierByIdBlocksCrossOrgRead() {
        UUID id = UUID.randomUUID();
        SupplierEntity foreign = SupplierEntity.builder()
                .id(id).orgId(UUID.randomUUID()).supplierName("Foreign Co").build();
        when(supplierMapper.selectById(id)).thenReturn(foreign);

        assertThatThrownBy(() -> service.getSupplierById(id.toString()))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getStatus()).isEqualTo(404));
    }

    @Test
    void getSupplierByIdReturnsInOrgSupplier() {
        UUID id = UUID.randomUUID();
        SupplierEntity owned = SupplierEntity.builder()
                .id(id).orgId(callerOrg).supplierName("Home Co").build();
        when(supplierMapper.selectById(id)).thenReturn(owned);

        SupplierVo vo = service.getSupplierById(id.toString());

        assertThat(vo).isNotNull();
        assertThat(vo.getSupplierName()).isEqualTo("Home Co");
        assertThat(vo.getOrgId()).isEqualTo(callerOrg.toString());
    }

    @Test
    void createSupplierDerivesOrgFromCallerIgnoringBody() {
        SupplierDto dto = new SupplierDto();
        dto.setOrgId(UUID.randomUUID().toString()); // attacker-supplied foreign org
        dto.setSupplierName("New Co");
        when(supplierMapper.insert(any())).thenAnswer(inv -> {
            SupplierEntity e = inv.getArgument(0);
            e.setId(UUID.randomUUID());
            return 1;
        });

        SupplierVo vo = service.createSupplier(dto, UUID.randomUUID().toString());

        // Ownership must be the caller's org, never the body-supplied org.
        assertThat(vo.getOrgId()).isEqualTo(callerOrg.toString());
    }

    // ---- Purchase orders (store-scoped) ----

    @Test
    void getPurchaseOrderByIdBlocksCrossTenantRead() {
        UUID id = UUID.randomUUID();
        PurchaseOrderEntity foreign = PurchaseOrderEntity.builder()
                .id(id).storeId(UUID.randomUUID()).supplierId(UUID.randomUUID()).poNumber("PO-1").build();
        when(purchaseOrderMapper.selectById(id)).thenReturn(foreign);
        doThrow(new BusinessException(403, "FORBIDDEN", "outside your data scope"))
                .when(dataScopeService).assertCanRead(eq(foreign), any(CurrentUser.class));

        assertThatThrownBy(() -> service.getPurchaseOrderById(id.toString()))
                .isInstanceOf(BusinessException.class);

        verify(dataScopeService).assertCanRead(eq(foreign), any(CurrentUser.class));
    }

    @Test
    void createPurchaseOrderBlocksCrossTenantWrite() {
        PurchaseOrderDto dto = new PurchaseOrderDto();
        dto.setStoreId(UUID.randomUUID().toString()); // foreign store
        dto.setSupplierId(UUID.randomUUID().toString());
        dto.setPoNumber("PO-2");
        doThrow(new BusinessException(403, "FORBIDDEN", "outside your data scope"))
                .when(dataScopeService).assertCanWrite(any(PurchaseOrderEntity.class), any(CurrentUser.class));

        assertThatThrownBy(() -> service.createPurchaseOrder(dto, UUID.randomUUID().toString()))
                .isInstanceOf(BusinessException.class);

        verify(purchaseOrderMapper, never()).insert(any());
    }

    @Test
    void updatePurchaseOrderStatusBlocksCrossTenantWrite() {
        UUID id = UUID.randomUUID();
        PurchaseOrderEntity foreign = PurchaseOrderEntity.builder()
                .id(id).storeId(UUID.randomUUID()).supplierId(UUID.randomUUID()).poNumber("PO-3").build();
        when(purchaseOrderMapper.selectById(id)).thenReturn(foreign);
        doThrow(new BusinessException(403, "FORBIDDEN", "outside your data scope"))
                .when(dataScopeService).assertCanWrite(eq(foreign), any(CurrentUser.class));

        assertThatThrownBy(() -> service.updatePurchaseOrderStatus(id.toString(), "approved", UUID.randomUUID().toString()))
                .isInstanceOf(BusinessException.class);

        verify(purchaseOrderMapper, never()).updateById(any());
    }

    @Test
    void listPurchaseOrdersAppliesStoreScope() {
        when(purchaseOrderMapper.selectPage(any(), any()))
                .thenReturn(new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>());

        service.listPurchaseOrders(null, 1, 20);

        verify(dataScopeService).applyScope(any(), any(), any(CurrentUser.class));
    }

    private void authenticate(UUID orgId) {
        CurrentUser principal = CurrentUser.builder()
                .userId(UUID.randomUUID().toString())
                .email("buyer@example.com")
                .orgId(orgId.toString())
                .roles(java.util.Set.of("operations_specialist"))
                .permissions(List.of("procurement:view", "procurement:manage", "procurement:approve"))
                .build();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, List.of()));
    }
}
