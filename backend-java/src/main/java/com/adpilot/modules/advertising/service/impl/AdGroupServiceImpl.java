package com.adpilot.modules.advertising.service.impl;

import com.adpilot.common.api.PageResponse;
import com.adpilot.common.security.CurrentUser;
import com.adpilot.common.security.DataScopeService;
import com.adpilot.common.security.ScopeTarget;
import com.adpilot.common.utils.SecurityUtils;
import com.adpilot.modules.advertising.converter.AdGroupConverter;
import com.adpilot.modules.advertising.entity.AdGroupEntity;
import com.adpilot.modules.advertising.entity.CampaignEntity;
import com.adpilot.modules.advertising.entity.KeywordEntity;
import com.adpilot.modules.advertising.mapper.AdGroupMapper;
import com.adpilot.modules.advertising.mapper.CampaignMapper;
import com.adpilot.modules.advertising.mapper.KeywordMapper;
import com.adpilot.modules.advertising.service.AdGroupService;
import com.adpilot.modules.advertising.vo.AdGroupVo;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class AdGroupServiceImpl implements AdGroupService {

    private final AdGroupMapper adGroupMapper;
    private final KeywordMapper keywordMapper;
    private final CampaignMapper campaignMapper;
    private final DataScopeService dataScopeService;

    /** Store + owner scope target for ad groups (Req 24.1). */
    private static final ScopeTarget AD_GROUP_SCOPE = ScopeTarget.storeAndOwner("store_id", "created_by");

    /** Current authenticated user, or {@code null} for system/background contexts. */
    private static CurrentUser scopeUser() {
        return SecurityUtils.isAuthenticated() ? SecurityUtils.getCurrentUser() : null;
    }

    @Override
    public PageResponse<AdGroupVo> listAdGroups(String storeId, String campaignId, String status, int page, int pageSize) {
        Page<AdGroupEntity> pageParam = new Page<>(page, pageSize);
        QueryWrapper<AdGroupEntity> wrapper = new QueryWrapper<>();

        if (storeId != null && !storeId.isBlank()) {
            wrapper.eq("store_id", UUID.fromString(storeId));
        }
        if (campaignId != null && !campaignId.isBlank()) {
            wrapper.eq("campaign_id", UUID.fromString(campaignId));
        }
        if (status != null && !status.isBlank()) {
            wrapper.eq("status", status);
        }

        // Constrain the result set to the caller's effective data scope (Req 24.1).
        CurrentUser user = scopeUser();
        if (user != null) {
            dataScopeService.applyScope(wrapper, AD_GROUP_SCOPE, user);
        }
        wrapper.orderByDesc("created_at");

        Page<AdGroupEntity> result = adGroupMapper.selectPage(pageParam, wrapper);
        List<AdGroupEntity> rows = result.getRecords();
        if (rows.isEmpty()) {
            return PageResponse.of(Collections.emptyList(), result.getTotal(), page, pageSize);
        }

        // Resolve parent campaign names in one batch.
        List<UUID> campaignIds = rows.stream()
                .map(AdGroupEntity::getCampaignId)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());
        Map<UUID, String> campaignNames = new HashMap<>();
        if (!campaignIds.isEmpty()) {
            for (CampaignEntity c : campaignMapper.selectBatchIds(campaignIds)) {
                campaignNames.put(c.getId(), c.getName());
            }
        }

        // Aggregate keyword count and performance per ad group from the keywords
        // table (the ad group's children carry the reliable metrics).
        List<UUID> adGroupIds = rows.stream().map(AdGroupEntity::getId).collect(Collectors.toList());
        LambdaQueryWrapper<KeywordEntity> kwWrapper = new LambdaQueryWrapper<>();
        kwWrapper.in(KeywordEntity::getAdGroupId, adGroupIds);
        List<KeywordEntity> keywords = keywordMapper.selectList(kwWrapper);
        Map<UUID, Agg> aggByAdGroup = new HashMap<>();
        for (KeywordEntity kw : keywords) {
            Agg agg = aggByAdGroup.computeIfAbsent(kw.getAdGroupId(), k -> new Agg());
            agg.keywordCount++;
            agg.spend = agg.spend.add(kw.getSpend() != null ? kw.getSpend() : BigDecimal.ZERO);
            agg.sales = agg.sales.add(kw.getSales() != null ? kw.getSales() : BigDecimal.ZERO);
            agg.orders += kw.getOrders() != null ? kw.getOrders() : 0;
        }

        List<AdGroupVo> voList = rows.stream().map(entity -> {
            AdGroupVo vo = AdGroupConverter.toVo(entity);
            vo.setCampaignName(campaignNames.get(entity.getCampaignId()));
            Agg agg = aggByAdGroup.get(entity.getId());
            if (agg != null) {
                vo.setKeywordCount(agg.keywordCount);
                vo.setSpend(agg.spend.doubleValue());
                vo.setSales(agg.sales.doubleValue());
                vo.setOrders(agg.orders);
                double salesVal = agg.sales.doubleValue();
                vo.setAcos(salesVal > 0 ? (agg.spend.doubleValue() / salesVal) * 100 : 0);
            }
            return vo;
        }).collect(Collectors.toList());

        return PageResponse.of(voList, result.getTotal(), page, pageSize);
    }

    /** Mutable per-ad-group accumulator for keyword-derived metrics. */
    private static final class Agg {
        int keywordCount = 0;
        BigDecimal spend = BigDecimal.ZERO;
        BigDecimal sales = BigDecimal.ZERO;
        int orders = 0;
    }
}
