package com.adpilot.modules.advertising.service.impl;

import com.adpilot.common.api.PageResponse;
import com.adpilot.common.exception.BusinessException;
import com.adpilot.common.security.CurrentUser;
import com.adpilot.common.security.DataScopeService;
import com.adpilot.common.security.ScopeTarget;
import com.adpilot.common.utils.SecurityUtils;
import com.adpilot.modules.advertising.converter.GoalConverter;
import com.adpilot.modules.advertising.dto.GoalCreateRequest;
import com.adpilot.modules.advertising.dto.GoalUpdateRequest;
import com.adpilot.modules.advertising.converter.CampaignConverter;
import com.adpilot.modules.advertising.entity.CampaignEntity;
import com.adpilot.modules.advertising.entity.GoalEntity;
import com.adpilot.modules.advertising.mapper.CampaignMapper;
import com.adpilot.modules.advertising.mapper.GoalMapper;
import com.adpilot.modules.advertising.service.CampaignBuilderService;
import com.adpilot.modules.advertising.service.GoalMetricsService;
import com.adpilot.modules.advertising.service.GoalService;
import com.adpilot.modules.advertising.support.GoalMetrics;
import com.adpilot.modules.advertising.support.StoreScopeRef;
import com.adpilot.modules.advertising.vo.CampaignVo;
import com.adpilot.modules.advertising.vo.GoalVo;
import com.adpilot.modules.advertising.vo.PerformanceSummaryVo;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
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
public class GoalServiceImpl implements GoalService {

    private final GoalMapper goalMapper;
    private final CampaignMapper campaignMapper;
    private final CampaignBuilderService campaignBuilderService;
    private final GoalMetricsService goalMetricsService;
    private final ObjectMapper objectMapper;
    private final DataScopeService dataScopeService;

    /** Store + owner scope target for goals (Req 24.1). */
    private static final ScopeTarget GOAL_SCOPE = ScopeTarget.storeAndOwner("store_id", "created_by");

    /** Current authenticated user, or {@code null} for system/background contexts. */
    private static CurrentUser scopeUser() {
        return SecurityUtils.isAuthenticated() ? SecurityUtils.getCurrentUser() : null;
    }

    /**
     * Assert the caller owns the given Store before acting on a store-keyed
     * resource (Req 25.3); out-of-scope or guessed store ids are rejected with a
     * 403 without disclosing contents.
     */
    private void assertStoreOwnership(UUID storeUuid) {
        CurrentUser user = scopeUser();
        if (user != null) {
            dataScopeService.assertCanWrite(StoreScopeRef.of(storeUuid), user);
        }
    }

    @Override
    public PageResponse<GoalVo> listGoals(String storeId, int page, int pageSize) {
        Page<GoalEntity> pageParam = new Page<>(page, pageSize);
        QueryWrapper<GoalEntity> wrapper = new QueryWrapper<>();

        if (storeId != null && !storeId.isBlank()) {
            wrapper.eq("store_id", UUID.fromString(storeId));
        }

        // Constrain the result set to the caller's effective data scope (Req 24.1).
        CurrentUser user = scopeUser();
        if (user != null) {
            dataScopeService.applyScope(wrapper, GOAL_SCOPE, user);
        }
        wrapper.orderByDesc("created_at");

        Page<GoalEntity> result = goalMapper.selectPage(pageParam, wrapper);
        List<GoalVo> voList = result.getRecords().stream()
                .map(entity -> {
                    GoalMetrics metrics = goalMetricsService.computeGoalMetrics(entity.getId());
                    PerformanceSummaryVo performance = GoalMetricsService.toPerformanceSummary(metrics);
                    return GoalConverter.toVo(entity, fetchGoalCampaigns(entity.getId()), performance);
                })
                .collect(Collectors.toList());

        return PageResponse.of(voList, result.getTotal(), page, pageSize);
    }

    @Override
    public GoalVo getGoalById(String id) {
        GoalEntity entity = goalMapper.selectById(UUID.fromString(id));
        if (entity == null) {
            throw new BusinessException("GOAL_NOT_FOUND", "Goal not found: " + id);
        }
        CurrentUser user = scopeUser();
        if (user != null) {
            dataScopeService.assertCanRead(entity, user);
        }
        GoalMetrics metrics = goalMetricsService.computeGoalMetrics(entity.getId());
        PerformanceSummaryVo performance = GoalMetricsService.toPerformanceSummary(metrics);
        return GoalConverter.toVo(entity, fetchGoalCampaigns(entity.getId()), performance);
    }

    @Override
    @Transactional
    public GoalVo createGoal(GoalCreateRequest request, String userId) {
        UUID storeUuid = UUID.fromString(request.getStoreId());
        // The new Goal is created under the requested Store; reject when the caller
        // does not own that Store (Req 25.3).
        assertStoreOwnership(storeUuid);

        GoalEntity entity = GoalEntity.builder()
                .storeId(storeUuid)
                .name(request.getName())
                .type(request.getType())
                .status("active")
                .targetAcos(request.getTargetAcos())
                .dailyBudget(request.getDailyBudget())
                .maxCpc(request.getMaxCpc())
                .minBid(request.getMinBid())
                .maxBid(request.getMaxBid())
                .brandKeywords(toJson(request.getBrandKeywords()))
                .categoryKeywords(toJson(request.getCategoryKeywords()))
                .competitorBrands(toJson(request.getCompetitorBrands()))
                .competitorAsins(toJson(request.getCompetitorAsins()))
                .autoNegate(request.getAutoNegate() != null ? request.getAutoNegate() : false)
                .autoBid(request.getAutoBid() != null ? request.getAutoBid() : true)
                .autoExpand(request.getAutoExpand() != null ? request.getAutoExpand() : false)
                .optimizeFrequency(request.getOptimizeFrequency())
                .riskPreference(request.getRiskPreference())
                .productIds(toJson(request.getProductIds()))
                .createdBy(userId != null ? UUID.fromString(userId) : null)
                .updatedBy(userId != null ? UUID.fromString(userId) : null)
                .build();

        goalMapper.insert(entity);
        log.info("Goal created: id={}, name={}", entity.getId(), entity.getName());

        // Auto-generate campaigns from goal
        try {
            List<String> campaignIds = campaignBuilderService.buildCampaignsFromGoal(entity, request.getStoreId());
            log.info("Auto-generated {} campaigns for goal {}", campaignIds.size(), entity.getId());
        } catch (Exception e) {
            log.error("Failed to auto-generate campaigns for goal {}", entity.getId(), e);
        }

        return GoalConverter.toVo(entity, fetchGoalCampaigns(entity.getId()), null);
    }

    @Override
    @Transactional
    public GoalVo updateGoal(String id, GoalUpdateRequest request, String userId) {
        GoalEntity entity = goalMapper.selectById(UUID.fromString(id));
        if (entity == null) {
            throw new BusinessException("GOAL_NOT_FOUND", "Goal not found: " + id);
        }

        CurrentUser user = scopeUser();
        if (user != null) {
            dataScopeService.assertCanWrite(entity, user);
        }

        if (request.getName() != null) entity.setName(request.getName());
        if (request.getType() != null) entity.setType(request.getType());
        if (request.getStatus() != null) entity.setStatus(request.getStatus());
        if (request.getTargetAcos() != null) entity.setTargetAcos(request.getTargetAcos());
        if (request.getDailyBudget() != null) entity.setDailyBudget(request.getDailyBudget());
        if (request.getMaxCpc() != null) entity.setMaxCpc(request.getMaxCpc());
        if (request.getMinBid() != null) entity.setMinBid(request.getMinBid());
        if (request.getMaxBid() != null) entity.setMaxBid(request.getMaxBid());
        if (request.getBrandKeywords() != null) entity.setBrandKeywords(toJson(request.getBrandKeywords()));
        if (request.getCategoryKeywords() != null) entity.setCategoryKeywords(toJson(request.getCategoryKeywords()));
        if (request.getCompetitorBrands() != null) entity.setCompetitorBrands(toJson(request.getCompetitorBrands()));
        if (request.getCompetitorAsins() != null) entity.setCompetitorAsins(toJson(request.getCompetitorAsins()));
        if (request.getAutoNegate() != null) entity.setAutoNegate(request.getAutoNegate());
        if (request.getAutoBid() != null) entity.setAutoBid(request.getAutoBid());
        if (request.getAutoExpand() != null) entity.setAutoExpand(request.getAutoExpand());
        if (request.getOptimizeFrequency() != null) entity.setOptimizeFrequency(request.getOptimizeFrequency());
        if (request.getRiskPreference() != null) entity.setRiskPreference(request.getRiskPreference());
        if (request.getProductIds() != null) entity.setProductIds(toJson(request.getProductIds()));

        entity.setUpdatedBy(userId != null ? UUID.fromString(userId) : null);
        goalMapper.updateById(entity);
        log.info("Goal updated: id={}", id);

        // Req 20.2/20.3: propagate ONLY target ACoS + optimization goal to associated campaigns,
        // never the name/budget/personality default, and never overwrite a campaign-level override.
        goalMetricsService.propagateOnUpdate(entity, userId);

        return GoalConverter.toVo(entity, fetchGoalCampaigns(entity.getId()), null);
    }

    @Override
    @Transactional
    public void deleteGoal(String id, String userId) {
        GoalEntity entity = goalMapper.selectById(UUID.fromString(id));
        if (entity == null) {
            throw new BusinessException("GOAL_NOT_FOUND", "Goal not found: " + id);
        }
        CurrentUser user = scopeUser();
        if (user != null) {
            dataScopeService.assertCanWrite(entity, user);
        }
        goalMapper.deleteById(UUID.fromString(id));
        log.info("Goal deleted: id={}", id);
    }

    private int countCampaigns(UUID goalId) {
        LambdaQueryWrapper<CampaignEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(CampaignEntity::getGoalId, goalId);
        Long count = campaignMapper.selectCount(wrapper);
        return count != null ? count.intValue() : 0;
    }

    /**
     * Load the Campaigns associated with the Goal as {@link CampaignVo}s so the {@link GoalVo} can
     * expose its {@code campaigns} collection (Req 14.4). {@code GoalVo.campaignCount} is derived from
     * this collection, keeping the count consistent with the collection length (Property 33).
     */
    private List<CampaignVo> fetchGoalCampaigns(UUID goalId) {
        if (goalId == null) {
            return List.of();
        }
        LambdaQueryWrapper<CampaignEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(CampaignEntity::getGoalId, goalId);
        List<CampaignEntity> campaigns = campaignMapper.selectList(wrapper);
        if (campaigns == null) {
            return List.of();
        }
        return campaigns.stream()
                .map(CampaignConverter::toVo)
                .collect(Collectors.toList());
    }

    private PerformanceSummaryVo buildPerformanceSummary(UUID storeId) {
        // Aggregate performance from campaigns for this store
        LambdaQueryWrapper<CampaignEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(CampaignEntity::getStoreId, storeId);
        List<CampaignEntity> campaigns = campaignMapper.selectList(wrapper);

        double totalSpend = 0, totalSales = 0;
        int totalOrders = 0, totalClicks = 0;
        long totalImpressions = 0;

        for (CampaignEntity c : campaigns) {
            totalSpend += c.getSpend() != null ? c.getSpend().doubleValue() : 0;
            totalSales += c.getSales() != null ? c.getSales().doubleValue() : 0;
            totalOrders += c.getOrders() != null ? c.getOrders() : 0;
            totalClicks += c.getClicks() != null ? c.getClicks() : 0;
            totalImpressions += c.getImpressions() != null ? c.getImpressions() : 0;
        }

        double acos = totalSales > 0 ? (totalSpend / totalSales) * 100 : 0;
        double roas = totalSpend > 0 ? totalSales / totalSpend : 0;
        double cvr = totalClicks > 0 ? (double) totalOrders / totalClicks : 0;
        double avgCpc = totalClicks > 0 ? totalSpend / totalClicks : 0;

        return PerformanceSummaryVo.builder()
                .spend(totalSpend)
                .sales(totalSales)
                .orders(totalOrders)
                .impressions(totalImpressions)
                .clicks(totalClicks)
                .acos(acos)
                .roas(roas)
                .conversionRate(cvr)
                .avgCpc(avgCpc)
                .build();
    }

    private String toJson(List<String> list) {
        if (list == null) return null;
        try {
            return objectMapper.writeValueAsString(list);
        } catch (Exception e) {
            return "[]";
        }
    }
}
