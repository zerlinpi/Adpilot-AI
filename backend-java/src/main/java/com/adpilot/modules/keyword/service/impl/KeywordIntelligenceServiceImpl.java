package com.adpilot.modules.keyword.service.impl;

import com.adpilot.common.api.PageResponse;
import com.adpilot.common.exception.BusinessException;
import com.adpilot.modules.audit.service.AuditLogService;
import com.adpilot.modules.keyword.converter.KeywordInsightConverter;
import com.adpilot.modules.keyword.dto.KeywordInsightQueryRequest;
import com.adpilot.modules.keyword.entity.KeywordCoverageEntity;
import com.adpilot.modules.keyword.entity.KeywordInsightEntity;
import com.adpilot.modules.keyword.entity.KeywordLibraryEntity;
import com.adpilot.modules.keyword.entity.KeywordLibraryItemEntity;
import com.adpilot.modules.keyword.entity.KeywordNgramEntity;
import com.adpilot.modules.keyword.mapper.KeywordCoverageMapper;
import com.adpilot.modules.keyword.mapper.KeywordInsightMapper;
import com.adpilot.modules.keyword.mapper.KeywordLibraryItemMapper;
import com.adpilot.modules.keyword.mapper.KeywordLibraryMapper;
import com.adpilot.modules.keyword.mapper.KeywordNgramMapper;
import com.adpilot.modules.keyword.service.KeywordIntelligenceService;
import com.adpilot.modules.keyword.vo.KeywordInsightOverviewVo;
import com.adpilot.modules.keyword.vo.KeywordInsightVo;
import com.adpilot.modules.keyword.vo.KeywordSummaryVo;
import com.adpilot.common.security.CurrentUser;
import com.adpilot.common.security.DataScopeService;
import com.adpilot.common.security.ScopeTarget;
import com.adpilot.common.utils.SecurityUtils;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class KeywordIntelligenceServiceImpl implements KeywordIntelligenceService {

    private final KeywordInsightMapper insightMapper;
    private final KeywordCoverageMapper coverageMapper;
    private final KeywordNgramMapper ngramMapper;
    private final KeywordLibraryMapper libraryMapper;
    private final KeywordLibraryItemMapper libraryItemMapper;
    private final AuditLogService auditLogService;
    private final ObjectMapper objectMapper;
    private final DataScopeService dataScopeService;

    /** Store-only scope target for keyword insights (Req 7.1.5). */
    private static final ScopeTarget STORE_SCOPE = ScopeTarget.store("store_id");

    /** Current authenticated user, or {@code null} for system/background contexts. */
    private static CurrentUser scopeUser() {
        return SecurityUtils.isAuthenticated() ? SecurityUtils.getCurrentUser() : null;
    }

    private static final BigDecimal TARGET_ACOS = new BigDecimal("30.00");
    private static final int MIN_CLICKS_FOR_WASTE = 15;
    private static final BigDecimal MIN_SPEND_FOR_WASTE = new BigDecimal("15.00");
    private static final BigDecimal MIN_SPEND_FOR_HIGH_ACOS = new BigDecimal("20.00");
    private static final BigDecimal CVR_MULTIPLIER = new BigDecimal("1.3");
    private static final BigDecimal ACOS_MULTIPLIER = new BigDecimal("1.3");
    private static final int MIN_ORDERS_WINNER = 2;
    private static final int MIN_WASTE_NGRAM_COUNT = 3;

    @Override
    public KeywordInsightOverviewVo getOverview(String storeId) {
        try {
            UUID storeUuid = UUID.fromString(storeId);

            LambdaQueryWrapper<KeywordInsightEntity> allWrapper = new LambdaQueryWrapper<>();
            allWrapper.eq(KeywordInsightEntity::getStoreId, storeUuid);
            long totalKeywords = insightMapper.selectCount(allWrapper);

            LambdaQueryWrapper<KeywordInsightEntity> activeWrapper = new LambdaQueryWrapper<>();
            activeWrapper.eq(KeywordInsightEntity::getStoreId, storeUuid)
                    .eq(KeywordInsightEntity::getStatus, "open");
            long activeKeywords = insightMapper.selectCount(activeWrapper);

            LambdaQueryWrapper<KeywordInsightEntity> wasteWrapper = new LambdaQueryWrapper<>();
            wasteWrapper.eq(KeywordInsightEntity::getStoreId, storeUuid)
                    .eq(KeywordInsightEntity::getSegment, "waste");
            long wasteKeywords = insightMapper.selectCount(wasteWrapper);

            LambdaQueryWrapper<KeywordInsightEntity> winnerWrapper = new LambdaQueryWrapper<>();
            winnerWrapper.eq(KeywordInsightEntity::getStoreId, storeUuid)
                    .eq(KeywordInsightEntity::getSegment, "winner");
            long winnerKeywords = insightMapper.selectCount(winnerWrapper);

            LambdaQueryWrapper<KeywordInsightEntity> rankingWrapper = new LambdaQueryWrapper<>();
            rankingWrapper.eq(KeywordInsightEntity::getStoreId, storeUuid)
                    .eq(KeywordInsightEntity::getSegment, "ranking");
            long rankingKeywords = insightMapper.selectCount(rankingWrapper);

            LambdaQueryWrapper<KeywordInsightEntity> negativeWrapper = new LambdaQueryWrapper<>();
            negativeWrapper.eq(KeywordInsightEntity::getStoreId, storeUuid)
                    .like(KeywordInsightEntity::getRecommendedAction, "negative");
            long negativeCandidates = insightMapper.selectCount(negativeWrapper);

            LambdaQueryWrapper<KeywordInsightEntity> harvestWrapper = new LambdaQueryWrapper<>();
            harvestWrapper.eq(KeywordInsightEntity::getStoreId, storeUuid)
                    .eq(KeywordInsightEntity::getRecommendedAction, "add_exact");
            long harvestCandidates = insightMapper.selectCount(harvestWrapper);

            // Calculate health score: percentage of non-waste insights
            int healthScore = totalKeywords > 0
                    ? (int) (((totalKeywords - wasteKeywords) * 100.0) / totalKeywords)
                    : 100;

            // Calculate search term coverage rate
            LambdaQueryWrapper<KeywordCoverageEntity> coverageWrapper = new LambdaQueryWrapper<>();
            coverageWrapper.eq(KeywordCoverageEntity::getStoreId, storeUuid);
            long totalCoverage = coverageMapper.selectCount(coverageWrapper);

            LambdaQueryWrapper<KeywordCoverageEntity> coveredWrapper = new LambdaQueryWrapper<>();
            coveredWrapper.eq(KeywordCoverageEntity::getStoreId, storeUuid)
                    .eq(KeywordCoverageEntity::getInListing, true);
            long coveredCount = coverageMapper.selectCount(coveredWrapper);

            double coverageRate = totalCoverage > 0
                    ? BigDecimal.valueOf(coveredCount * 100.0 / totalCoverage)
                            .setScale(1, RoundingMode.HALF_UP).doubleValue()
                    : 0.0;

            return KeywordInsightOverviewVo.builder()
                    .totalKeywords((int) totalKeywords)
                    .activeKeywords((int) activeKeywords)
                    .wasteKeywords((int) wasteKeywords)
                    .winnerKeywords((int) winnerKeywords)
                    .lowImpressionHighCvrKeywords((int) rankingKeywords)
                    .negativeCandidates((int) negativeCandidates)
                    .harvestCandidates((int) harvestCandidates)
                    .keywordHealthScore(healthScore)
                    .searchTermCoverageRate(coverageRate)
                    .build();
        } catch (IllegalArgumentException e) {
            throw new BusinessException("INVALID_STORE_ID", "Invalid store ID format: " + storeId);
        } catch (Exception e) {
            log.error("Failed to get keyword overview for store {}: {}", storeId, e.getMessage(), e);
            throw new BusinessException("OVERVIEW_ERROR", "Failed to retrieve keyword overview");
        }
    }

    @Override
    public PageResponse<KeywordInsightVo> getInsights(KeywordInsightQueryRequest request) {
        try {
            int page = request.getPage() != null ? request.getPage() : 1;
            int pageSize = request.getPageSize() != null ? request.getPageSize() : 50;

            Page<KeywordInsightEntity> pageParam = new Page<>(page, pageSize);
            QueryWrapper<KeywordInsightEntity> wrapper = new QueryWrapper<>();

            if (request.getStoreId() != null && !request.getStoreId().isEmpty()) {
                wrapper.eq("store_id", UUID.fromString(request.getStoreId()).toString());
            }
            if (request.getSegment() != null && !request.getSegment().isEmpty()) {
                wrapper.eq("segment", request.getSegment());
            }
            if (request.getStatus() != null && !request.getStatus().isEmpty()) {
                wrapper.eq("status", request.getStatus());
            }
            if (request.getSource() != null && !request.getSource().isEmpty()) {
                wrapper.eq("source", request.getSource());
            }
            if (request.getRiskLevel() != null && !request.getRiskLevel().isEmpty()) {
                wrapper.eq("risk_level", request.getRiskLevel());
            }
            if (request.getSearch() != null && !request.getSearch().isEmpty()) {
                wrapper.like("text", request.getSearch());
            }

            // Store-scope the listing so an unset/foreign storeId can never surface
            // another tenant's insights (empty scope short-circuits to no rows).
            CurrentUser user = scopeUser();
            if (user != null) {
                dataScopeService.applyScope(wrapper, STORE_SCOPE, user);
            }

            wrapper.orderByDesc("created_at");

            Page<KeywordInsightEntity> result = insightMapper.selectPage(pageParam, wrapper);
            List<KeywordInsightVo> voList = KeywordInsightConverter.toVoList(result.getRecords());

            return PageResponse.of(voList, result.getTotal(), page, pageSize);
        } catch (IllegalArgumentException e) {
            throw new BusinessException("INVALID_PARAMETER", "Invalid query parameter: " + e.getMessage());
        } catch (Exception e) {
            log.error("Failed to get keyword insights: {}", e.getMessage(), e);
            throw new BusinessException("INSIGHTS_ERROR", "Failed to retrieve keyword insights");
        }
    }

    @Override
    public KeywordSummaryVo getSummary(String storeId) {
        try {
            UUID storeUuid = UUID.fromString(storeId);

            // Get all open insights for this store
            LambdaQueryWrapper<KeywordInsightEntity> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(KeywordInsightEntity::getStoreId, storeUuid)
                    .eq(KeywordInsightEntity::getStatus, "open");
            List<KeywordInsightEntity> insights = insightMapper.selectList(wrapper);

            // Find biggest problem: highest waste score insight
            String biggestProblem = insights.stream()
                    .filter(i -> "waste".equals(i.getSegment()))
                    .max(Comparator.comparingInt(i -> i.getWasteScore() != null ? i.getWasteScore() : 0))
                    .map(i -> "Waste spend on \"" + i.getText() + "\" - " + i.getReason())
                    .orElse("No major waste issues detected");

            // Best keyword to scale: winner with highest opportunity score
            String bestKeywordToScale = insights.stream()
                    .filter(i -> "winner".equals(i.getSegment()))
                    .max(Comparator.comparingInt(i -> i.getOpportunityScore() != null ? i.getOpportunityScore() : 0))
                    .map(i -> "\"" + i.getText() + "\" - " + i.getReason())
                    .orElse("No clear scaling opportunities found");

            // Best keyword to negate: waste with highest waste score
            String bestKeywordToNegate = insights.stream()
                    .filter(i -> "waste".equals(i.getSegment()) && "add_negative".equals(i.getRecommendedAction()))
                    .max(Comparator.comparingInt(i -> i.getWasteScore() != null ? i.getWasteScore() : 0))
                    .map(i -> "\"" + i.getText() + "\" - " + i.getReason())
                    .orElse("No negative keyword candidates found");

            // Listing missing but converting
            List<String> listingMissing = insights.stream()
                    .filter(i -> "listing_missing".equals(i.getSegment()))
                    .limit(5)
                    .map(i -> i.getText())
                    .collect(Collectors.toList());

            // Competitor opportunities
            List<String> competitorOpps = insights.stream()
                    .filter(i -> "competitor".equals(i.getSegment()))
                    .limit(5)
                    .map(i -> i.getText())
                    .collect(Collectors.toList());

            // Generate next week plan
            List<String> nextWeekPlan = generateNextWeekPlan(insights);

            return KeywordSummaryVo.builder()
                    .biggestProblem(biggestProblem)
                    .bestKeywordToScale(bestKeywordToScale)
                    .bestKeywordToNegate(bestKeywordToNegate)
                    .listingMissingButConverting(listingMissing.isEmpty() ? List.of("All converting terms are in listing") : listingMissing)
                    .competitorOpportunities(competitorOpps.isEmpty() ? List.of("No competitor terms detected") : competitorOpps)
                    .nextWeekPlan(nextWeekPlan)
                    .build();
        } catch (IllegalArgumentException e) {
            throw new BusinessException("INVALID_STORE_ID", "Invalid store ID format: " + storeId);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to get keyword summary for store {}: {}", storeId, e.getMessage(), e);
            throw new BusinessException("SUMMARY_ERROR", "Failed to generate keyword summary");
        }
    }

    @Override
    @Transactional
    public int analyzeKeywordHealth(String storeId) {
        try {
            UUID storeUuid = UUID.fromString(storeId);
            int totalInsights = 0;

            log.info("Starting keyword health analysis for store: {}", storeId);

            // Get all coverage data for this store
            LambdaQueryWrapper<KeywordCoverageEntity> coverageWrapper = new LambdaQueryWrapper<>();
            coverageWrapper.eq(KeywordCoverageEntity::getStoreId, storeUuid);
            List<KeywordCoverageEntity> coverages = coverageMapper.selectList(coverageWrapper);

            // Calculate averages for rules
            BigDecimal avgCvr = calculateAverageCvr(coverages);
            BigDecimal avgImpressions = calculateAverageImpressions(coverages);

            // Rule 1: Winner Term Rule
            totalInsights += applyWinnerTermRule(storeUuid, coverages);

            // Rule 2: Waste Spend Rule
            totalInsights += applyWasteSpendRule(storeUuid, coverages);

            // Rule 3: Low Impression High CVR Rule
            totalInsights += applyLowImpressionHighCvrRule(storeUuid, coverages, avgCvr, avgImpressions);

            // Rule 4: High ACoS Rule
            totalInsights += applyHighAcosRule(storeUuid, coverages);

            // Rule 5: No Listing Coverage Rule
            totalInsights += applyNoListingCoverageRule(storeUuid, coverages);

            // Rule 6: Brand Protection Rule
            totalInsights += applyBrandProtectionRule(storeUuid, coverages);

            // Rule 7: Competitor Term Rule
            totalInsights += applyCompetitorTermRule(storeUuid, coverages);

            // Rule 8: N-Gram Waste Rule
            totalInsights += applyNgramWasteRule(storeUuid);

            // Rule 9: Cannibalization Rule
            totalInsights += applyCannibalizationRule(storeUuid, coverages);

            // Rule 10: Ranking Opportunity Rule
            totalInsights += applyRankingOpportunityRule(storeUuid, coverages);

            log.info("Keyword health analysis completed for store: {}, insights generated: {}", storeId, totalInsights);
            return totalInsights;
        } catch (IllegalArgumentException e) {
            throw new BusinessException("INVALID_STORE_ID", "Invalid store ID format: " + storeId);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to analyze keyword health for store {}: {}", storeId, e.getMessage(), e);
            throw new BusinessException("ANALYSIS_ERROR", "Failed to analyze keyword health: " + e.getMessage());
        }
    }

    @Override
    @Transactional
    public KeywordInsightVo applyInsight(String id, String userId) {
        try {
            KeywordInsightEntity entity = insightMapper.selectById(UUID.fromString(id));
            if (entity == null) {
                throw new BusinessException("INSIGHT_NOT_FOUND", "Insight not found: " + id);
            }
            CurrentUser user = scopeUser();
            if (user != null) {
                dataScopeService.assertCanWrite(entity, user);
            }

            String previousStatus = entity.getStatus();
            entity.setStatus("applied");
            entity.setUpdatedAt(LocalDateTime.now());
            insightMapper.updateById(entity);

            // Write audit log
            try {
                Map<String, Object> details = Map.of(
                        "insightId", id,
                        "text", entity.getText() != null ? entity.getText() : "",
                        "action", entity.getRecommendedAction() != null ? entity.getRecommendedAction() : "",
                        "previousStatus", previousStatus != null ? previousStatus : "",
                        "newStatus", "applied"
                );
                auditLogService.createLog(
                        userId != null ? UUID.fromString(userId) : null,
                        entity.getStoreId(),
                        "APPLY_INSIGHT",
                        "keyword_insight",
                        entity.getId(),
                        details
                );
            } catch (Exception auditEx) {
                log.warn("Failed to write audit log for apply insight {}: {}", id, auditEx.getMessage());
            }

            log.info("Insight applied: id={}, action={}", id, entity.getRecommendedAction());
            return KeywordInsightConverter.toVo(entity);
        } catch (IllegalArgumentException e) {
            throw new BusinessException("INVALID_ID", "Invalid insight ID format: " + id);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to apply insight {}: {}", id, e.getMessage(), e);
            throw new BusinessException("APPLY_ERROR", "Failed to apply insight");
        }
    }

    @Override
    @Transactional
    public KeywordInsightVo watchInsight(String id) {
        try {
            KeywordInsightEntity entity = insightMapper.selectById(UUID.fromString(id));
            if (entity == null) {
                throw new BusinessException("INSIGHT_NOT_FOUND", "Insight not found: " + id);
            }
            CurrentUser user = scopeUser();
            if (user != null) {
                dataScopeService.assertCanWrite(entity, user);
            }

            entity.setStatus("watching");
            entity.setUpdatedAt(LocalDateTime.now());
            insightMapper.updateById(entity);

            log.info("Insight set to watching: id={}", id);
            return KeywordInsightConverter.toVo(entity);
        } catch (IllegalArgumentException e) {
            throw new BusinessException("INVALID_ID", "Invalid insight ID format: " + id);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to watch insight {}: {}", id, e.getMessage(), e);
            throw new BusinessException("WATCH_ERROR", "Failed to update insight status");
        }
    }

    @Override
    @Transactional
    public KeywordInsightVo dismissInsight(String id) {
        try {
            KeywordInsightEntity entity = insightMapper.selectById(UUID.fromString(id));
            if (entity == null) {
                throw new BusinessException("INSIGHT_NOT_FOUND", "Insight not found: " + id);
            }
            CurrentUser user = scopeUser();
            if (user != null) {
                dataScopeService.assertCanWrite(entity, user);
            }

            String previousStatus = entity.getStatus();
            entity.setStatus("dismissed");
            entity.setUpdatedAt(LocalDateTime.now());
            insightMapper.updateById(entity);

            // Write audit log
            try {
                Map<String, Object> details = Map.of(
                        "insightId", id,
                        "text", entity.getText() != null ? entity.getText() : "",
                        "previousStatus", previousStatus != null ? previousStatus : "",
                        "newStatus", "dismissed"
                );
                auditLogService.createLog(
                        null,
                        entity.getStoreId(),
                        "DISMISS_INSIGHT",
                        "keyword_insight",
                        entity.getId(),
                        details
                );
            } catch (Exception auditEx) {
                log.warn("Failed to write audit log for dismiss insight {}: {}", id, auditEx.getMessage());
            }

            log.info("Insight dismissed: id={}", id);
            return KeywordInsightConverter.toVo(entity);
        } catch (IllegalArgumentException e) {
            throw new BusinessException("INVALID_ID", "Invalid insight ID format: " + id);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to dismiss insight {}: {}", id, e.getMessage(), e);
            throw new BusinessException("DISMISS_ERROR", "Failed to dismiss insight");
        }
    }

    // ==================== Rule Implementations ====================

    /**
     * Shared scaffolding for coverage-driven rules: iterate the store's coverage
     * rows, keep the ones matching {@code predicate}, skip any that already have an
     * equivalent open/watching insight (same segment + action), then build and
     * insert the rule-specific insight. Returns the number of insights created.
     *
     * <p>The per-rule behaviour (matching condition and the insight that gets
     * built) is supplied by the caller so each rule keeps its exact logic,
     * thresholds and outputs.
     */
    private int applyCoverageRule(UUID storeId,
                                  List<KeywordCoverageEntity> coverages,
                                  Predicate<KeywordCoverageEntity> matches,
                                  String segment,
                                  String action,
                                  Function<KeywordCoverageEntity, KeywordInsightEntity> insightBuilder) {
        int count = 0;
        for (KeywordCoverageEntity c : coverages) {
            if (matches.test(c)) {
                if (insightAlreadyExists(storeId, c.getKeywordText(), segment, action)) {
                    continue;
                }
                insightMapper.insert(insightBuilder.apply(c));
                count++;
            }
        }
        return count;
    }

    /**
     * Rule 1: Winner Term Rule
     * Search terms where orders >= 2 and ACoS <= targetAcos -> segment='winner', action='add_exact'
     */
    private int applyWinnerTermRule(UUID storeId, List<KeywordCoverageEntity> coverages) {
        return applyCoverageRule(storeId, coverages,
                c -> c.getOrders() != null && c.getOrders() >= MIN_ORDERS_WINNER
                        && c.getAcos() != null && c.getAcos().compareTo(TARGET_ACOS) <= 0,
                "winner", "add_exact",
                c -> {
                    BigDecimal profitMargin = TARGET_ACOS.subtract(c.getAcos());
                    return KeywordInsightEntity.builder()
                        .storeId(storeId)
                        .campaignId(c.getCampaignId())
                        .keywordId(c.getKeywordId())
                        .text(c.getKeywordText())
                        .source("search_term")
                        .segment("winner")
                        .healthScore(calculateHealthScore(c))
                        .opportunityScore(85)
                        .wasteScore(5)
                        .confidenceScore(calculateConfidenceScore(c))
                        .recommendedAction("add_exact")
                        .reason("High performer with " + c.getOrders() + " orders and "
                                + c.getAcos().setScale(2, RoundingMode.HALF_UP) + "% ACoS (target: "
                                + TARGET_ACOS + "%). Profit margin: " + profitMargin.setScale(2, RoundingMode.HALF_UP) + "%")
                        .currentData(toJsonString(Map.of(
                                "impressions", c.getImpressions() != null ? c.getImpressions() : 0,
                                "clicks", c.getClicks() != null ? c.getClicks() : 0,
                                "orders", c.getOrders() != null ? c.getOrders() : 0,
                                "spend", c.getSpend() != null ? c.getSpend() : BigDecimal.ZERO,
                                "sales", c.getSales() != null ? c.getSales() : BigDecimal.ZERO,
                                "acos", c.getAcos() != null ? c.getAcos() : BigDecimal.ZERO,
                                "cvr", c.getCvr() != null ? c.getCvr() : BigDecimal.ZERO
                        )))
                        .expectedImpact("Estimated +" + (c.getOrders() * 2) + " additional orders per week")
                        .riskLevel("low")
                        .status("open")
                        .build();
                });
    }

    /**
     * Rule 2: Waste Spend Rule
     * Search terms where clicks >= 15, orders = 0, spend >= 15 -> segment='waste', action='add_negative'
     */
    private int applyWasteSpendRule(UUID storeId, List<KeywordCoverageEntity> coverages) {
        return applyCoverageRule(storeId, coverages,
                c -> c.getClicks() != null && c.getClicks() >= MIN_CLICKS_FOR_WASTE
                        && (c.getOrders() == null || c.getOrders() == 0)
                        && c.getSpend() != null && c.getSpend().compareTo(MIN_SPEND_FOR_WASTE) >= 0,
                "waste", "add_negative",
                c -> KeywordInsightEntity.builder()
                        .storeId(storeId)
                        .campaignId(c.getCampaignId())
                        .keywordId(c.getKeywordId())
                        .text(c.getKeywordText())
                        .source("search_term")
                        .segment("waste")
                        .healthScore(10)
                        .opportunityScore(5)
                        .wasteScore(95)
                        .confidenceScore(calculateConfidenceScore(c))
                        .recommendedAction("add_negative")
                        .reason("Zero orders after " + c.getClicks() + " clicks and $"
                                + c.getSpend().setScale(2, RoundingMode.HALF_UP) + " spend. "
                                + "This search term is wasting budget with no conversions.")
                        .currentData(toJsonString(Map.of(
                                "impressions", c.getImpressions() != null ? c.getImpressions() : 0,
                                "clicks", c.getClicks() != null ? c.getClicks() : 0,
                                "orders", 0,
                                "spend", c.getSpend() != null ? c.getSpend() : BigDecimal.ZERO,
                                "sales", BigDecimal.ZERO,
                                "acos", BigDecimal.ZERO
                        )))
                        .expectedImpact("Save $" + c.getSpend().setScale(2, RoundingMode.HALF_UP) + " per week")
                        .riskLevel("low")
                        .status("open")
                        .build());
    }

    /**
     * Rule 3: Low Impression High CVR Rule
     * Keywords where CVR > avg * 1.3 and impressions < avg -> segment='ranking', action='increase_bid'
     */
    private int applyLowImpressionHighCvrRule(UUID storeId, List<KeywordCoverageEntity> coverages,
                                               BigDecimal avgCvr, BigDecimal avgImpressions) {
        BigDecimal cvrThreshold = avgCvr.multiply(CVR_MULTIPLIER);

        return applyCoverageRule(storeId, coverages,
                c -> c.getCvr() != null && c.getImpressions() != null
                        && c.getCvr().compareTo(cvrThreshold) > 0
                        && c.getImpressions() < avgImpressions.longValue()
                        && c.getOrders() != null && c.getOrders() > 0,
                "ranking", "increase_bid",
                c -> KeywordInsightEntity.builder()
                        .storeId(storeId)
                        .campaignId(c.getCampaignId())
                        .keywordId(c.getKeywordId())
                        .text(c.getKeywordText())
                        .source("keyword")
                        .segment("ranking")
                        .healthScore(70)
                        .opportunityScore(80)
                        .wasteScore(10)
                        .confidenceScore(calculateConfidenceScore(c))
                        .recommendedAction("increase_bid")
                        .reason("High CVR of " + c.getCvr().setScale(2, RoundingMode.HALF_UP)
                                + "% (avg: " + avgCvr.setScale(2, RoundingMode.HALF_UP)
                                + "%) but only " + c.getImpressions() + " impressions (avg: "
                                + avgImpressions.longValue() + "). Increasing bid can unlock more conversions.")
                        .currentData(toJsonString(Map.of(
                                "impressions", c.getImpressions(),
                                "clicks", c.getClicks() != null ? c.getClicks() : 0,
                                "orders", c.getOrders(),
                                "cvr", c.getCvr(),
                                "avgCvr", avgCvr,
                                "avgImpressions", avgImpressions
                        )))
                        .expectedImpact("Estimated 2-3x impression increase with maintained CVR")
                        .riskLevel("medium")
                        .status("open")
                        .build());
    }

    /**
     * Rule 4: High ACoS Rule
     * Keywords where ACoS > target * 1.3 and spend > 20 -> segment='waste', action='decrease_bid'
     */
    private int applyHighAcosRule(UUID storeId, List<KeywordCoverageEntity> coverages) {
        BigDecimal acosThreshold = TARGET_ACOS.multiply(ACOS_MULTIPLIER);

        return applyCoverageRule(storeId, coverages,
                c -> c.getAcos() != null && c.getSpend() != null
                        && c.getAcos().compareTo(acosThreshold) > 0
                        && c.getSpend().compareTo(MIN_SPEND_FOR_HIGH_ACOS) > 0,
                "waste", "decrease_bid",
                c -> {
                    BigDecimal overSpendPct = c.getAcos().subtract(TARGET_ACOS)
                            .divide(TARGET_ACOS, 2, RoundingMode.HALF_UP)
                            .multiply(new BigDecimal("100"));

                    return KeywordInsightEntity.builder()
                        .storeId(storeId)
                        .campaignId(c.getCampaignId())
                        .keywordId(c.getKeywordId())
                        .text(c.getKeywordText())
                        .source("keyword")
                        .segment("waste")
                        .healthScore(25)
                        .opportunityScore(30)
                        .wasteScore(80)
                        .confidenceScore(calculateConfidenceScore(c))
                        .recommendedAction("decrease_bid")
                        .reason("ACoS of " + c.getAcos().setScale(2, RoundingMode.HALF_UP)
                                + "% is " + overSpendPct.setScale(0, RoundingMode.HALF_UP)
                                + "% above target (" + TARGET_ACOS + "%) with $"
                                + c.getSpend().setScale(2, RoundingMode.HALF_UP) + " spend. "
                                + "Reducing bid can improve profitability.")
                        .currentData(toJsonString(Map.of(
                                "impressions", c.getImpressions() != null ? c.getImpressions() : 0,
                                "clicks", c.getClicks() != null ? c.getClicks() : 0,
                                "orders", c.getOrders() != null ? c.getOrders() : 0,
                                "spend", c.getSpend(),
                                "sales", c.getSales() != null ? c.getSales() : BigDecimal.ZERO,
                                "acos", c.getAcos(),
                                "targetAcos", TARGET_ACOS
                        )))
                        .expectedImpact("Reduce ACoS by ~" + overSpendPct.setScale(0, RoundingMode.HALF_UP) + "%")
                        .riskLevel("medium")
                        .status("open")
                        .build();
                });
    }

    /**
     * Rule 5: No Listing Coverage Rule
     * Search terms with orders > 0 but not in listing -> segment='listing_missing', action='add_to_listing'
     */
    private int applyNoListingCoverageRule(UUID storeId, List<KeywordCoverageEntity> coverages) {
        return applyCoverageRule(storeId, coverages,
                c -> c.getOrders() != null && c.getOrders() > 0
                        && (c.getInListing() == null || !c.getInListing()),
                "listing_missing", "add_to_listing",
                c -> KeywordInsightEntity.builder()
                        .storeId(storeId)
                        .campaignId(c.getCampaignId())
                        .keywordId(c.getKeywordId())
                        .text(c.getKeywordText())
                        .source("search_term")
                        .segment("listing_missing")
                        .healthScore(50)
                        .opportunityScore(75)
                        .wasteScore(20)
                        .confidenceScore(calculateConfidenceScore(c))
                        .recommendedAction("add_to_listing")
                        .reason("Search term \"" + c.getKeywordText() + "\" has "
                                + c.getOrders() + " orders but is not found in the product listing. "
                                + "Adding it can improve organic ranking and conversion rate.")
                        .currentData(toJsonString(Map.of(
                                "impressions", c.getImpressions() != null ? c.getImpressions() : 0,
                                "clicks", c.getClicks() != null ? c.getClicks() : 0,
                                "orders", c.getOrders(),
                                "inListing", false,
                                "inTitle", c.getInTitle() != null ? c.getInTitle() : false,
                                "inBulletPoints", c.getInBulletPoints() != null ? c.getInBulletPoints() : false,
                                "inBackend", c.getInBackend() != null ? c.getInBackend() : false
                        )))
                        .expectedImpact("Improved organic ranking and Quality Score for this term")
                        .riskLevel("low")
                        .status("open")
                        .build());
    }

    /**
     * Rule 6: Brand Protection Rule
     * Brand keywords should not be auto-negated -> segment='brand'
     */
    private int applyBrandProtectionRule(UUID storeId, List<KeywordCoverageEntity> coverages) {
        int count = 0;
        List<String> brandIndicators = new ArrayList<>(List.of("brand", "official", "authentic", "genuine"));
        // Merge store-configured brand keywords (关键词与自动化配置 → 品牌关键词).
        brandIndicators.addAll(loadSeedTerms(storeId, "seed_brand"));

        for (KeywordCoverageEntity c : coverages) {
            if (c.getKeywordText() == null) continue;
            String lowerText = c.getKeywordText().toLowerCase();

            boolean isBrand = brandIndicators.stream().anyMatch(lowerText::contains);
            if (!isBrand) continue;

            // Check if there's a waste insight that might incorrectly negate this
            LambdaQueryWrapper<KeywordInsightEntity> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(KeywordInsightEntity::getStoreId, storeId)
                    .eq(KeywordInsightEntity::getText, c.getKeywordText())
                    .eq(KeywordInsightEntity::getSegment, "brand");
            if (insightMapper.selectCount(wrapper) > 0) {
                continue;
            }

            KeywordInsightEntity insight = KeywordInsightEntity.builder()
                    .storeId(storeId)
                    .campaignId(c.getCampaignId())
                    .keywordId(c.getKeywordId())
                    .text(c.getKeywordText())
                    .source("keyword")
                    .segment("brand")
                    .healthScore(90)
                    .opportunityScore(20)
                    .wasteScore(0)
                    .confidenceScore(90)
                    .recommendedAction("protect")
                    .reason("Brand keyword detected. This term should not be auto-negated "
                            + "as it represents branded traffic with high conversion intent.")
                    .currentData(toJsonString(Map.of(
                            "impressions", c.getImpressions() != null ? c.getImpressions() : 0,
                            "clicks", c.getClicks() != null ? c.getClicks() : 0,
                            "orders", c.getOrders() != null ? c.getOrders() : 0,
                            "isBrand", true
                    )))
                    .expectedImpact("Protect brand traffic from accidental negation")
                    .riskLevel("low")
                    .status("open")
                    .build();
            insightMapper.insert(insight);
            count++;
        }
        return count;
    }

    /**
     * Rule 7: Competitor Term Rule
     * Search terms matching competitor brands -> segment='competitor'
     */
    private int applyCompetitorTermRule(UUID storeId, List<KeywordCoverageEntity> coverages) {
        // Known competitor brand indicators - in production this would come from a configuration table
        List<String> competitorIndicators = new ArrayList<>(List.of("competitor", "alternative to", "vs ", "versus",
                "better than", "like ", "similar to"));
        // Merge store-configured competitor brands / ASINs (关键词与自动化配置 → 竞品品牌 / 竞品 ASIN).
        competitorIndicators.addAll(loadSeedTerms(storeId, "seed_competitor_brand"));
        competitorIndicators.addAll(loadSeedTerms(storeId, "seed_competitor_asin"));

        return applyCoverageRule(storeId, coverages,
                c -> c.getKeywordText() != null
                        && competitorIndicators.stream().anyMatch(c.getKeywordText().toLowerCase()::contains),
                "competitor", "analyze_competitor",
                c -> KeywordInsightEntity.builder()
                    .storeId(storeId)
                    .campaignId(c.getCampaignId())
                    .keywordId(c.getKeywordId())
                    .text(c.getKeywordText())
                    .source("search_term")
                    .segment("competitor")
                    .healthScore(60)
                    .opportunityScore(70)
                    .wasteScore(15)
                    .confidenceScore(65)
                    .recommendedAction("analyze_competitor")
                    .reason("Competitor search term detected: \"" + c.getKeywordText()
                            + "\". This represents a competitive intelligence opportunity.")
                    .currentData(toJsonString(Map.of(
                            "impressions", c.getImpressions() != null ? c.getImpressions() : 0,
                            "clicks", c.getClicks() != null ? c.getClicks() : 0,
                            "orders", c.getOrders() != null ? c.getOrders() : 0,
                            "spend", c.getSpend() != null ? c.getSpend() : BigDecimal.ZERO
                    )))
                    .expectedImpact("Competitive intelligence and potential market share capture")
                    .riskLevel("medium")
                    .status("open")
                    .build());
    }

    /**
     * Rule 8: N-Gram Waste Rule
     * N-grams appearing in 3+ waste terms -> suggest phrase negative
     */
    private int applyNgramWasteRule(UUID storeId) {
        int count = 0;

        LambdaQueryWrapper<KeywordNgramEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(KeywordNgramEntity::getStoreId, storeId)
                .ge(KeywordNgramEntity::getWasteCount, MIN_WASTE_NGRAM_COUNT);
        List<KeywordNgramEntity> wasteNgrams = ngramMapper.selectList(wrapper);

        for (KeywordNgramEntity ngram : wasteNgrams) {
            if (insightAlreadyExists(storeId, ngram.getNgram(), "waste", "add_phrase_negative")) {
                continue;
            }

            KeywordInsightEntity insight = KeywordInsightEntity.builder()
                    .storeId(storeId)
                    .text(ngram.getNgram())
                    .source("ngram")
                    .segment("waste")
                    .healthScore(15)
                    .opportunityScore(10)
                    .wasteScore(90)
                    .confidenceScore(75)
                    .recommendedAction("add_phrase_negative")
                    .reason("N-gram \"" + ngram.getNgram() + "\" appears in "
                            + ngram.getWasteCount() + " waste search terms. "
                            + "Adding as phrase negative can eliminate multiple waste terms at once.")
                    .currentData(toJsonString(Map.of(
                            "ngram", ngram.getNgram(),
                            "ngramType", ngram.getNgramType() != null ? ngram.getNgramType() : "",
                            "frequency", ngram.getFrequency() != null ? ngram.getFrequency() : 0,
                            "wasteCount", ngram.getWasteCount() != null ? ngram.getWasteCount() : 0,
                            "totalSpend", ngram.getTotalSpend() != null ? ngram.getTotalSpend() : BigDecimal.ZERO
                    )))
                    .expectedImpact("Eliminate " + ngram.getWasteCount() + " waste search terms with one negative keyword")
                    .riskLevel("low")
                    .status("open")
                    .build();
            insightMapper.insert(insight);
            count++;
        }
        return count;
    }

    /**
     * Rule 9: Cannibalization Rule
     * Same keyword in multiple campaigns -> add to watchlist
     */
    private int applyCannibalizationRule(UUID storeId, List<KeywordCoverageEntity> coverages) {
        int count = 0;

        // Group keywords by text to find duplicates across campaigns
        Map<String, List<KeywordCoverageEntity>> keywordGroups = coverages.stream()
                .filter(c -> c.getKeywordText() != null && !c.getKeywordText().isEmpty())
                .collect(Collectors.groupingBy(KeywordCoverageEntity::getKeywordText));

        for (Map.Entry<String, List<KeywordCoverageEntity>> entry : keywordGroups.entrySet()) {
            List<KeywordCoverageEntity> group = entry.getValue();
            if (group.size() <= 1) continue;

            // Multiple campaigns have the same keyword
            Set<UUID> campaignIds = group.stream()
                    .map(KeywordCoverageEntity::getCampaignId)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toSet());

            if (campaignIds.size() <= 1) continue;

            String keywordText = entry.getKey();
            if (insightAlreadyExists(storeId, keywordText, "cannibalization", "consolidate")) {
                continue;
            }

            BigDecimal totalSpend = group.stream()
                    .map(c -> c.getSpend() != null ? c.getSpend() : BigDecimal.ZERO)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            KeywordInsightEntity insight = KeywordInsightEntity.builder()
                    .storeId(storeId)
                    .keywordId(group.get(0).getKeywordId())
                    .text(keywordText)
                    .source("keyword")
                    .segment("cannibalization")
                    .healthScore(45)
                    .opportunityScore(55)
                    .wasteScore(40)
                    .confidenceScore(70)
                    .recommendedAction("consolidate")
                    .reason("Keyword \"" + keywordText + "\" is active in "
                            + campaignIds.size() + " campaigns. This cannibalization "
                            + "may be driving up costs. Consider consolidating to the best-performing campaign.")
                    .currentData(toJsonString(Map.of(
                            "keyword", keywordText,
                            "campaignCount", campaignIds.size(),
                            "campaignIds", campaignIds.stream().map(UUID::toString).collect(Collectors.toList()),
                            "totalSpend", totalSpend
                    )))
                    .expectedImpact("Reduce wasted spend from campaign overlap")
                    .riskLevel("medium")
                    .status("open")
                    .build();
            insightMapper.insert(insight);
            count++;
        }
        return count;
    }

    /**
     * Rule 10: Ranking Opportunity Rule
     * Low ACoS but not in listing -> suggest rank boost
     */
    private int applyRankingOpportunityRule(UUID storeId, List<KeywordCoverageEntity> coverages) {
        BigDecimal lowAcosThreshold = TARGET_ACOS.multiply(new BigDecimal("0.7")); // 70% of target

        return applyCoverageRule(storeId, coverages,
                c -> c.getAcos() != null && c.getAcos().compareTo(lowAcosThreshold) < 0
                        && c.getOrders() != null && c.getOrders() > 0
                        && (c.getInListing() == null || !c.getInListing()),
                "ranking", "boost_rank",
                c -> KeywordInsightEntity.builder()
                        .storeId(storeId)
                        .campaignId(c.getCampaignId())
                        .keywordId(c.getKeywordId())
                        .text(c.getKeywordText())
                        .source("keyword")
                        .segment("ranking")
                        .healthScore(75)
                        .opportunityScore(85)
                        .wasteScore(5)
                        .confidenceScore(calculateConfidenceScore(c))
                        .recommendedAction("boost_rank")
                        .reason("Keyword \"" + c.getKeywordText() + "\" has excellent ACoS of "
                                + c.getAcos().setScale(2, RoundingMode.HALF_UP) + "% but is not in the listing. "
                                + "Adding to listing and boosting rank can amplify organic + paid synergy.")
                        .currentData(toJsonString(Map.of(
                                "impressions", c.getImpressions() != null ? c.getImpressions() : 0,
                                "clicks", c.getClicks() != null ? c.getClicks() : 0,
                                "orders", c.getOrders(),
                                "acos", c.getAcos(),
                                "inListing", c.getInListing() != null ? c.getInListing() : false
                        )))
                        .expectedImpact("Improved organic ranking with synergistic paid performance")
                        .riskLevel("low")
                        .status("open")
                        .build());
    }

    // ==================== Helper Methods ====================

    /**
     * Loads the store-level seed terms (关键词与自动化配置) for a given seed
     * library type, lowercased for case-insensitive matching. Returns an empty
     * list when nothing is configured. Feeds the brand-protection and
     * competitor-term rules.
     */
    private List<String> loadSeedTerms(UUID storeId, String libraryType) {
        try {
            LambdaQueryWrapper<KeywordLibraryEntity> libWrapper = new LambdaQueryWrapper<>();
            libWrapper.eq(KeywordLibraryEntity::getStoreId, storeId)
                    .eq(KeywordLibraryEntity::getLibraryType, libraryType);
            List<KeywordLibraryEntity> libraries = libraryMapper.selectList(libWrapper);
            if (libraries.isEmpty()) {
                return List.of();
            }
            List<UUID> libraryIds = libraries.stream()
                    .map(KeywordLibraryEntity::getId)
                    .collect(Collectors.toList());

            QueryWrapper<KeywordLibraryItemEntity> itemWrapper = new QueryWrapper<>();
            itemWrapper.in("library_id", libraryIds);
            return libraryItemMapper.selectList(itemWrapper).stream()
                    .map(KeywordLibraryItemEntity::getKeywordText)
                    .filter(t -> t != null && !t.isBlank())
                    .map(t -> t.trim().toLowerCase())
                    .distinct()
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.warn("Failed to load seed terms for store {} type {}: {}", storeId, libraryType, e.getMessage());
            return List.of();
        }
    }

    private boolean insightAlreadyExists(UUID storeId, String text, String segment, String action) {        LambdaQueryWrapper<KeywordInsightEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(KeywordInsightEntity::getStoreId, storeId)
                .eq(KeywordInsightEntity::getText, text)
                .eq(KeywordInsightEntity::getSegment, segment)
                .eq(KeywordInsightEntity::getRecommendedAction, action)
                .in(KeywordInsightEntity::getStatus, "open", "watching");
        return insightMapper.selectCount(wrapper) > 0;
    }

    private BigDecimal calculateAverageCvr(List<KeywordCoverageEntity> coverages) {
        return coverages.stream()
                .filter(c -> c.getCvr() != null && c.getOrders() != null && c.getOrders() > 0)
                .map(KeywordCoverageEntity::getCvr)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(
                        Math.max(coverages.stream()
                                .filter(c -> c.getCvr() != null && c.getOrders() != null && c.getOrders() > 0)
                                .count(), 1)),
                        4, RoundingMode.HALF_UP);
    }

    private BigDecimal calculateAverageImpressions(List<KeywordCoverageEntity> coverages) {
        long count = coverages.stream()
                .filter(c -> c.getImpressions() != null && c.getImpressions() > 0)
                .count();
        if (count == 0) return BigDecimal.ZERO;

        long totalImpressions = coverages.stream()
                .filter(c -> c.getImpressions() != null)
                .mapToLong(KeywordCoverageEntity::getImpressions)
                .sum();
        return BigDecimal.valueOf(totalImpressions).divide(BigDecimal.valueOf(count), 0, RoundingMode.HALF_UP);
    }

    private int calculateHealthScore(KeywordCoverageEntity c) {
        int score = 50;
        if (c.getAcos() != null) {
            if (c.getAcos().compareTo(TARGET_ACOS) <= 0) score += 30;
            else if (c.getAcos().compareTo(TARGET_ACOS.multiply(new BigDecimal("1.5"))) <= 0) score += 10;
            else score -= 20;
        }
        if (c.getOrders() != null && c.getOrders() > 0) score += 15;
        if (c.getCvr() != null && c.getCvr().compareTo(new BigDecimal("5")) > 0) score += 5;
        return Math.min(100, Math.max(0, score));
    }

    private int calculateConfidenceScore(KeywordCoverageEntity c) {
        int score = 50;
        if (c.getClicks() != null) {
            if (c.getClicks() >= 100) score += 30;
            else if (c.getClicks() >= 50) score += 20;
            else if (c.getClicks() >= 20) score += 10;
        }
        if (c.getImpressions() != null) {
            if (c.getImpressions() >= 1000) score += 15;
            else if (c.getImpressions() >= 500) score += 10;
            else if (c.getImpressions() >= 100) score += 5;
        }
        return Math.min(100, Math.max(0, score));
    }

    private String toJsonString(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize object to JSON: {}", e.getMessage());
            return "{}";
        }
    }

    private List<String> generateNextWeekPlan(List<KeywordInsightEntity> insights) {
        List<String> plan = new ArrayList<>();

        long wasteCount = insights.stream().filter(i -> "waste".equals(i.getSegment())).count();
        long winnerCount = insights.stream().filter(i -> "winner".equals(i.getSegment())).count();
        long listingMissing = insights.stream().filter(i -> "listing_missing".equals(i.getSegment())).count();
        long rankingCount = insights.stream().filter(i -> "ranking".equals(i.getSegment())).count();

        if (wasteCount > 0) {
            plan.add("Add " + wasteCount + " negative keywords to eliminate waste spend");
        }
        if (winnerCount > 0) {
            plan.add("Harvest " + winnerCount + " winner search terms as exact match keywords");
        }
        if (listingMissing > 0) {
            plan.add("Update listing with " + listingMissing + " converting search terms");
        }
        if (rankingCount > 0) {
            plan.add("Increase bids on " + rankingCount + " high-CVR low-impression keywords");
        }
        if (plan.isEmpty()) {
            plan.add("Continue monitoring current keyword performance");
            plan.add("Review search term report for new opportunities");
        }
        return plan;
    }
}
