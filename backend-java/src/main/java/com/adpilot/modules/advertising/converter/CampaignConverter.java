package com.adpilot.modules.advertising.converter;

import com.adpilot.modules.advertising.entity.CampaignEntity;
import com.adpilot.modules.advertising.support.CampaignView;
import com.adpilot.modules.advertising.vo.CampaignVo;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.List;

public class CampaignConverter {

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final ObjectMapper objectMapper = new ObjectMapper();

    /** Immutable {@code origin} value for a Campaign ingested from Amazon (Req 12.6). */
    private static final String ORIGIN_AMAZON_IMPORT = "amazon_import";

    public static CampaignVo toVo(CampaignEntity entity) {
        // Single-arg projection used where the creation Operation's lifecycle is not resolved
        // (e.g. CSV export). An amazon_import Campaign is still recognized as synced from its
        // origin + amazon_campaign_id; a local Campaign defaults to not-yet-synced until the
        // service resolves its creation Operation via {@link #toVo(CampaignEntity, boolean)}.
        return toVo(entity, false);
    }

    /**
     * Project a {@link CampaignEntity} onto its {@link CampaignVo}, gating Amazon-synced-list
     * inclusion (Req 12.7, 12.8; Property 29).
     *
     * @param entity                     the campaign entity
     * @param creationOperationEffective whether the Campaign's creation Operation reached the
     *                                   {@code effective} Sync_State (resolved by the service from
     *                                   the {@code operations} table)
     */
    public static CampaignVo toVo(CampaignEntity entity, boolean creationOperationEffective) {
        if (entity == null) return null;
        boolean synced = syncedToAmazon(entity, creationOperationEffective);
        return CampaignVo.builder()
                .id(entity.getId().toString())
                .goalId(entity.getGoalId() != null ? entity.getGoalId().toString() : null)
                .storeId(entity.getStoreId().toString())
                .name(entity.getName())
                .campaignType(entity.getCampaignType())
                .portfolio(entity.getPortfolio())
                .status(AdvertisingEnumEmitter.objectStatus(entity.getStatus()))
                .budget(entity.getBudget() != null ? entity.getBudget().doubleValue() : 0)
                .budgetType(entity.getBudgetType())
                .startDate(entity.getStartDate())
                .endDate(entity.getEndDate())
                .targetingType(entity.getTargetingType())
                .state(entity.getState())
                .spend(entity.getSpend() != null ? entity.getSpend().doubleValue() : 0)
                .sales(entity.getSales() != null ? entity.getSales().doubleValue() : 0)
                .orders(entity.getOrders() != null ? entity.getOrders() : 0)
                .impressions(entity.getImpressions() != null ? entity.getImpressions() : 0)
                .clicks(entity.getClicks() != null ? entity.getClicks() : 0)
                .acos(entity.getAcos() != null ? entity.getAcos().doubleValue() : 0)
                .roas(entity.getRoas() != null ? entity.getRoas().doubleValue() : 0)
                .conversionRate(entity.getConversionRate() != null ? entity.getConversionRate().doubleValue() : 0)
                .avgCpc(entity.getAvgCpc() != null ? entity.getAvgCpc().doubleValue() : 0)
                .adGroupCount(entity.getAdGroupCount() != null ? entity.getAdGroupCount() : 0)
                .keywordCount(entity.getKeywordCount() != null ? entity.getKeywordCount() : 0)
                .negativeKeywordCount(entity.getNegativeKeywordCount() != null ? entity.getNegativeKeywordCount() : 0)
                .externalId(entity.getExternalId())
                .hostingEnabled(Boolean.TRUE.equals(entity.getHostingEnabled()))
                .aiHostingStatus(AdvertisingEnumEmitter.aiHostingStatus(Boolean.TRUE.equals(entity.getHostingEnabled())))
                .hostingGoal(entity.getHostingGoal())
                .optimizationGoal(AdvertisingEnumEmitter.optimizationGoal(entity.getHostingGoal()))
                .campaignPersonality(AdvertisingEnumEmitter.aiPersonality(entity.getCampaignPersonality()))
                .targetAcos(entity.getTargetAcos() != null ? entity.getTargetAcos().doubleValue() : null)
                .aiManaged(Boolean.TRUE.equals(entity.getAiManaged()))
                .portfolioId(entity.getPortfolioId() != null ? entity.getPortfolioId().toString() : null)
                .parentAsin(entity.getParentAsin())
                .targetingGoal(entity.getTargetingGoal())
                .origin(entity.getOrigin())
                .amazonCampaignId(entity.getAmazonCampaignId())
                .syncedToAmazon(synced)
                .localSource(!synced)
                .tags(parseJsonList(entity.getTags()))
                .createdAt(entity.getCreatedAt() != null ? entity.getCreatedAt().format(FORMATTER) : null)
                .updatedAt(entity.getUpdatedAt() != null ? entity.getUpdatedAt().format(FORMATTER) : null)
                .build();
    }

    /**
     * Decide whether a Campaign belongs in the Amazon-synced campaign list (Req 12.7, 12.8;
     * Property 29).
     *
     * <p>A Campaign is synced <strong>iff</strong> it has a non-empty {@code amazon_campaign_id}
     * AND its creation Operation reached the {@code effective} Sync_State. A Campaign whose
     * {@code origin} is {@code amazon_import} was ingested from Amazon and therefore already exists
     * (is "effective") on the platform, so an Amazon id alone qualifies it; a locally-created
     * Campaign additionally requires its creation Operation to be {@code effective}. Any Campaign
     * that fails the gate is a local draft to be shown only with a local-source badge.</p>
     *
     * @param entity                     the campaign entity (may be {@code null})
     * @param creationOperationEffective whether the creation Operation reached {@code effective}
     * @return {@code true} iff the Campaign qualifies for the Amazon-synced list
     */
    public static boolean syncedToAmazon(CampaignEntity entity, boolean creationOperationEffective) {
        if (entity == null) return false;
        String amazonCampaignId = entity.getAmazonCampaignId();
        boolean hasAmazonCampaignId = amazonCampaignId != null && !amazonCampaignId.isBlank();
        if (!hasAmazonCampaignId) {
            return false;
        }
        boolean amazonImport = ORIGIN_AMAZON_IMPORT.equalsIgnoreCase(entity.getOrigin());
        return amazonImport || creationOperationEffective;
    }

    /**
     * Project an entity onto the database-free {@link CampaignView} consumed by
     * the pure {@link com.adpilot.modules.advertising.support.CampaignFilter}
     * predicate (Req 19.4). {@code adType} is taken from the campaign type
     * (SP / SB / SD) and {@code recentAcos} from the campaign's realized ACoS.
     */
    public static CampaignView toView(CampaignEntity entity) {
        if (entity == null) return null;
        return new CampaignView(
                entity.getStoreId() != null ? entity.getStoreId().toString() : null,
                entity.getCampaignType(),
                entity.getPortfolioId() != null ? entity.getPortfolioId().toString() : null,
                entity.getParentAsin(),
                entity.getTargetingGoal(),
                entity.getStatus(),
                entity.getTargetAcos(),
                entity.getAcos(),
                Boolean.TRUE.equals(entity.getAiManaged()),
                Boolean.TRUE.equals(entity.getHostingEnabled()));
    }

    private static List<String> parseJsonList(String json) {        if (json == null || json.isBlank()) return Collections.emptyList();
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }
}
