package com.adpilot.modules.advertising.service.impl;

import com.adpilot.modules.advertising.entity.*;
import com.adpilot.modules.advertising.mapper.*;
import com.adpilot.modules.advertising.service.RecommendationEngineService;
import com.adpilot.modules.advertising.support.AcosScale;
import com.adpilot.modules.advertising.support.RecommendationDeduplicator;
import com.adpilot.modules.advertising.support.StoreScopeRef;
import com.adpilot.common.security.CurrentUser;
import com.adpilot.common.security.DataScopeService;
import com.adpilot.common.utils.SecurityUtils;
import com.adpilot.modules.product.entity.ProductEntity;
import com.adpilot.modules.product.mapper.ProductMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/**
 * Hardened recommendation engine (Requirement 18). The engine is:
 * <ul>
 *   <li><strong>Null-safe</strong> — every record metric is read through a null-coalescing helper so
 *       no evaluation raises a null-reference error (Req 18.1);</li>
 *   <li><strong>Bid-baseline aware</strong> — a record with no bid falls back to its Ad_Group default
 *       bid, and bid recommendations are skipped when neither a bid nor a default bid exists
 *       (Req 18.2, 18.3);</li>
 *   <li><strong>Zero-sales correct</strong> — a record with no sales is treated as sales {@code 0.00}
 *       and evaluated under the spend-without-sales (waste) rule rather than the ACoS-threshold rule,
 *       because ACoS is undefined at zero sales (Req 18.4);</li>
 *   <li><strong>Deduplicated</strong> — at most one Recommendation per (target, change) is produced,
 *       using the reusable {@link RecommendationDeduplicator} (also reused by smart diagnosis)
 *       (Req 18.5);</li>
 *   <li><strong>Target-ACoS resolving</strong> — the target ACoS is resolved in the order Goal target
 *       ACoS → product {@code target_acos} → configurable system default {@code 0.25} (Req 18.6, 18.7);</li>
 *   <li><strong>Store-scoped</strong> — generation reads and writes only the requested Store's records
 *       (Req 18.8).</li>
 * </ul>
 *
 * <p>All ACoS comparisons are performed on decimal ratios through {@link AcosScale} (Requirement 17);
 * percentages are produced only for human-readable display copy.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RecommendationEngineServiceImpl implements RecommendationEngineService {

    private final RecommendationMapper recommendationMapper;
    private final KeywordMapper keywordMapper;
    private final TargetMapper targetMapper;
    private final CampaignMapper campaignMapper;
    private final GoalMapper goalMapper;
    private final AdGroupMapper adGroupMapper;
    private final CampaignProductLinkMapper campaignProductLinkMapper;
    private final ProductMapper productMapper;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Bid multiplier applied when proposing a bid decrease for an over-target keyword. */
    private static final BigDecimal BID_DECREASE_FACTOR = new BigDecimal("0.8");
    /** Bid multiplier applied when proposing a bid increase for a high-CVR keyword. */
    private static final BigDecimal BID_INCREASE_FACTOR = new BigDecimal("1.2");
    /** Budget multiplier applied when proposing a budget increase for a high-ROAS campaign. */
    private static final BigDecimal BUDGET_INCREASE_FACTOR = new BigDecimal("1.2");
    /** Over-target multiplier for the High-ACoS rule (ACoS &gt; target * 1.3). */
    private static final BigDecimal HIGH_ACOS_MULTIPLIER = new BigDecimal("1.3");

    /**
     * The system default target ACoS used when neither a Goal target ACoS nor a product
     * {@code target_acos} is configured (Req 18.6c). It is a <strong>configurable backend value</strong>
     * (Req 18.7), defaulting to {@code 0.25} (25%) expressed as a decimal ratio.
     */
    @Value("${adpilot.recommendation.default-target-acos:0.25}")
    private BigDecimal defaultTargetAcos;

    /**
     * Shared data-scope guard (Req 24/25). Field-injected and optional so the pure
     * generation logic can still be constructed directly in unit/property tests
     * (which run without a security context). When an interactive user is present,
     * generation is rejected for a Store outside the caller's effective scope.
     */
    @Autowired(required = false)
    private DataScopeService dataScopeService;

    @Override
    @Transactional
    public int generateRecommendations(String storeId) {
        if (storeId == null || storeId.isBlank()) {
            return 0;
        }
        final UUID storeUuid = UUID.fromString(storeId);

        // Recommendation generation is requested for a Store; reject when the caller
        // does not own that Store (Req 25.3) without disclosing its contents. Skipped
        // for non-interactive (scheduled/background) contexts with no acting user.
        if (dataScopeService != null && SecurityUtils.isAuthenticated()) {
            CurrentUser user = SecurityUtils.getCurrentUser();
            dataScopeService.assertCanWrite(StoreScopeRef.of(storeUuid), user);
        }

        // --- Load all store-scoped reference data once (Req 18.8: requested Store only) ---
        Map<UUID, CampaignEntity> campaignById = byId(
                selectByStore(campaignMapper, CampaignEntity::getStoreId, storeUuid),
                CampaignEntity::getId);
        Map<UUID, GoalEntity> goalById = byId(
                selectByStore(goalMapper, GoalEntity::getStoreId, storeUuid),
                GoalEntity::getId);
        Map<UUID, AdGroupEntity> adGroupById = byId(
                selectByStore(adGroupMapper, AdGroupEntity::getStoreId, storeUuid),
                AdGroupEntity::getId);

        // Resolved product target ACoS per campaign (Req 18.6b), via campaign_product_links → products.
        Map<UUID, BigDecimal> productTargetAcosByCampaign =
                resolveProductTargetAcosByCampaign(storeUuid);

        // --- Deduplicator seeded with existing not-yet-resolved Recommendations (Req 18.5) ---
        RecommendationDeduplicator dedup = new RecommendationDeduplicator();
        seedExistingRecommendations(storeUuid, dedup);

        int count = 0;

        // Enabled keywords for this store.
        LambdaQueryWrapper<KeywordEntity> keywordWrapper = new LambdaQueryWrapper<>();
        keywordWrapper.eq(KeywordEntity::getStoreId, storeUuid);
        keywordWrapper.eq(KeywordEntity::getStatus, "enabled");
        List<KeywordEntity> keywords = nullSafe(keywordMapper.selectList(keywordWrapper));

        // Store-level averages, computed null-safely (used by CVR/ROAS heuristics).
        double avgCvr = keywords.stream()
                .map(KeywordEntity::getCvr).filter(Objects::nonNull)
                .mapToDouble(BigDecimal::doubleValue).average().orElse(0);
        double avgImpressions = keywords.stream()
                .map(KeywordEntity::getImpressions).filter(Objects::nonNull)
                .mapToDouble(Long::doubleValue).average().orElse(0);

        // ---- Keyword rules ----
        for (KeywordEntity keyword : keywords) {
            BigDecimal spend = nz(keyword.getSpend());
            BigDecimal sales = nz(keyword.getSales());
            BigDecimal acos = nz(keyword.getAcos());
            int clicks = nz(keyword.getClicks());
            int orders = nz(keyword.getOrders());
            BigDecimal cvr = nz(keyword.getCvr());
            long impressions = nz(keyword.getImpressions());

            boolean hasSales = sales.signum() > 0;
            BigDecimal targetAcos = resolveTargetAcos(keyword.getCampaignId(), campaignById,
                    goalById, productTargetAcosByCampaign);

            // Rule 1 — High ACoS (ACoS-threshold rule). Only valid when sales > 0, because ACoS is
            // undefined at zero sales (Req 18.4); routed to the waste rule below otherwise.
            if (hasSales && spend.compareTo(BigDecimal.valueOf(20)) > 0) {
                BigDecimal acosThreshold = targetAcos.multiply(HIGH_ACOS_MULTIPLIER);
                if (AcosScale.compareRatios(acos, acosThreshold) > 0) {
                    BigDecimal baselineBid = resolveBaselineBid(keyword.getBid(), keyword.getAdGroupId(), adGroupById);
                    // Bid recommendation skipped when neither a bid nor a default bid exists (Req 18.3).
                    if (baselineBid != null && dedup.tryEmit("keyword", keyword.getId(), "decrease_bid")) {
                        BigDecimal newBid = baselineBid.multiply(BID_DECREASE_FACTOR).setScale(4, RoundingMode.HALF_UP);
                        BigDecimal saving = spend.multiply(new BigDecimal("0.2"));
                        createRecommendation(storeUuid, keyword, "decrease_bid", "high",
                                "High ACoS: " + acosPct(acos) + "%",
                                "Decrease bid from " + baselineBid.toPlainString() + " to " + newBid.toPlainString(),
                                "ACoS " + acosPct(acos) + "% 超出目标 " + acosPct(targetAcos)
                                        + "% 的 30% 以上，当前竞价偏高、在亏钱。",
                                "预计降低 ACoS、约节省 $" + saving.setScale(2, RoundingMode.HALF_UP) + "/周期。",
                                baselineBid.toPlainString(), newBid.toPlainString(), saving);
                        count++;
                    }
                }
            }

            // Rule 2 — Waste spend (spend-without-sales rule). Triggered when a record has no sales
            // yet has accumulated clicks and spend (Req 18.4).
            if (!hasSales && clicks >= 15 && spend.compareTo(BigDecimal.valueOf(15)) >= 0) {
                if (dedup.tryEmit("keyword", keyword.getId(), "add_negative")) {
                    String text = keyword.getKeywordText();
                    createRecommendation(storeUuid, keyword, "add_negative", "high",
                            "Waste Spend: " + clicks + " clicks, 0 sales",
                            "Add '" + text + "' as negative keyword",
                            clicks + " 次点击零转化，纯属浪费花费。",
                            "预计节省 $" + spend.setScale(2, RoundingMode.HALF_UP) + "/周期。",
                            text, "negative_" + text, spend);
                    count++;
                }
            }

            // Rule 3 — Winner keyword (ACoS-threshold rule). Only valid when sales > 0 (Req 18.4).
            if (hasSales && orders >= 2 && AcosScale.compareRatios(acos, targetAcos) <= 0) {
                if (dedup.tryEmit("keyword", keyword.getId(), "add_exact")) {
                    BigDecimal impact = sales.multiply(new BigDecimal("0.1"));
                    createRecommendation(storeUuid, keyword, "add_exact", "medium",
                            "Winner Keyword: " + orders + " orders at " + acosPct(acos) + "% ACoS",
                            "Harvest as exact match keyword",
                            orders + " 单且 ACoS 仅 " + acosPct(acos)
                                    + "%，是已验证的优质词，建议收割为精确匹配锁定流量。",
                            "预计带来更多稳定转化，约 +$" + impact.setScale(2, RoundingMode.HALF_UP) + "/周期。",
                            nullSafeText(keyword.getMatchType()), "exact", impact);
                    count++;
                }
            }

            // Rule 4 — Low impression, high CVR (bid increase). A bid recommendation, so it requires a
            // baseline bid (record bid or Ad_Group default), else it is skipped (Req 18.2, 18.3).
            if (cvr.doubleValue() > avgCvr * 1.3 && impressions < avgImpressions) {
                BigDecimal baselineBid = resolveBaselineBid(keyword.getBid(), keyword.getAdGroupId(), adGroupById);
                if (baselineBid != null && dedup.tryEmit("keyword", keyword.getId(), "increase_bid")) {
                    BigDecimal newBid = baselineBid.multiply(BID_INCREASE_FACTOR).setScale(4, RoundingMode.HALF_UP);
                    BigDecimal impact = sales.multiply(new BigDecimal("0.15"));
                    createRecommendation(storeUuid, keyword, "increase_bid", "medium",
                            "Low Impression High CVR: " + cvr.setScale(2, RoundingMode.HALF_UP) + " CVR, " + impressions + " impressions",
                            "Increase bid from " + baselineBid.toPlainString() + " to " + newBid.toPlainString(),
                            "转化率高于均值但曝光偏低，提高竞价可释放更多优质流量。",
                            "预计提升曝光与订单，约 +$" + impact.setScale(2, RoundingMode.HALF_UP) + "/周期。",
                            baselineBid.toPlainString(), newBid.toPlainString(), impact);
                    count++;
                }
            }
        }

        // ---- Campaign rule: Budget reallocation (Rule 5) ----
        double avgRoas = campaignById.values().stream()
                .map(CampaignEntity::getRoas).filter(Objects::nonNull)
                .mapToDouble(BigDecimal::doubleValue).average().orElse(0);

        for (CampaignEntity campaign : campaignById.values()) {
            if (!"enabled".equals(campaign.getStatus())) {
                continue;
            }
            BigDecimal roas = nz(campaign.getRoas());
            BigDecimal budget = nz(campaign.getBudget());
            BigDecimal spend = nz(campaign.getSpend());
            BigDecimal sales = nz(campaign.getSales());
            if (budget.signum() <= 0) {
                continue; // budget-usage undefined without a positive budget
            }
            double budgetUsage = spend.doubleValue() / budget.doubleValue() * 100;
            if (roas.doubleValue() > avgRoas * 1.3 && budgetUsage > 80) {
                if (dedup.tryEmit("campaign", campaign.getId(), "increase_budget")) {
                    BigDecimal newBudget = budget.multiply(BUDGET_INCREASE_FACTOR).setScale(2, RoundingMode.HALF_UP);
                    BigDecimal impact = sales.multiply(new BigDecimal("0.1"));
                    createCampaignRecommendation(storeUuid, campaign, "increase_budget", "medium",
                            "High ROAS Campaign: " + roas.setScale(1, RoundingMode.HALF_UP) + "x ROAS, " + (int) budgetUsage + "% budget used",
                            "Increase budget from $" + budget.toPlainString() + " to $" + newBudget.toPlainString(),
                            "ROAS " + roas.setScale(1, RoundingMode.HALF_UP) + "x 高于均值且预算已用 "
                                    + (int) budgetUsage + "%，加预算可放大一个盈利广告活动。",
                            "预计带来更多销售，约 +$" + impact.setScale(2, RoundingMode.HALF_UP) + "/周期。",
                            budget.toPlainString(), newBudget.toPlainString(), impact);
                    count++;
                }
            }
        }

        // ---- Target rule: Pause waste targets (Rule 7) ----
        LambdaQueryWrapper<TargetEntity> targetWrapper = new LambdaQueryWrapper<>();
        targetWrapper.eq(TargetEntity::getStoreId, storeUuid);
        targetWrapper.eq(TargetEntity::getStatus, "enabled");
        List<TargetEntity> targets = nullSafe(targetMapper.selectList(targetWrapper));

        for (TargetEntity target : targets) {
            BigDecimal spend = nz(target.getSpend());
            BigDecimal sales = nz(target.getSales());
            // Spend-without-sales waste rule (Req 18.4): no sales but meaningful spend.
            if (sales.signum() == 0 && spend.compareTo(BigDecimal.valueOf(30)) > 0) {
                if (dedup.tryEmit("target", target.getId(), "pause_target")) {
                    createTargetRecommendation(storeUuid, target, "pause_target", "high",
                            "Waste Target: $" + spend.toPlainString() + " spend, 0 sales",
                            "Pause target '" + nullSafeText(target.getTargetingValue()) + "'",
                            "$" + spend.setScale(2, RoundingMode.HALF_UP) + " 花费零转化，属于无效投放。",
                            "预计节省 $" + spend.setScale(2, RoundingMode.HALF_UP) + "/周期。",
                            "enabled", "paused", spend);
                    count++;
                }
            }
        }

        log.info("Generated {} recommendations for store {}", count, storeId);
        return count;
    }

    // ------------------------------------------------------------------
    // Resolution helpers
    // ------------------------------------------------------------------

    /**
     * Resolve the target ACoS for a campaign in the order Goal target ACoS → product
     * {@code target_acos} → configurable system default (Req 18.6). All values are returned as
     * normalized decimal ratios.
     */
    private BigDecimal resolveTargetAcos(UUID campaignId,
                                         Map<UUID, CampaignEntity> campaignById,
                                         Map<UUID, GoalEntity> goalById,
                                         Map<UUID, BigDecimal> productTargetAcosByCampaign) {
        // (a) Campaign's associated Goal target ACoS
        if (campaignId != null) {
            CampaignEntity campaign = campaignById.get(campaignId);
            if (campaign != null && campaign.getGoalId() != null) {
                GoalEntity goal = goalById.get(campaign.getGoalId());
                if (goal != null && goal.getTargetAcos() != null && goal.getTargetAcos().signum() > 0) {
                    return AcosScale.normalizeRatio(goal.getTargetAcos());
                }
            }
            // (b) Product target_acos
            BigDecimal productAcos = productTargetAcosByCampaign.get(campaignId);
            if (productAcos != null && productAcos.signum() > 0) {
                return AcosScale.normalizeRatio(productAcos);
            }
        }
        // (c) Configurable system default (Req 18.6c / 18.7)
        BigDecimal fallback = defaultTargetAcos != null ? defaultTargetAcos : new BigDecimal("0.25");
        return AcosScale.normalizeRatio(fallback);
    }

    /**
     * Resolve the baseline bid for a bid recommendation (Req 18.2/18.3): the record's own bid when
     * present, otherwise the record's Ad_Group default bid, otherwise {@code null} (signalling the
     * caller to skip the bid recommendation entirely).
     */
    private BigDecimal resolveBaselineBid(BigDecimal recordBid, UUID adGroupId,
                                          Map<UUID, AdGroupEntity> adGroupById) {
        if (recordBid != null && recordBid.signum() > 0) {
            return recordBid;
        }
        if (adGroupId != null) {
            AdGroupEntity adGroup = adGroupById.get(adGroupId);
            if (adGroup != null && adGroup.getDefaultBid() != null && adGroup.getDefaultBid().signum() > 0) {
                return adGroup.getDefaultBid();
            }
        }
        return null;
    }

    /** Build a campaignId → product {@code target_acos} map via {@code campaign_product_links}. */
    private Map<UUID, BigDecimal> resolveProductTargetAcosByCampaign(UUID storeUuid) {
        LambdaQueryWrapper<CampaignProductLinkEntity> linkWrapper = new LambdaQueryWrapper<>();
        linkWrapper.eq(CampaignProductLinkEntity::getStoreId, storeUuid);
        List<CampaignProductLinkEntity> links = nullSafe(campaignProductLinkMapper.selectList(linkWrapper));
        if (links.isEmpty()) {
            return Collections.emptyMap();
        }

        Set<UUID> productIds = new HashSet<>();
        for (CampaignProductLinkEntity link : links) {
            if (link.getProductId() != null) {
                productIds.add(link.getProductId());
            }
        }
        Map<UUID, BigDecimal> productAcosById = new HashMap<>();
        if (!productIds.isEmpty()) {
            LambdaQueryWrapper<ProductEntity> productWrapper = new LambdaQueryWrapper<>();
            productWrapper.eq(ProductEntity::getStoreId, storeUuid);
            productWrapper.in(ProductEntity::getId, productIds);
            for (ProductEntity product : nullSafe(productMapper.selectList(productWrapper))) {
                if (product.getTargetAcos() != null && product.getTargetAcos().signum() > 0) {
                    productAcosById.put(product.getId(), product.getTargetAcos());
                }
            }
        }

        Map<UUID, BigDecimal> byCampaign = new HashMap<>();
        for (CampaignProductLinkEntity link : links) {
            if (link.getCampaignId() == null || link.getProductId() == null) {
                continue;
            }
            BigDecimal acos = productAcosById.get(link.getProductId());
            if (acos != null) {
                // Keep the first resolvable product target ACoS per campaign.
                byCampaign.putIfAbsent(link.getCampaignId(), acos);
            }
        }
        return byCampaign;
    }

    /**
     * Seed the deduplicator with keys for the store's existing, not-yet-resolved Recommendations so a
     * fresh generation pass does not recreate the same change for the same target (Req 18.5).
     */
    private void seedExistingRecommendations(UUID storeUuid, RecommendationDeduplicator dedup) {
        LambdaQueryWrapper<RecommendationEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(RecommendationEntity::getStoreId, storeUuid);
        wrapper.in(RecommendationEntity::getStatus, "pending", "applying", "watching", "local-only");
        for (RecommendationEntity rec : nullSafe(recommendationMapper.selectList(wrapper))) {
            dedup.seed(dedupKeyFor(rec));
        }
    }

    /** Derive the dedup key for an existing Recommendation from its target identity and type. */
    private static String dedupKeyFor(RecommendationEntity rec) {
        String entityType;
        UUID entityId;
        if (rec.getKeywordId() != null) {
            entityType = "keyword";
            entityId = rec.getKeywordId();
        } else if (rec.getTargetId() != null) {
            entityType = "target";
            entityId = rec.getTargetId();
        } else {
            entityType = "campaign";
            entityId = rec.getCampaignId();
        }
        return RecommendationDeduplicator.keyOf(entityType, entityId, rec.getType());
    }

    // ------------------------------------------------------------------
    // Null-safety + collection helpers
    // ------------------------------------------------------------------

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static int nz(Integer v) {
        return v == null ? 0 : v;
    }

    private static long nz(Long v) {
        return v == null ? 0L : v;
    }

    private static String nullSafeText(String v) {
        return v == null ? "" : v;
    }

    private static <T> List<T> nullSafe(List<T> list) {
        return list == null ? Collections.emptyList() : list;
    }

    /** ACoS decimal ratio → display percentage (Req 17.2), rounded to one decimal for copy. */
    private static BigDecimal acosPct(BigDecimal ratio) {
        return AcosScale.percentForDisplay(ratio).setScale(1, RoundingMode.HALF_UP);
    }

    private static <E> List<E> selectByStore(com.baomidou.mybatisplus.core.mapper.BaseMapper<E> mapper,
                                              com.baomidou.mybatisplus.core.toolkit.support.SFunction<E, ?> storeColumn,
                                              UUID storeUuid) {
        LambdaQueryWrapper<E> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(storeColumn, storeUuid);
        return nullSafe(mapper.selectList(wrapper));
    }

    private static <E> Map<UUID, E> byId(List<E> list,
                                         com.baomidou.mybatisplus.core.toolkit.support.SFunction<E, UUID> idGetter) {
        Map<UUID, E> map = new HashMap<>();
        for (E e : list) {
            UUID id = idGetter.apply(e);
            if (id != null) {
                map.put(id, e);
            }
        }
        return map;
    }

    private static String toJson(Map<String, Object> data) {
        try {
            return MAPPER.writeValueAsString(data);
        } catch (Exception e) {
            return "{}";
        }
    }

    private static Map<String, Object> keywordData(KeywordEntity k) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (k.getImpressions() != null) m.put("impressions", k.getImpressions());
        if (k.getClicks() != null) m.put("clicks", k.getClicks());
        if (k.getOrders() != null) m.put("orders", k.getOrders());
        if (k.getSpend() != null) m.put("spend", k.getSpend());
        if (k.getSales() != null) m.put("sales", k.getSales());
        if (k.getAcos() != null) m.put("acos", k.getAcos());
        if (k.getCvr() != null) m.put("cvr", k.getCvr());
        if (k.getBid() != null) m.put("currentBid", k.getBid());
        return m;
    }

    private static Map<String, Object> campaignData(CampaignEntity c) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (c.getSpend() != null) m.put("spend", c.getSpend());
        if (c.getSales() != null) m.put("sales", c.getSales());
        if (c.getAcos() != null) m.put("acos", c.getAcos());
        if (c.getRoas() != null) m.put("roas", c.getRoas());
        if (c.getBudget() != null) m.put("budget", c.getBudget());
        return m;
    }

    private static Map<String, Object> targetData(TargetEntity t) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (t.getSpend() != null) m.put("spend", t.getSpend());
        if (t.getOrders() != null) m.put("orders", t.getOrders());
        if (t.getSales() != null) m.put("sales", t.getSales());
        if (t.getAcos() != null) m.put("acos", t.getAcos());
        return m;
    }

    private static String riskFromPriority(String priority) {
        if ("high".equals(priority)) return "high";
        if ("medium".equals(priority)) return "medium";
        return "low";
    }

    // ------------------------------------------------------------------
    // Persistence
    // ------------------------------------------------------------------

    private void createRecommendation(UUID storeId, KeywordEntity keyword, String type, String priority,
                                       String title, String description, String reason, String expectedImpact,
                                       String currentValue, String recommendedValue,
                                       BigDecimal estimatedImpact) {
        RecommendationEntity rec = RecommendationEntity.builder()
                .storeId(storeId)
                .campaignId(keyword.getCampaignId())
                .keywordId(keyword.getId())
                .type(type)
                .priority(priority)
                .title(title)
                .description(description)
                .reason(reason)
                .expectedImpact(expectedImpact)
                .riskLevel(riskFromPriority(priority))
                .targetEntityType("keyword")
                .targetEntityName(keyword.getKeywordText())
                .currentData(toJson(keywordData(keyword)))
                .currentValue(currentValue)
                .recommendedValue(recommendedValue)
                .estimatedImpact(estimatedImpact != null ? estimatedImpact.setScale(2, RoundingMode.HALF_UP) : null)
                .confidence(BigDecimal.valueOf(0.8))
                .status("pending")
                .build();
        recommendationMapper.insert(rec);
    }

    private void createCampaignRecommendation(UUID storeId, CampaignEntity campaign, String type, String priority,
                                               String title, String description, String reason, String expectedImpact,
                                               String currentValue, String recommendedValue,
                                               BigDecimal estimatedImpact) {
        RecommendationEntity rec = RecommendationEntity.builder()
                .storeId(storeId)
                .campaignId(campaign.getId())
                .type(type)
                .priority(priority)
                .title(title)
                .description(description)
                .reason(reason)
                .expectedImpact(expectedImpact)
                .riskLevel(riskFromPriority(priority))
                .targetEntityType("campaign")
                .targetEntityName(campaign.getName())
                .currentData(toJson(campaignData(campaign)))
                .currentValue(currentValue)
                .recommendedValue(recommendedValue)
                .estimatedImpact(estimatedImpact != null ? estimatedImpact.setScale(2, RoundingMode.HALF_UP) : null)
                .confidence(BigDecimal.valueOf(0.75))
                .status("pending")
                .build();
        recommendationMapper.insert(rec);
    }

    private void createTargetRecommendation(UUID storeId, TargetEntity target, String type, String priority,
                                             String title, String description, String reason, String expectedImpact,
                                             String currentValue, String recommendedValue,
                                             BigDecimal estimatedImpact) {
        RecommendationEntity rec = RecommendationEntity.builder()
                .storeId(storeId)
                .campaignId(target.getCampaignId())
                .targetId(target.getId())
                .type(type)
                .priority(priority)
                .title(title)
                .description(description)
                .reason(reason)
                .expectedImpact(expectedImpact)
                .riskLevel(riskFromPriority(priority))
                .targetEntityType("target")
                .targetEntityName(target.getTargetingValue())
                .currentData(toJson(targetData(target)))
                .currentValue(currentValue)
                .recommendedValue(recommendedValue)
                .estimatedImpact(estimatedImpact != null ? estimatedImpact.setScale(2, RoundingMode.HALF_UP) : null)
                .confidence(BigDecimal.valueOf(0.85))
                .status("pending")
                .build();
        recommendationMapper.insert(rec);
    }
}
