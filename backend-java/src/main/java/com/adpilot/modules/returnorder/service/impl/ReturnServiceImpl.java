package com.adpilot.modules.returnorder.service.impl;

import com.adpilot.common.api.PageResponse;
import com.adpilot.common.exception.BusinessException;
import com.adpilot.modules.returnorder.dto.ReturnDto;
import com.adpilot.modules.returnorder.entity.ReturnEntity;
import com.adpilot.modules.returnorder.mapper.ReturnMapper;
import com.adpilot.modules.returnorder.service.ReturnService;
import com.adpilot.modules.returnorder.vo.ReturnVo;
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
public class ReturnServiceImpl implements ReturnService {

    private final ReturnMapper returnMapper;
    private final DataScopeService dataScopeService;

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** Store-only scope target shared by return entities (Req 7.1.5). */
    private static final ScopeTarget STORE_SCOPE = ScopeTarget.store("store_id");

    /** Current authenticated user, or {@code null} for system/background contexts. */
    private static CurrentUser scopeUser() {
        return SecurityUtils.isAuthenticated() ? SecurityUtils.getCurrentUser() : null;
    }

    @Override
    public PageResponse<ReturnVo> listReturns(String storeId, int page, int pageSize) {
        Page<ReturnEntity> pageParam = new Page<>(page, pageSize);
        QueryWrapper<ReturnEntity> wrapper = new QueryWrapper<>();
        if (storeId != null && !storeId.isBlank()) {
            try { wrapper.eq("store_id", UUID.fromString(storeId).toString()); }
            catch (IllegalArgumentException ignored) { /* invalid id -> no extra filter */ }
        }
        CurrentUser user = scopeUser();
        if (user != null) {
            dataScopeService.applyScope(wrapper, STORE_SCOPE, user);
        }
        wrapper.orderByDesc("created_at");

        Page<ReturnEntity> result = returnMapper.selectPage(pageParam, wrapper);
        List<ReturnVo> voList = result.getRecords().stream()
                .map(this::toVo)
                .collect(Collectors.toList());

        return PageResponse.of(voList, result.getTotal(), page, pageSize);
    }

    @Override
    public ReturnVo getReturnById(String id) {
        ReturnEntity entity = returnMapper.selectById(UUID.fromString(id));
        if (entity == null) {
            throw new BusinessException("RETURN_NOT_FOUND", "Return not found: " + id);
        }
        CurrentUser user = scopeUser();
        if (user != null) {
            dataScopeService.assertCanRead(entity, user);
        }
        return toVo(entity);
    }

    @Override
    @Transactional
    public List<ReturnVo> importReturns(List<ReturnDto> dtos, String userId) {
        CurrentUser user = scopeUser();
        List<ReturnVo> results = new ArrayList<>();
        for (ReturnDto dto : dtos) {
            ReturnEntity entity = ReturnEntity.builder()
                    .storeId(UUID.fromString(dto.getStoreId()))
                    .orderId(dto.getOrderId())
                    .returnId(dto.getReturnId())
                    .sku(dto.getSku())
                    .asin(dto.getAsin())
                    .quantityReturned(dto.getQuantityReturned() != null ? dto.getQuantityReturned() : 0)
                    .returnReason(dto.getReturnReason())
                    .returnStatus(dto.getReturnStatus())
                    .returnDate(dto.getReturnDate())
                    .refundAmount(dto.getRefundAmount() != null ? dto.getRefundAmount() : BigDecimal.ZERO)
                    .currency(dto.getCurrency())
                    .rawData(dto.getRawData() != null ? dto.getRawData() : "{}")
                    .build();

            if (user != null) {
                dataScopeService.assertCanWrite(entity, user);
            }

            returnMapper.insert(entity);
            results.add(toVo(entity));
        }
        log.info("Imported {} returns", results.size());
        return results;
    }

    private ReturnVo toVo(ReturnEntity entity) {
        return ReturnVo.builder()
                .id(entity.getId().toString())
                .storeId(entity.getStoreId().toString())
                .orderId(entity.getOrderId())
                .returnId(entity.getReturnId())
                .sku(entity.getSku())
                .asin(entity.getAsin())
                .quantityReturned(entity.getQuantityReturned() != null ? entity.getQuantityReturned() : 0)
                .returnReason(entity.getReturnReason())
                .returnStatus(entity.getReturnStatus())
                .returnDate(entity.getReturnDate() != null ? entity.getReturnDate().format(FORMATTER) : null)
                .refundAmount(entity.getRefundAmount() != null ? entity.getRefundAmount().doubleValue() : 0.0)
                .currency(entity.getCurrency())
                .createdAt(entity.getCreatedAt() != null ? entity.getCreatedAt().format(FORMATTER) : null)
                .build();
    }
}
