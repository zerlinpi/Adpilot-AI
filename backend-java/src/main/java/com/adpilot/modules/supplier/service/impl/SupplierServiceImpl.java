package com.adpilot.modules.supplier.service.impl;

import com.adpilot.common.api.PageResponse;
import com.adpilot.common.exception.BusinessException;
import com.adpilot.modules.supplier.dto.SupplierDto;
import com.adpilot.modules.supplier.entity.SupplierEntity;
import com.adpilot.modules.supplier.mapper.SupplierMapper;
import com.adpilot.modules.supplier.service.SupplierService;
import com.adpilot.modules.supplier.vo.SupplierVo;
import com.adpilot.common.utils.SecurityUtils;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class SupplierServiceImpl implements SupplierService {

    private final SupplierMapper supplierMapper;

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Override
    public PageResponse<SupplierVo> listSuppliers(int page, int pageSize) {
        Page<SupplierEntity> pageParam = new Page<>(page, pageSize);
        LambdaQueryWrapper<SupplierEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.orderByDesc(SupplierEntity::getCreatedAt);

        Page<SupplierEntity> result = supplierMapper.selectPage(pageParam, wrapper);
        List<SupplierVo> voList = result.getRecords().stream()
                .map(this::toVo)
                .collect(Collectors.toList());

        return PageResponse.of(voList, result.getTotal(), page, pageSize);
    }

    @Override
    public SupplierVo getSupplierById(String id) {
        SupplierEntity entity = supplierMapper.selectById(UUID.fromString(id));
        if (entity == null) {
            throw new BusinessException("SUPPLIER_NOT_FOUND", "Supplier not found: " + id);
        }
        return toVo(entity);
    }

    @Override
    @Transactional
    public SupplierVo createSupplier(SupplierDto dto) {
        // Resolve the owning organization: prefer an explicit orgId from the
        // payload, otherwise fall back to the authenticated user's org. The
        // suppliers.org_id column is NOT NULL, so a missing org would otherwise
        // fail the insert with a constraint violation (Req: store/org scoping).
        String orgId = dto.getOrgId();
        if (orgId == null || orgId.isBlank()) {
            orgId = SecurityUtils.isAuthenticated() ? SecurityUtils.getCurrentOrgId() : null;
        }
        if (orgId == null || orgId.isBlank()) {
            throw new BusinessException("ORG_REQUIRED", "Unable to resolve organization for supplier creation");
        }

        SupplierEntity entity = SupplierEntity.builder()
                .orgId(UUID.fromString(orgId))
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
        return toVo(entity);
    }

    @Override
    @Transactional
    public SupplierVo updateSupplier(String id, SupplierDto dto) {
        SupplierEntity entity = supplierMapper.selectById(UUID.fromString(id));
        if (entity == null) {
            throw new BusinessException("SUPPLIER_NOT_FOUND", "Supplier not found: " + id);
        }

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
        if (dto.getOrgId() != null) {
            entity.setOrgId(UUID.fromString(dto.getOrgId()));
        }
        if (dto.getNotes() != null) {
            entity.setNotes(dto.getNotes());
        }

        entity.setUpdatedAt(LocalDateTime.now());

        supplierMapper.updateById(entity);
        log.info("Supplier updated: id={}", id);
        return toVo(entity);
    }

    @Override
    @Transactional
    public void deleteSupplier(String id) {
        SupplierEntity entity = supplierMapper.selectById(UUID.fromString(id));
        if (entity == null) {
            throw new BusinessException("SUPPLIER_NOT_FOUND", "Supplier not found: " + id);
        }
        supplierMapper.deleteById(UUID.fromString(id));
        log.info("Supplier deleted: id={}", id);
    }

    private SupplierVo toVo(SupplierEntity entity) {
        return SupplierVo.builder()
                .id(entity.getId().toString())
                .orgId(entity.getOrgId() != null ? entity.getOrgId().toString() : null)
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
}
