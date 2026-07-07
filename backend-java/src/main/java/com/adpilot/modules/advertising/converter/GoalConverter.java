package com.adpilot.modules.advertising.converter;

import com.adpilot.modules.advertising.entity.GoalEntity;
import com.adpilot.modules.advertising.vo.CampaignTrendPointVo;
import com.adpilot.modules.advertising.vo.CampaignVo;
import com.adpilot.modules.advertising.vo.GoalVo;
import com.adpilot.modules.advertising.vo.PerformanceSummaryVo;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.List;

public class GoalConverter {

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final ObjectMapper objectMapper = new ObjectMapper();

    public static GoalVo toVo(GoalEntity entity) {
        if (entity == null) return null;
        List<String> products = parseJsonList(entity.getProductIds());
        return GoalVo.builder()
                .id(entity.getId().toString())
                .storeId(entity.getStoreId().toString())
                .name(entity.getName())
                .type(entity.getType())
                .status(entity.getStatus())
                .targetAcos(entity.getTargetAcos() != null ? entity.getTargetAcos().doubleValue() : 0)
                .dailyBudget(entity.getDailyBudget() != null ? entity.getDailyBudget().doubleValue() : 0)
                .maxCpc(entity.getMaxCpc() != null ? entity.getMaxCpc().doubleValue() : 0)
                .minBid(entity.getMinBid() != null ? entity.getMinBid().doubleValue() : 0)
                .maxBid(entity.getMaxBid() != null ? entity.getMaxBid().doubleValue() : 0)
                .brandKeywords(parseJsonList(entity.getBrandKeywords()))
                .categoryKeywords(parseJsonList(entity.getCategoryKeywords()))
                .competitorBrands(parseJsonList(entity.getCompetitorBrands()))
                .competitorAsins(parseJsonList(entity.getCompetitorAsins()))
                .autoNegate(Boolean.TRUE.equals(entity.getAutoNegate()))
                .autoBid(Boolean.TRUE.equals(entity.getAutoBid()))
                .autoExpand(Boolean.TRUE.equals(entity.getAutoExpand()))
                .optimizeFrequency(entity.getOptimizeFrequency())
                .riskPreference(AdvertisingEnumEmitter.aiPersonality(entity.getRiskPreference()))
                .optimizationGoal(AdvertisingEnumEmitter.optimizationGoal(entity.getType()))
                .productIds(parseJsonList(entity.getProductIds()))
                .products(products)
                .campaigns(Collections.emptyList())
                .trendData(Collections.emptyList())
                .createdAt(entity.getCreatedAt() != null ? entity.getCreatedAt().format(FORMATTER) : null)
                .updatedAt(entity.getUpdatedAt() != null ? entity.getUpdatedAt().format(FORMATTER) : null)
                .build();
    }

    /**
     * Convert a Goal with its associated {@code campaigns} and performance summary (Req 14.4). The
     * VO's {@code campaignCount} is derived from the supplied {@code campaigns} collection, so it
     * always equals that collection's length (Property 33).
     */
    public static GoalVo toVo(GoalEntity entity, List<CampaignVo> campaigns, PerformanceSummaryVo performance) {
        return toVo(entity, campaigns, performance, null);
    }

    /**
     * Convert a Goal with its associated {@code campaigns}, performance summary, and {@code trendData}
     * (Req 14.4).
     */
    public static GoalVo toVo(GoalEntity entity, List<CampaignVo> campaigns, PerformanceSummaryVo performance,
                              List<CampaignTrendPointVo> trendData) {
        GoalVo vo = toVo(entity);
        if (vo != null) {
            vo.setCampaigns(campaigns != null ? campaigns : Collections.emptyList());
            vo.setPerformance(performance);
            if (trendData != null) {
                vo.setTrendData(trendData);
            }
        }
        return vo;
    }

    private static List<String> parseJsonList(String json) {
        if (json == null || json.isBlank()) return Collections.emptyList();
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }
}
