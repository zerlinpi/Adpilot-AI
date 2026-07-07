package com.adpilot.modules.advertising.service.impl;

import com.adpilot.common.exception.BusinessException;
import com.adpilot.common.security.CurrentUser;
import com.adpilot.common.security.DataScopeService;
import com.adpilot.common.security.ScopeTarget;
import com.adpilot.common.utils.SecurityUtils;
import com.adpilot.modules.advertising.converter.AdPlacementLockConverter;
import com.adpilot.modules.advertising.dto.AdPlacementLockCreateRequest;
import com.adpilot.modules.advertising.entity.AdPlacementLockStrategyEntity;
import com.adpilot.modules.advertising.entity.AdPlacementLockTaskEntity;
import com.adpilot.modules.advertising.entity.CampaignEntity;
import com.adpilot.modules.advertising.entity.KeywordEntity;
import com.adpilot.modules.advertising.mapper.AdPlacementLockStrategyMapper;
import com.adpilot.modules.advertising.mapper.AdPlacementLockTaskMapper;
import com.adpilot.modules.advertising.mapper.CampaignMapper;
import com.adpilot.modules.advertising.mapper.KeywordMapper;
import com.adpilot.modules.advertising.service.AdPlacementLockService;
import com.adpilot.modules.advertising.vo.AdPlacementLockStrategyVo;
import com.adpilot.modules.advertising.vo.AdPlacementLockTaskVo;
import com.adpilot.modules.advertising.vo.PlacementLockAmsVo;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Default {@link AdPlacementLockService}. Strategies and their derived tasks are
 * store-scoped via {@link DataScopeService}, mirroring {@code AdPortfolioServiceImpl}.
 * The bid-range invariant {@code bidMin <= bidMax} is enforced on create (Req 26.4).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdPlacementLockServiceImpl implements AdPlacementLockService {

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final AdPlacementLockStrategyMapper strategyMapper;
    private final AdPlacementLockTaskMapper taskMapper;
    private final CampaignMapper campaignMapper;
    private final KeywordMapper keywordMapper;
    private final DataScopeService dataScopeService;

    /** Store + owner scope target for strategies, mirroring campaigns/portfolios. */
    private static final ScopeTarget LOCK_SCOPE = ScopeTarget.storeAndOwner("store_id", "created_by");

    private static CurrentUser scopeUser() {
        return SecurityUtils.isAuthenticated() ? SecurityUtils.getCurrentUser() : null;
    }

    @Override
    public List<AdPlacementLockStrategyVo> listStrategies(String storeId) {
        List<AdPlacementLockStrategyEntity> strategies = loadScopedStrategies(storeId);
        if (strategies.isEmpty()) {
            return List.of();
        }
        Map<UUID, String> campaignNames = resolveCampaignNames(strategies);
        return strategies.stream()
                .map(s -> AdPlacementLockConverter.toVo(s, campaignNames))
                .collect(Collectors.toList());
    }

    @Override
    @Transactional
    public AdPlacementLockStrategyVo createStrategy(AdPlacementLockCreateRequest request, String userId) {
        if (request.getBidMin() == null || request.getBidMax() == null
                || request.getBidMin().compareTo(request.getBidMax()) > 0) {
            // Req 26.4: reject an invalid bid range (minimum exceeds maximum).
            throw new BusinessException("INVALID_BID_RANGE",
                    "Minimum bid must not exceed maximum bid");
        }

        UUID campaignId = parseUuid(request.getCampaignId(), "campaignId");
        CampaignEntity campaign = campaignMapper.selectById(campaignId);
        if (campaign == null) {
            throw new BusinessException("CAMPAIGN_NOT_FOUND", "Campaign not found: " + request.getCampaignId());
        }

        AdPlacementLockStrategyEntity entity = AdPlacementLockStrategyEntity.builder()
                .storeId(parseUuid(request.getStoreId(), "storeId"))
                .campaignId(campaignId)
                .targetPlacement(request.getTargetPlacement())
                .bidMin(request.getBidMin())
                .bidMax(request.getBidMax())
                .status("active")
                .createdBy(userId != null ? UUID.fromString(userId) : null)
                .build();

        strategyMapper.insert(entity);
        log.info("Ad placement lock strategy created: id={}, placement={}",
                entity.getId(), entity.getTargetPlacement());

        return AdPlacementLockConverter.toVo(entity,
                Map.of(campaignId, campaign.getName() != null ? campaign.getName() : ""));
    }

    @Override
    public List<AdPlacementLockTaskVo> listTasks(String storeId) {
        List<AdPlacementLockStrategyEntity> strategies = loadScopedStrategies(storeId);
        if (strategies.isEmpty()) {
            return List.of();
        }
        Map<UUID, AdPlacementLockStrategyEntity> strategyById = strategies.stream()
                .collect(Collectors.toMap(AdPlacementLockStrategyEntity::getId, s -> s));

        List<AdPlacementLockTaskEntity> tasks = taskMapper.selectList(
                new QueryWrapper<AdPlacementLockTaskEntity>()
                        .in("strategy_id", strategyById.keySet())
                        .orderByDesc("created_at"));
        if (tasks.isEmpty()) {
            return List.of();
        }

        Map<UUID, String> campaignNames = resolveCampaignNames(strategies);
        Map<UUID, String> keywordTexts = resolveKeywordTexts(tasks);

        return tasks.stream()
                .map(t -> AdPlacementLockConverter.toVo(
                        t, strategyById.get(t.getStrategyId()), campaignNames, keywordTexts))
                .collect(Collectors.toList());
    }

    @Override
    public List<PlacementLockAmsVo> listAmsData(String storeId) {
        List<AdPlacementLockStrategyEntity> strategies = loadScopedStrategies(storeId);
        if (strategies.isEmpty()) {
            return List.of();
        }
        Map<UUID, AdPlacementLockStrategyEntity> strategyById = strategies.stream()
                .collect(Collectors.toMap(AdPlacementLockStrategyEntity::getId, s -> s));

        List<AdPlacementLockTaskEntity> tasks = taskMapper.selectList(
                new QueryWrapper<AdPlacementLockTaskEntity>()
                        .in("strategy_id", strategyById.keySet())
                        .orderByDesc("last_run_at"));

        Map<UUID, String> campaignNames = resolveCampaignNames(strategies);
        Map<UUID, String> keywordTexts = resolveKeywordTexts(tasks);

        // AMS real-time data is stubbed from the latest enforcement task per strategy.
        List<PlacementLockAmsVo> rows = new ArrayList<>();
        for (AdPlacementLockTaskEntity task : tasks) {
            AdPlacementLockStrategyEntity strategy = strategyById.get(task.getStrategyId());
            if (strategy == null) {
                continue;
            }
            rows.add(PlacementLockAmsVo.builder()
                    .strategyId(idStr(strategy.getId()))
                    .campaignName(campaignNames.get(strategy.getCampaignId()))
                    .targetPlacement(strategy.getTargetPlacement())
                    .keywordText(keywordTexts.get(task.getKeywordId()))
                    .currentBid(task.getLastBid() != null ? task.getLastBid().doubleValue() : null)
                    .bidMin(strategy.getBidMin() != null ? strategy.getBidMin().doubleValue() : null)
                    .bidMax(strategy.getBidMax() != null ? strategy.getBidMax().doubleValue() : null)
                    .status(strategy.getStatus())
                    .observedAt(task.getLastRunAt() != null ? task.getLastRunAt().format(FORMATTER) : null)
                    .build());
        }
        return rows;
    }

    // ----- helpers -----

    private List<AdPlacementLockStrategyEntity> loadScopedStrategies(String storeId) {
        QueryWrapper<AdPlacementLockStrategyEntity> wrapper = new QueryWrapper<>();
        if (storeId != null && !storeId.isBlank()) {
            wrapper.eq("store_id", parseUuid(storeId, "storeId"));
        }
        CurrentUser user = scopeUser();
        if (user != null) {
            dataScopeService.applyScope(wrapper, LOCK_SCOPE, user);
        }
        wrapper.orderByDesc("created_at");
        return strategyMapper.selectList(wrapper);
    }

    private Map<UUID, String> resolveCampaignNames(List<AdPlacementLockStrategyEntity> strategies) {
        List<UUID> campaignIds = strategies.stream()
                .map(AdPlacementLockStrategyEntity::getCampaignId)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());
        if (campaignIds.isEmpty()) {
            return Collections.emptyMap();
        }
        List<CampaignEntity> campaigns = campaignMapper.selectBatchIds(campaignIds);
        return campaigns.stream()
                .filter(c -> c.getId() != null)
                .collect(Collectors.toMap(CampaignEntity::getId,
                        c -> c.getName() != null ? c.getName() : "", (a, b) -> a));
    }

    private Map<UUID, String> resolveKeywordTexts(List<AdPlacementLockTaskEntity> tasks) {
        List<UUID> keywordIds = tasks.stream()
                .map(AdPlacementLockTaskEntity::getKeywordId)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());
        if (keywordIds.isEmpty()) {
            return Collections.emptyMap();
        }
        List<KeywordEntity> keywords = keywordMapper.selectBatchIds(keywordIds);
        return keywords.stream()
                .filter(k -> k.getId() != null)
                .collect(Collectors.toMap(KeywordEntity::getId,
                        k -> k.getKeywordText() != null ? k.getKeywordText() : "", (a, b) -> a));
    }

    private static String idStr(UUID id) {
        return id != null ? id.toString() : null;
    }

    private UUID parseUuid(String value, String field) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException | NullPointerException ex) {
            throw new BusinessException("INVALID_ID", "Invalid " + field + ": " + value);
        }
    }
}
