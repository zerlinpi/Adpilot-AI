package com.adpilot.modules.advertising.service.impl;

import com.adpilot.common.api.PageResponse;
import com.adpilot.common.security.CurrentUser;
import com.adpilot.common.security.DataScopeService;
import com.adpilot.common.security.ScopeTarget;
import com.adpilot.common.utils.SecurityUtils;
import com.adpilot.modules.advertising.converter.NegativeKeywordConverter;
import com.adpilot.modules.advertising.entity.CampaignEntity;
import com.adpilot.modules.advertising.entity.NegativeKeywordEntity;
import com.adpilot.modules.advertising.mapper.CampaignMapper;
import com.adpilot.modules.advertising.mapper.NegativeKeywordMapper;
import com.adpilot.modules.advertising.service.NegativeKeywordService;
import com.adpilot.modules.advertising.vo.NegativeKeywordVo;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class NegativeKeywordServiceImpl implements NegativeKeywordService {

    private final NegativeKeywordMapper negativeKeywordMapper;
    private final CampaignMapper campaignMapper;
    private final DataScopeService dataScopeService;

    /** Store + owner scope target for negative keywords (Req 24.1). */
    private static final ScopeTarget NEGATIVE_KEYWORD_SCOPE = ScopeTarget.storeAndOwner("store_id", "created_by");

    /** Current authenticated user, or {@code null} for system/background contexts. */
    private static CurrentUser scopeUser() {
        return SecurityUtils.isAuthenticated() ? SecurityUtils.getCurrentUser() : null;
    }

    @Override
    public PageResponse<NegativeKeywordVo> listNegativeKeywords(String storeId, String campaignId, String level, int page, int pageSize) {
        Page<NegativeKeywordEntity> pageParam = new Page<>(page, pageSize);
        QueryWrapper<NegativeKeywordEntity> wrapper = new QueryWrapper<>();

        if (storeId != null && !storeId.isBlank()) {
            wrapper.eq("store_id", UUID.fromString(storeId));
        }
        if (campaignId != null && !campaignId.isBlank()) {
            wrapper.eq("campaign_id", UUID.fromString(campaignId));
        }
        if (level != null && !level.isBlank()) {
            wrapper.eq("level", level);
        }

        // Constrain the result set to the caller's effective data scope (Req 24.1).
        CurrentUser user = scopeUser();
        if (user != null) {
            dataScopeService.applyScope(wrapper, NEGATIVE_KEYWORD_SCOPE, user);
        }
        wrapper.orderByDesc("created_at");

        Page<NegativeKeywordEntity> result = negativeKeywordMapper.selectPage(pageParam, wrapper);
        List<NegativeKeywordEntity> rows = result.getRecords();
        if (rows.isEmpty()) {
            return PageResponse.of(Collections.emptyList(), result.getTotal(), page, pageSize);
        }

        List<UUID> campaignIds = rows.stream()
                .map(NegativeKeywordEntity::getCampaignId)
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());
        Map<UUID, String> campaignNames = new HashMap<>();
        if (!campaignIds.isEmpty()) {
            for (CampaignEntity c : campaignMapper.selectBatchIds(campaignIds)) {
                campaignNames.put(c.getId(), c.getName());
            }
        }

        List<NegativeKeywordVo> voList = rows.stream().map(entity -> {
            NegativeKeywordVo vo = NegativeKeywordConverter.toVo(entity);
            if (entity.getCampaignId() != null) {
                vo.setCampaignName(campaignNames.get(entity.getCampaignId()));
            }
            return vo;
        }).collect(Collectors.toList());

        return PageResponse.of(voList, result.getTotal(), page, pageSize);
    }
}
