package com.adpilot.modules.advertising.service.impl;

import com.adpilot.common.api.PageResponse;
import com.adpilot.common.exception.BusinessException;
import com.adpilot.common.security.CurrentUser;
import com.adpilot.common.security.DataScopeService;
import com.adpilot.common.security.ScopeTarget;
import com.adpilot.common.utils.SecurityUtils;
import com.adpilot.modules.advertising.converter.KeywordConverter;
import com.adpilot.modules.advertising.dto.KeywordUpdateRequest;
import com.adpilot.modules.advertising.entity.BidChangeEntity;
import com.adpilot.modules.advertising.entity.KeywordEntity;
import com.adpilot.modules.advertising.mapper.BidChangeMapper;
import com.adpilot.modules.advertising.mapper.KeywordMapper;
import com.adpilot.modules.advertising.service.KeywordService;
import com.adpilot.modules.advertising.vo.KeywordVo;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class KeywordServiceImpl implements KeywordService {

    private final KeywordMapper keywordMapper;
    private final BidChangeMapper bidChangeMapper;
    private final DataScopeService dataScopeService;

    /** Store + owner scope target for keywords (Req 24.1). */
    private static final ScopeTarget KEYWORD_SCOPE = ScopeTarget.storeAndOwner("store_id", "created_by");

    /** Current authenticated user, or {@code null} for system/background contexts. */
    private static CurrentUser scopeUser() {
        return SecurityUtils.isAuthenticated() ? SecurityUtils.getCurrentUser() : null;
    }

    @Override
    public PageResponse<KeywordVo> listKeywords(String storeId, String campaignId, String status, int page, int pageSize) {
        Page<KeywordEntity> pageParam = new Page<>(page, pageSize);
        QueryWrapper<KeywordEntity> wrapper = new QueryWrapper<>();

        if (storeId != null && !storeId.isBlank()) {
            wrapper.eq("store_id", UUID.fromString(storeId));
        }
        if (campaignId != null && !campaignId.isBlank()) {
            wrapper.eq("campaign_id", UUID.fromString(campaignId));
        }
        if (status != null && !status.isBlank()) {
            wrapper.eq("status", status);
        }

        // Constrain the result set to the caller's effective data scope (Req 24.1)
        // so cross-store keywords are never disclosed via the list.
        CurrentUser user = scopeUser();
        if (user != null) {
            dataScopeService.applyScope(wrapper, KEYWORD_SCOPE, user);
        }
        wrapper.orderByDesc("created_at");

        Page<KeywordEntity> result = keywordMapper.selectPage(pageParam, wrapper);
        List<KeywordVo> voList = result.getRecords().stream()
                .map(KeywordConverter::toVo)
                .collect(Collectors.toList());

        return PageResponse.of(voList, result.getTotal(), page, pageSize);
    }

    @Override
    @Transactional
    public KeywordVo updateKeyword(String id, KeywordUpdateRequest request, String userId) {
        KeywordEntity entity = keywordMapper.selectById(UUID.fromString(id));
        if (entity == null) {
            throw new BusinessException("KEYWORD_NOT_FOUND", "Keyword not found: " + id);
        }

        // Per-record ownership: confirm the referenced keyword belongs to a Store
        // within the caller's effective data scope before mutating it (Req 25.1/25.2).
        CurrentUser user = scopeUser();
        if (user != null) {
            dataScopeService.assertCanWrite(entity, user);
        }

        BigDecimal oldBid = entity.getBid();

        if (request.getStatus() != null) entity.setStatus(request.getStatus());
        if (request.getBid() != null) {
            entity.setBid(request.getBid());

            // Record bid change
            BidChangeEntity bidChange = BidChangeEntity.builder()
                    .storeId(entity.getStoreId())
                    .keywordId(entity.getId())
                    .campaignId(entity.getCampaignId())
                    .entityType("keyword")
                    .oldBid(oldBid)
                    .newBid(request.getBid())
                    .changeReason("manual")
                    .changedBy(userId != null ? UUID.fromString(userId) : null)
                    .isAutomated(false)
                    .build();
            bidChangeMapper.insert(bidChange);
        }

        entity.setUpdatedBy(userId != null ? UUID.fromString(userId) : null);
        keywordMapper.updateById(entity);
        log.info("Keyword updated: id={}", id);

        return KeywordConverter.toVo(entity);
    }
}
