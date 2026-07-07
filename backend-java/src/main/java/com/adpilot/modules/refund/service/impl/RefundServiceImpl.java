package com.adpilot.modules.refund.service.impl;

import com.adpilot.common.api.PageResponse;
import com.adpilot.common.exception.BusinessException;
import com.adpilot.modules.refund.dto.RefundDto;
import com.adpilot.modules.refund.entity.RefundEntity;
import com.adpilot.modules.refund.mapper.RefundMapper;
import com.adpilot.modules.refund.service.RefundService;
import com.adpilot.modules.refund.vo.RefundVo;
import com.adpilot.common.security.CurrentUser;
import com.adpilot.common.security.DataScopeService;
import com.adpilot.common.security.ScopeTarget;
import com.adpilot.common.utils.SecurityUtils;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class RefundServiceImpl implements RefundService {

    private final RefundMapper refundMapper;
    private final DataScopeService dataScopeService;

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** Store-only scope target shared by refund entities (Req 7.1.5). */
    private static final ScopeTarget STORE_SCOPE = ScopeTarget.store("store_id");

    /** Current authenticated user, or {@code null} for system/background contexts. */
    private static CurrentUser scopeUser() {
        return SecurityUtils.isAuthenticated() ? SecurityUtils.getCurrentUser() : null;
    }

    @Override
    public PageResponse<RefundVo> listRefunds(String storeId, int page, int pageSize) {
        Page<RefundEntity> pageParam = new Page<>(page, pageSize);
        QueryWrapper<RefundEntity> wrapper = new QueryWrapper<>();
        if (storeId != null && !storeId.isBlank()) {
            try { wrapper.eq("store_id", UUID.fromString(storeId).toString()); }
            catch (IllegalArgumentException ignored) { }
        }
        CurrentUser user = scopeUser();
        if (user != null) {
            dataScopeService.applyScope(wrapper, STORE_SCOPE, user);
        }
        wrapper.orderByDesc("created_at");

        Page<RefundEntity> result = refundMapper.selectPage(pageParam, wrapper);
        List<RefundVo> voList = result.getRecords().stream()
                .map(this::toVo)
                .collect(Collectors.toList());

        return PageResponse.of(voList, result.getTotal(), page, pageSize);
    }

    @Override
    public RefundVo getRefundById(String id) {
        RefundEntity entity = refundMapper.selectById(UUID.fromString(id));
        if (entity == null) {
            throw new BusinessException("REFUND_NOT_FOUND", "Refund not found: " + id);
        }
        CurrentUser user = scopeUser();
        if (user != null) {
            dataScopeService.assertCanRead(entity, user);
        }
        return toVo(entity);
    }

    @Override
    @Transactional
    public List<RefundVo> importRefunds(List<RefundDto> dtos, String userId) {
        CurrentUser user = scopeUser();
        List<RefundVo> results = new ArrayList<>();
        for (RefundDto dto : dtos) {
            RefundEntity entity = RefundEntity.builder()
                    .storeId(UUID.fromString(dto.getStoreId()))
                    .orderId(dto.getOrderId())
                    .refundId(dto.getRefundId())
                    .sku(dto.getSku())
                    .asin(dto.getAsin())
                    .refundAmount(dto.getRefundAmount() != null ? dto.getRefundAmount() : BigDecimal.ZERO)
                    .refundReason(dto.getRefundReason())
                    .refundStatus(dto.getRefundStatus())
                    .refundDate(dto.getRefundDate())
                    .currency(dto.getCurrency())
                    .rawData(dto.getRawData() != null ? dto.getRawData() : "{}")
                    .build();

            if (user != null) {
                dataScopeService.assertCanWrite(entity, user);
            }

            refundMapper.insert(entity);
            results.add(toVo(entity));
        }
        log.info("Imported {} refunds", results.size());
        return results;
    }

    private RefundVo toVo(RefundEntity entity) {
        return RefundVo.builder()
                .id(entity.getId().toString())
                .storeId(entity.getStoreId().toString())
                .orderId(entity.getOrderId())
                .refundId(entity.getRefundId())
                .sku(entity.getSku())
                .asin(entity.getAsin())
                .refundAmount(entity.getRefundAmount() != null ? entity.getRefundAmount().doubleValue() : 0.0)
                .refundReason(entity.getRefundReason())
                .refundStatus(entity.getRefundStatus())
                .refundDate(entity.getRefundDate() != null ? entity.getRefundDate().format(FORMATTER) : null)
                .currency(entity.getCurrency())
                .createdAt(entity.getCreatedAt() != null ? entity.getCreatedAt().format(FORMATTER) : null)
                .build();
    }
}
