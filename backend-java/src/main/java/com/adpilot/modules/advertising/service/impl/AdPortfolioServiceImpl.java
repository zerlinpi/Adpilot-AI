package com.adpilot.modules.advertising.service.impl;

import com.adpilot.common.exception.BusinessException;
import com.adpilot.common.security.CurrentUser;
import com.adpilot.common.security.DataScopeService;
import com.adpilot.common.security.ScopeTarget;
import com.adpilot.common.utils.SecurityUtils;
import com.adpilot.modules.advertising.converter.AdPortfolioConverter;
import com.adpilot.modules.advertising.dto.AdPortfolioCreateRequest;
import com.adpilot.modules.advertising.dto.AdPortfolioUpdateRequest;
import com.adpilot.modules.advertising.entity.AdPortfolioEntity;
import com.adpilot.modules.advertising.entity.CampaignEntity;
import com.adpilot.modules.advertising.mapper.AdPortfolioMapper;
import com.adpilot.modules.advertising.mapper.CampaignMapper;
import com.adpilot.modules.advertising.service.AdPortfolioService;
import com.adpilot.modules.advertising.vo.AdPortfolioVo;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class AdPortfolioServiceImpl implements AdPortfolioService {

    private final AdPortfolioMapper adPortfolioMapper;
    private final CampaignMapper campaignMapper;
    private final DataScopeService dataScopeService;

    /** Store + owner scope target for portfolios, mirroring campaigns. */
    private static final ScopeTarget PORTFOLIO_SCOPE = ScopeTarget.storeAndOwner("store_id", "created_by");

    /** Current authenticated user, or {@code null} for system/background contexts. */
    private static CurrentUser scopeUser() {
        return SecurityUtils.isAuthenticated() ? SecurityUtils.getCurrentUser() : null;
    }

    @Override
    public List<AdPortfolioVo> listPortfolios(String storeId) {
        QueryWrapper<AdPortfolioEntity> wrapper = new QueryWrapper<>();
        if (storeId != null && !storeId.isBlank()) {
            wrapper.eq("store_id", parseUuid(storeId, "storeId"));
        }
        CurrentUser user = scopeUser();
        if (user != null) {
            dataScopeService.applyScope(wrapper, PORTFOLIO_SCOPE, user);
        }
        wrapper.orderByDesc("created_at");

        List<AdPortfolioEntity> portfolios = adPortfolioMapper.selectList(wrapper);
        if (portfolios.isEmpty()) {
            return List.of();
        }

        Map<UUID, List<CampaignEntity>> membersByPortfolio = loadMembers(portfolios);
        return portfolios.stream()
                .map(p -> AdPortfolioConverter.toVo(p,
                        membersByPortfolio.getOrDefault(p.getId(), Collections.emptyList())))
                .collect(Collectors.toList());
    }

    @Override
    public AdPortfolioVo getPortfolioById(String id) {
        AdPortfolioEntity entity = adPortfolioMapper.selectById(parseUuid(id, "id"));
        if (entity == null) {
            throw new BusinessException("PORTFOLIO_NOT_FOUND", "Ad portfolio not found: " + id);
        }
        CurrentUser user = scopeUser();
        if (user != null) {
            dataScopeService.assertCanRead(entity, user);
        }
        return AdPortfolioConverter.toVo(entity, loadMembers(List.of(entity)).getOrDefault(entity.getId(), List.of()));
    }

    @Override
    @Transactional
    public AdPortfolioVo createPortfolio(AdPortfolioCreateRequest request, String userId) {
        AdPortfolioEntity entity = AdPortfolioEntity.builder()
                .storeId(parseUuid(request.getStoreId(), "storeId"))
                .name(request.getName())
                .state(request.getState() != null && !request.getState().isBlank()
                        ? request.getState() : "enabled")
                .budgetType(normalizeBudgetType(request.getBudgetType()))
                .budget(request.getBudget())
                .startDate(request.getStartDate())
                .endDate(request.getEndDate())
                .externalId(request.getExternalId())
                .createdBy(userId != null ? UUID.fromString(userId) : null)
                .build();

        adPortfolioMapper.insert(entity);
        log.info("Ad portfolio created: id={}, name={}", entity.getId(), entity.getName());
        return AdPortfolioConverter.toVo(entity, List.of());
    }

    @Override
    @Transactional
    public AdPortfolioVo updatePortfolio(String id, AdPortfolioUpdateRequest request, String userId) {
        AdPortfolioEntity entity = adPortfolioMapper.selectById(parseUuid(id, "id"));
        if (entity == null) {
            throw new BusinessException("PORTFOLIO_NOT_FOUND", "Ad portfolio not found: " + id);
        }
        CurrentUser user = scopeUser();
        if (user != null) {
            dataScopeService.assertCanWrite(entity, user);
        }

        if (request.getName() != null) entity.setName(request.getName());
        if (request.getState() != null) entity.setState(request.getState());
        if (request.getBudgetType() != null) entity.setBudgetType(normalizeBudgetType(request.getBudgetType()));
        if (request.getBudget() != null) entity.setBudget(request.getBudget());
        if (request.getStartDate() != null) entity.setStartDate(request.getStartDate());
        if (request.getEndDate() != null) entity.setEndDate(request.getEndDate());
        if (request.getExternalId() != null) entity.setExternalId(request.getExternalId());

        // Clearing the budget cap: budget_type=none means 无预算上限, so drop any stored budget.
        if ("none".equals(entity.getBudgetType())) {
            entity.setBudget(null);
        }

        adPortfolioMapper.updateById(entity);
        log.info("Ad portfolio updated: id={}", id);

        return AdPortfolioConverter.toVo(entity,
                loadMembers(List.of(entity)).getOrDefault(entity.getId(), List.of()));
    }

    /** Load member campaigns for the given portfolios, grouped by portfolio id. */
    private Map<UUID, List<CampaignEntity>> loadMembers(List<AdPortfolioEntity> portfolios) {
        List<UUID> ids = portfolios.stream()
                .map(AdPortfolioEntity::getId)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toList());
        if (ids.isEmpty()) {
            return Map.of();
        }
        QueryWrapper<CampaignEntity> wrapper = new QueryWrapper<>();
        wrapper.in("portfolio_id", ids);
        List<CampaignEntity> campaigns = campaignMapper.selectList(wrapper);

        Map<UUID, List<CampaignEntity>> grouped = new java.util.HashMap<>();
        for (CampaignEntity c : campaigns) {
            if (c.getPortfolioId() == null) {
                continue;
            }
            grouped.computeIfAbsent(c.getPortfolioId(), k -> new ArrayList<>()).add(c);
        }
        return grouped;
    }

    /** Normalize the budget type, defaulting to {@code none} (无预算上限). */
    private String normalizeBudgetType(String budgetType) {
        if (budgetType == null || budgetType.isBlank()) {
            return "none";
        }
        String bt = budgetType.trim().toLowerCase();
        return switch (bt) {
            case "none", "recurring", "date_range" -> bt;
            default -> throw new BusinessException("INVALID_BUDGET_TYPE",
                    "Unsupported budget type: " + budgetType);
        };
    }

    private UUID parseUuid(String value, String field) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException | NullPointerException ex) {
            throw new BusinessException("INVALID_ID", "Invalid " + field + ": " + value);
        }
    }
}
