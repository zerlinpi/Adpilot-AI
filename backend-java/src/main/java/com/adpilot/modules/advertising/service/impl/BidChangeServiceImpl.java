package com.adpilot.modules.advertising.service.impl;

import com.adpilot.common.api.PageResponse;
import com.adpilot.common.security.CurrentUser;
import com.adpilot.common.security.DataScopeService;
import com.adpilot.common.security.ScopeTarget;
import com.adpilot.common.utils.SecurityUtils;
import com.adpilot.modules.advertising.converter.BidChangeConverter;
import com.adpilot.modules.advertising.entity.BidChangeEntity;
import com.adpilot.modules.advertising.entity.CampaignEntity;
import com.adpilot.modules.advertising.mapper.BidChangeMapper;
import com.adpilot.modules.advertising.mapper.CampaignMapper;
import com.adpilot.modules.advertising.service.BidChangeService;
import com.adpilot.modules.advertising.vo.BidChangeVo;
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
public class BidChangeServiceImpl implements BidChangeService {

    private final BidChangeMapper bidChangeMapper;
    private final CampaignMapper campaignMapper;
    private final DataScopeService dataScopeService;

    /** Store scope target — bid_changes carries no conventional owner column (Req 24.1). */
    private static final ScopeTarget BID_CHANGE_SCOPE = ScopeTarget.store("store_id");

    /** Current authenticated user, or {@code null} for system/background contexts. */
    private static CurrentUser scopeUser() {
        return SecurityUtils.isAuthenticated() ? SecurityUtils.getCurrentUser() : null;
    }

    @Override
    public PageResponse<BidChangeVo> listBidChanges(String storeId, String campaignId, String entityType, int page, int pageSize) {
        Page<BidChangeEntity> pageParam = new Page<>(page, pageSize);
        QueryWrapper<BidChangeEntity> wrapper = new QueryWrapper<>();

        if (storeId != null && !storeId.isBlank()) {
            wrapper.eq("store_id", UUID.fromString(storeId));
        }
        if (campaignId != null && !campaignId.isBlank()) {
            wrapper.eq("campaign_id", UUID.fromString(campaignId));
        }
        if (entityType != null && !entityType.isBlank()) {
            wrapper.eq("entity_type", entityType);
        }

        // Constrain the result set to the caller's effective data scope (Req 24.1).
        CurrentUser user = scopeUser();
        if (user != null) {
            dataScopeService.applyScope(wrapper, BID_CHANGE_SCOPE, user);
        }
        wrapper.orderByDesc("created_at");

        Page<BidChangeEntity> result = bidChangeMapper.selectPage(pageParam, wrapper);
        List<BidChangeEntity> rows = result.getRecords();
        if (rows.isEmpty()) {
            return PageResponse.of(Collections.emptyList(), result.getTotal(), page, pageSize);
        }

        List<UUID> campaignIds = rows.stream()
                .map(BidChangeEntity::getCampaignId)
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());
        Map<UUID, String> campaignNames = new HashMap<>();
        if (!campaignIds.isEmpty()) {
            for (CampaignEntity c : campaignMapper.selectBatchIds(campaignIds)) {
                campaignNames.put(c.getId(), c.getName());
            }
        }

        List<BidChangeVo> voList = rows.stream().map(entity -> {
            BidChangeVo vo = BidChangeConverter.toVo(entity);
            if (entity.getCampaignId() != null) {
                vo.setCampaignName(campaignNames.get(entity.getCampaignId()));
            }
            return vo;
        }).collect(Collectors.toList());

        return PageResponse.of(voList, result.getTotal(), page, pageSize);
    }
}
