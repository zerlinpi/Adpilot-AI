package com.adpilot.modules.advertising.service.impl;

import com.adpilot.common.api.PageResponse;
import com.adpilot.common.exception.BusinessException;
import com.adpilot.common.security.CurrentUser;
import com.adpilot.common.security.DataScopeService;
import com.adpilot.common.security.ScopeTarget;
import com.adpilot.common.utils.SecurityUtils;
import com.adpilot.modules.advertising.converter.RecommendationConverter;
import com.adpilot.modules.advertising.entity.KeywordEntity;
import com.adpilot.modules.advertising.entity.RecommendationEntity;
import com.adpilot.modules.advertising.entity.TargetEntity;
import com.adpilot.modules.advertising.mapper.KeywordMapper;
import com.adpilot.modules.advertising.mapper.RecommendationMapper;
import com.adpilot.modules.advertising.mapper.TargetMapper;
import com.adpilot.modules.advertising.service.RecommendationService;
import com.adpilot.modules.advertising.vo.RecommendationVo;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class RecommendationServiceImpl implements RecommendationService {

    private final RecommendationMapper recommendationMapper;
    private final KeywordMapper keywordMapper;
    private final TargetMapper targetMapper;
    private final DataScopeService dataScopeService;

    /** Store scope target — recommendations carries no conventional owner column (Req 24.1). */
    private static final ScopeTarget RECOMMENDATION_SCOPE = ScopeTarget.store("store_id");

    /** Current authenticated user, or {@code null} for system/background contexts. */
    private static CurrentUser scopeUser() {
        return SecurityUtils.isAuthenticated() ? SecurityUtils.getCurrentUser() : null;
    }

    @Override
    public PageResponse<RecommendationVo> listRecommendations(String storeId, String status, String type, int page, int pageSize) {
        Page<RecommendationEntity> pageParam = new Page<>(page, pageSize);
        QueryWrapper<RecommendationEntity> wrapper = new QueryWrapper<>();

        if (storeId != null && !storeId.isBlank()) {
            wrapper.eq("store_id", UUID.fromString(storeId));
        }
        if (status != null && !status.isBlank()) {
            wrapper.eq("status", status);
        }
        if (type != null && !type.isBlank()) {
            wrapper.eq("type", type);
        }

        // Constrain the result set to the caller's effective data scope (Req 24.1).
        CurrentUser user = scopeUser();
        if (user != null) {
            dataScopeService.applyScope(wrapper, RECOMMENDATION_SCOPE, user);
        }
        wrapper.orderByDesc("created_at");

        Page<RecommendationEntity> result = recommendationMapper.selectPage(pageParam, wrapper);
        List<RecommendationVo> voList = result.getRecords().stream()
                .map(RecommendationConverter::toVo)
                .collect(Collectors.toList());

        return PageResponse.of(voList, result.getTotal(), page, pageSize);
    }

    @Override
    @Transactional
    public RecommendationVo applyRecommendation(String id, String userId) {
        RecommendationEntity entity = recommendationMapper.selectById(UUID.fromString(id));
        if (entity == null) {
            throw new BusinessException("RECOMMENDATION_NOT_FOUND", "Recommendation not found: " + id);
        }

        // Per-record ownership: the referenced Recommendation must belong to a Store
        // within the caller's effective data scope before it can be applied (Req 25.1/25.2).
        CurrentUser applyUser = scopeUser();
        if (applyUser != null) {
            dataScopeService.assertCanWrite(entity, applyUser);
        }

        // Apply the recommendation based on type
        switch (entity.getType()) {
            case "decrease_bid":
            case "increase_bid":
                applyBidChange(entity);
                break;
            case "add_negative":
                applyNegativeKeyword(entity);
                break;
            case "add_exact":
                applyExactKeyword(entity);
                break;
            case "increase_budget":
                applyBudgetChange(entity);
                break;
            case "pause_target":
                applyPauseTarget(entity);
                break;
            default:
                log.warn("Unknown recommendation type: {}", entity.getType());
        }

        entity.setStatus("applied");
        entity.setAppliedAt(LocalDateTime.now());
        entity.setAppliedBy(userId != null ? UUID.fromString(userId) : null);
        recommendationMapper.updateById(entity);

        log.info("Recommendation applied: id={}, type={}", id, entity.getType());
        return RecommendationConverter.toVo(entity);
    }

    @Override
    @Transactional
    public RecommendationVo dismissRecommendation(String id, String userId) {
        RecommendationEntity entity = recommendationMapper.selectById(UUID.fromString(id));
        if (entity == null) {
            throw new BusinessException("RECOMMENDATION_NOT_FOUND", "Recommendation not found: " + id);
        }

        CurrentUser dismissUser = scopeUser();
        if (dismissUser != null) {
            dataScopeService.assertCanWrite(entity, dismissUser);
        }

        entity.setStatus("dismissed");
        entity.setDismissedAt(LocalDateTime.now());
        recommendationMapper.updateById(entity);

        log.info("Recommendation dismissed: id={}", id);
        return RecommendationConverter.toVo(entity);
    }

    @Override
    @Transactional
    public RecommendationVo watchRecommendation(String id, String userId) {
        RecommendationEntity entity = recommendationMapper.selectById(UUID.fromString(id));
        if (entity == null) {
            throw new BusinessException("RECOMMENDATION_NOT_FOUND", "Recommendation not found: " + id);
        }

        CurrentUser watchUser = scopeUser();
        if (watchUser != null) {
            dataScopeService.assertCanWrite(entity, watchUser);
        }

        entity.setStatus("watching");
        recommendationMapper.updateById(entity);

        log.info("Recommendation set to watching: id={}", id);
        return RecommendationConverter.toVo(entity);
    }

    private void applyBidChange(RecommendationEntity recommendation) {
        if (recommendation.getKeywordId() != null) {
            KeywordEntity keyword = keywordMapper.selectById(recommendation.getKeywordId());
            if (keyword != null && recommendation.getRecommendedValue() != null) {
                keyword.setBid(new BigDecimal(recommendation.getRecommendedValue()));
                keywordMapper.updateById(keyword);
            }
        } else if (recommendation.getTargetId() != null) {
            TargetEntity target = targetMapper.selectById(recommendation.getTargetId());
            if (target != null && recommendation.getRecommendedValue() != null) {
                target.setBid(new BigDecimal(recommendation.getRecommendedValue()));
                targetMapper.updateById(target);
            }
        }
    }

    private void applyNegativeKeyword(RecommendationEntity recommendation) {
        // This would create a negative keyword - simplified for now
        log.info("Applying negative keyword for recommendation {}", recommendation.getId());
    }

    private void applyExactKeyword(RecommendationEntity recommendation) {
        // This would create an exact match keyword - simplified for now
        log.info("Applying exact keyword for recommendation {}", recommendation.getId());
    }

    private void applyBudgetChange(RecommendationEntity recommendation) {
        // This would update campaign budget - simplified for now
        log.info("Applying budget change for recommendation {}", recommendation.getId());
    }

    private void applyPauseTarget(RecommendationEntity recommendation) {
        if (recommendation.getTargetId() != null) {
            TargetEntity target = targetMapper.selectById(recommendation.getTargetId());
            if (target != null) {
                target.setStatus("paused");
                targetMapper.updateById(target);
            }
        }
    }
}
