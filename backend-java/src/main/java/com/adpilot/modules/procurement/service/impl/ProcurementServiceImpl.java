package com.adpilot.modules.procurement.service.impl;

import com.adpilot.common.api.PageResponse;
import com.adpilot.common.exception.BusinessException;
import com.adpilot.modules.procurement.dto.PurchaseOrderDto;
import com.adpilot.modules.procurement.dto.SupplierDto;
import com.adpilot.modules.procurement.vo.SupplierVo;
import com.adpilot.modules.supplier.entity.SupplierEntity;
import com.adpilot.modules.supplier.mapper.SupplierMapper;
import com.adpilot.modules.procurement.entity.PurchaseOrderEntity;
import com.adpilot.modules.procurement.entity.PurchaseOrderItemEntity;
import com.adpilot.modules.procurement.mapper.PurchaseOrderItemMapper;
import com.adpilot.modules.procurement.mapper.PurchaseOrderMapper;
import com.adpilot.modules.procurement.service.ProcurementService;
import com.adpilot.modules.procurement.vo.PurchaseOrderVo;
import com.adpilot.common.security.CurrentUser;
import com.adpilot.common.security.DataScopeService;
import com.adpilot.common.security.ScopeTarget;
import com.adpilot.common.utils.SecurityUtils;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProcurementServiceImpl implements ProcurementService {

    private final SupplierMapper supplierMapper;
    private final PurchaseOrderMapper purchaseOrderMapper;
    private final PurchaseOrderItemMapper purchaseOrderItemMapper;
    private final DataScopeService dataScopeService;

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    /** Store-only scope target for purchase orders (Req 7.1.5). */
    private static final ScopeTarget STORE_SCOPE = ScopeTarget.store("store_id");

    /** Current authenticated user, or {@code null} for system/background contexts. */
    private static CurrentUser scopeUser() {
        return SecurityUtils.isAuthenticated() ? SecurityUtils.getCurrentUser() : null;
    }

    /**
     * Caller's org id, or {@code null} for system/background contexts. Suppliers are
     * org-scoped (no store dimension), so ownership is derived strictly from the
     * authenticated principal and never bound from request bodies.
     */
    private static UUID callerOrgId() {
        CurrentUser user = scopeUser();
        if (user == null || user.getOrgId() == null || user.getOrgId().isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(user.getOrgId());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    // ==================== Supplier Methods ====================

    @Override
    public PageResponse<SupplierVo> listSuppliers(String orgId, int page, int pageSize) {
        Page<SupplierEntity> pageParam = new Page<>(page, pageSize);
        LambdaQueryWrapper<SupplierEntity> wrapper = new LambdaQueryWrapper<>();
        // Suppliers are org-scoped: for an authenticated caller the org is derived
        // strictly from the principal and the client-supplied orgId is ignored, so
        // the listing can never widen to another tenant's suppliers.
        UUID callerOrg = callerOrgId();
        if (callerOrg != null) {
            wrapper.eq(SupplierEntity::getOrgId, callerOrg);
        } else if (orgId != null && !orgId.isEmpty()) {
            wrapper.eq(SupplierEntity::getOrgId, UUID.fromString(orgId));
        }
        wrapper.orderByDesc(SupplierEntity::getCreatedAt);

        Page<SupplierEntity> result = supplierMapper.selectPage(pageParam, wrapper);
        List<SupplierVo> voList = result.getRecords().stream()
                .map(this::toSupplierVo)
                .collect(Collectors.toList());

        return PageResponse.of(voList, result.getTotal(), page, pageSize);
    }

    @Override
    public SupplierVo getSupplierById(String id) {
        SupplierEntity entity = supplierMapper.selectById(UUID.fromString(id));
        if (entity == null) {
            throw new BusinessException("SUPPLIER_NOT_FOUND", "Supplier not found: " + id);
        }
        assertSupplierInOrg(entity, id);
        return toSupplierVo(entity);
    }

    /**
     * Reject a supplier that belongs to another tenant. Returns a 404 (rather than
     * 403) so a foreign supplier's existence is not disclosed, mirroring the store
     * org guard. System/background contexts (no principal) are not restricted.
     */
    private void assertSupplierInOrg(SupplierEntity entity, String requestedId) {
        UUID callerOrg = callerOrgId();
        if (callerOrg != null && !callerOrg.equals(entity.getOrgId())) {
            throw new BusinessException(404, "SUPPLIER_NOT_FOUND", "Supplier not found: " + requestedId);
        }
    }

    @Override
    @Transactional
    public SupplierVo createSupplier(SupplierDto dto, String userId) {
        // Mass-assignment guard: the owning org is derived from the authenticated
        // caller, never from the request body, so a supplier can't be planted into
        // an arbitrary tenant. Falls back to the body org only for system contexts.
        UUID callerOrg = callerOrgId();
        UUID orgId = callerOrg != null ? callerOrg : UUID.fromString(dto.getOrgId());
        SupplierEntity entity = SupplierEntity.builder()
                .orgId(orgId)
                .supplierName(dto.getSupplierName())
                .contactName(dto.getContactName())
                .contactEmail(dto.getContactEmail())
                .contactPhone(dto.getContactPhone())
                .address(dto.getAddress())
                .country(dto.getCountry())
                .paymentTerms(dto.getPaymentTerms())
                .leadTimeDays(dto.getLeadTimeDays())
                .rating(dto.getRating() != null ? dto.getRating() : BigDecimal.ZERO)
                .status("active")
                .notes(dto.getNotes())
                .build();

        supplierMapper.insert(entity);
        log.info("Supplier created: id={}, name={}", entity.getId(), entity.getSupplierName());
        return toSupplierVo(entity);
    }

    @Override
    @Transactional
    public SupplierVo updateSupplier(String id, SupplierDto dto, String userId) {
        SupplierEntity entity = supplierMapper.selectById(UUID.fromString(id));
        if (entity == null) {
            throw new BusinessException("SUPPLIER_NOT_FOUND", "Supplier not found: " + id);
        }
        assertSupplierInOrg(entity, id);

        if (dto.getSupplierName() != null) {
            entity.setSupplierName(dto.getSupplierName());
        }
        if (dto.getContactName() != null) {
            entity.setContactName(dto.getContactName());
        }
        if (dto.getContactEmail() != null) {
            entity.setContactEmail(dto.getContactEmail());
        }
        if (dto.getContactPhone() != null) {
            entity.setContactPhone(dto.getContactPhone());
        }
        if (dto.getAddress() != null) {
            entity.setAddress(dto.getAddress());
        }
        if (dto.getCountry() != null) {
            entity.setCountry(dto.getCountry());
        }
        if (dto.getPaymentTerms() != null) {
            entity.setPaymentTerms(dto.getPaymentTerms());
        }
        if (dto.getLeadTimeDays() != null) {
            entity.setLeadTimeDays(dto.getLeadTimeDays());
        }
        if (dto.getRating() != null) {
            entity.setRating(dto.getRating());
        }
        if (dto.getNotes() != null) {
            entity.setNotes(dto.getNotes());
        }
        // Ownership (org_id) is never rebound from the request body: doing so would
        // let a caller move a supplier into another tenant (mass-assignment).

        entity.setUpdatedAt(LocalDateTime.now());

        supplierMapper.updateById(entity);
        log.info("Supplier updated: id={}", id);
        return toSupplierVo(entity);
    }

    @Override
    @Transactional
    public void deleteSupplier(String id) {
        SupplierEntity entity = supplierMapper.selectById(UUID.fromString(id));
        if (entity == null) {
            throw new BusinessException("SUPPLIER_NOT_FOUND", "Supplier not found: " + id);
        }
        assertSupplierInOrg(entity, id);
        supplierMapper.deleteById(UUID.fromString(id));
        log.info("Supplier deleted: id={}", id);
    }

    // ==================== Purchase Order Methods ====================

    @Override
    public PageResponse<PurchaseOrderVo> listPurchaseOrders(String storeId, int page, int pageSize) {
        Page<PurchaseOrderEntity> pageParam = new Page<>(page, pageSize);
        QueryWrapper<PurchaseOrderEntity> wrapper = new QueryWrapper<>();
        if (storeId != null && !storeId.isEmpty()) {
            wrapper.eq("store_id", UUID.fromString(storeId).toString());
        }
        // Store-scope the listing so a caller only ever sees purchase orders for
        // stores within their effective data scope (empty scope -> no rows).
        CurrentUser user = scopeUser();
        if (user != null) {
            dataScopeService.applyScope(wrapper, STORE_SCOPE, user);
        }
        wrapper.orderByDesc("created_at");

        Page<PurchaseOrderEntity> result = purchaseOrderMapper.selectPage(pageParam, wrapper);
        List<PurchaseOrderVo> voList = result.getRecords().stream()
                .map(this::toPurchaseOrderVo)
                .collect(Collectors.toList());

        return PageResponse.of(voList, result.getTotal(), page, pageSize);
    }

    @Override
    public PurchaseOrderVo getPurchaseOrderById(String id) {
        PurchaseOrderEntity entity = purchaseOrderMapper.selectById(UUID.fromString(id));
        if (entity == null) {
            throw new BusinessException("PURCHASE_ORDER_NOT_FOUND", "Purchase order not found: " + id);
        }
        CurrentUser user = scopeUser();
        if (user != null) {
            dataScopeService.assertCanRead(entity, user);
        }

        // Fetch items
        LambdaQueryWrapper<PurchaseOrderItemEntity> itemWrapper = new LambdaQueryWrapper<>();
        itemWrapper.eq(PurchaseOrderItemEntity::getPurchaseOrderId, entity.getId());
        List<PurchaseOrderItemEntity> items = purchaseOrderItemMapper.selectList(itemWrapper);

        PurchaseOrderVo vo = toPurchaseOrderVo(entity);
        vo.setItems(items.stream()
                .map(this::toPurchaseOrderItemVo)
                .collect(Collectors.toList()));

        return vo;
    }

    @Override
    @Transactional
    public PurchaseOrderVo createPurchaseOrder(PurchaseOrderDto dto, String userId) {
        BigDecimal totalAmount = BigDecimal.ZERO;

        // Calculate total amount from items if not provided
        if (dto.getItems() != null && !dto.getItems().isEmpty()) {
            for (PurchaseOrderDto.PurchaseOrderItemDto itemDto : dto.getItems()) {
                if (itemDto.getUnitCost() != null && itemDto.getQuantityOrdered() != null) {
                    BigDecimal itemTotal = itemDto.getUnitCost().multiply(BigDecimal.valueOf(itemDto.getQuantityOrdered()));
                    totalAmount = totalAmount.add(itemTotal);
                }
            }
        }

        PurchaseOrderEntity entity = PurchaseOrderEntity.builder()
                .storeId(UUID.fromString(dto.getStoreId()))
                .supplierId(UUID.fromString(dto.getSupplierId()))
                .poNumber(dto.getPoNumber())
                .status(dto.getStatus() != null ? dto.getStatus() : "draft")
                .totalAmount(dto.getTotalAmount() != null ? dto.getTotalAmount() : totalAmount)
                .currency(dto.getCurrency())
                .orderDate(dto.getOrderDate() != null ? LocalDate.parse(dto.getOrderDate(), DATE_FORMATTER) : null)
                .expectedDeliveryDate(dto.getExpectedDeliveryDate() != null ? LocalDate.parse(dto.getExpectedDeliveryDate(), DATE_FORMATTER) : null)
                .shippingMethod(dto.getShippingMethod())
                .trackingNumber(dto.getTrackingNumber())
                .notes(dto.getNotes())
                .createdBy(userId != null ? UUID.fromString(userId) : null)
                .build();

        // Mass-assignment guard: validate the body-supplied storeId is within the
        // caller's scope before planting the record, so a purchase order can't be
        // created against an arbitrary store/tenant.
        CurrentUser user = scopeUser();
        if (user != null) {
            dataScopeService.assertCanWrite(entity, user);
        }

        purchaseOrderMapper.insert(entity);
        log.info("Purchase order created: id={}, poNumber={}", entity.getId(), entity.getPoNumber());

        // Insert items
        List<PurchaseOrderItemEntity> itemEntities = null;
        if (dto.getItems() != null && !dto.getItems().isEmpty()) {
            itemEntities = dto.getItems().stream()
                    .map(itemDto -> {
                        BigDecimal itemTotal = BigDecimal.ZERO;
                        if (itemDto.getUnitCost() != null && itemDto.getQuantityOrdered() != null) {
                            itemTotal = itemDto.getUnitCost().multiply(BigDecimal.valueOf(itemDto.getQuantityOrdered()));
                        }
                        return PurchaseOrderItemEntity.builder()
                                .purchaseOrderId(entity.getId())
                                .sku(itemDto.getSku())
                                .asin(itemDto.getAsin())
                                .productName(itemDto.getProductName())
                                .quantityOrdered(itemDto.getQuantityOrdered() != null ? itemDto.getQuantityOrdered() : 0)
                                .quantityReceived(0)
                                .unitCost(itemDto.getUnitCost() != null ? itemDto.getUnitCost() : BigDecimal.ZERO)
                                .totalCost(itemTotal)
                                .currency(dto.getCurrency())
                                .notes(itemDto.getNotes())
                                .build();
                    })
                    .collect(Collectors.toList());

            for (PurchaseOrderItemEntity itemEntity : itemEntities) {
                purchaseOrderItemMapper.insert(itemEntity);
            }
        }

        PurchaseOrderVo vo = toPurchaseOrderVo(entity);
        if (itemEntities != null) {
            vo.setItems(itemEntities.stream()
                    .map(this::toPurchaseOrderItemVo)
                    .collect(Collectors.toList()));
        }
        return vo;
    }

    @Override
    @Transactional
    public PurchaseOrderVo updatePurchaseOrderStatus(String id, String status, String userId) {
        PurchaseOrderEntity entity = purchaseOrderMapper.selectById(UUID.fromString(id));
        if (entity == null) {
            throw new BusinessException("PURCHASE_ORDER_NOT_FOUND", "Purchase order not found: " + id);
        }
        CurrentUser user = scopeUser();
        if (user != null) {
            dataScopeService.assertCanWrite(entity, user);
        }

        entity.setStatus(status);
        entity.setUpdatedAt(LocalDateTime.now());

        // Set approval info if status is approved
        if ("approved".equals(status)) {
            entity.setApprovedBy(userId != null ? UUID.fromString(userId) : null);
            entity.setApprovedAt(LocalDateTime.now());
        }

        purchaseOrderMapper.updateById(entity);
        log.info("Purchase order status updated: id={}, status={}", id, status);
        return toPurchaseOrderVo(entity);
    }

    // ==================== Conversion Methods ====================

    private SupplierVo toSupplierVo(SupplierEntity entity) {
        return SupplierVo.builder()
                .id(entity.getId().toString())
                .orgId(entity.getOrgId().toString())
                .supplierName(entity.getSupplierName())
                .contactName(entity.getContactName())
                .contactEmail(entity.getContactEmail())
                .contactPhone(entity.getContactPhone())
                .address(entity.getAddress())
                .country(entity.getCountry())
                .paymentTerms(entity.getPaymentTerms())
                .leadTimeDays(entity.getLeadTimeDays())
                .rating(entity.getRating() != null ? entity.getRating().doubleValue() : 0.0)
                .status(entity.getStatus())
                .notes(entity.getNotes())
                .createdAt(entity.getCreatedAt() != null ? entity.getCreatedAt().format(FORMATTER) : null)
                .updatedAt(entity.getUpdatedAt() != null ? entity.getUpdatedAt().format(FORMATTER) : null)
                .build();
    }

    private PurchaseOrderVo toPurchaseOrderVo(PurchaseOrderEntity entity) {
        return PurchaseOrderVo.builder()
                .id(entity.getId().toString())
                .storeId(entity.getStoreId().toString())
                .supplierId(entity.getSupplierId().toString())
                .poNumber(entity.getPoNumber())
                .status(entity.getStatus())
                .totalAmount(entity.getTotalAmount() != null ? entity.getTotalAmount().doubleValue() : 0.0)
                .currency(entity.getCurrency())
                .orderDate(entity.getOrderDate() != null ? entity.getOrderDate().format(DATE_FORMATTER) : null)
                .expectedDeliveryDate(entity.getExpectedDeliveryDate() != null ? entity.getExpectedDeliveryDate().format(DATE_FORMATTER) : null)
                .actualDeliveryDate(entity.getActualDeliveryDate() != null ? entity.getActualDeliveryDate().format(DATE_FORMATTER) : null)
                .shippingMethod(entity.getShippingMethod())
                .trackingNumber(entity.getTrackingNumber())
                .notes(entity.getNotes())
                .createdBy(entity.getCreatedBy() != null ? entity.getCreatedBy().toString() : null)
                .approvedBy(entity.getApprovedBy() != null ? entity.getApprovedBy().toString() : null)
                .approvedAt(entity.getApprovedAt() != null ? entity.getApprovedAt().format(FORMATTER) : null)
                .createdAt(entity.getCreatedAt() != null ? entity.getCreatedAt().format(FORMATTER) : null)
                .updatedAt(entity.getUpdatedAt() != null ? entity.getUpdatedAt().format(FORMATTER) : null)
                .build();
    }

    private PurchaseOrderVo.PurchaseOrderItemVo toPurchaseOrderItemVo(PurchaseOrderItemEntity entity) {
        return PurchaseOrderVo.PurchaseOrderItemVo.builder()
                .id(entity.getId().toString())
                .purchaseOrderId(entity.getPurchaseOrderId().toString())
                .sku(entity.getSku())
                .asin(entity.getAsin())
                .productName(entity.getProductName())
                .quantityOrdered(entity.getQuantityOrdered())
                .quantityReceived(entity.getQuantityReceived())
                .unitCost(entity.getUnitCost() != null ? entity.getUnitCost().doubleValue() : 0.0)
                .totalCost(entity.getTotalCost() != null ? entity.getTotalCost().doubleValue() : 0.0)
                .currency(entity.getCurrency())
                .notes(entity.getNotes())
                .createdAt(entity.getCreatedAt() != null ? entity.getCreatedAt().format(FORMATTER) : null)
                .updatedAt(entity.getUpdatedAt() != null ? entity.getUpdatedAt().format(FORMATTER) : null)
                .build();
    }
}
