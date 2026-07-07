package com.adpilot.modules.advertising.service;

import com.adpilot.modules.advertising.entity.CampaignEntity;
import com.adpilot.modules.advertising.entity.GoalEntity;
import com.adpilot.modules.advertising.mapper.CampaignMapper;
import com.adpilot.modules.advertising.support.AcosScale;
import com.adpilot.modules.advertising.support.GoalMetrics;
import com.adpilot.modules.advertising.support.GoalPropagationResult;
import com.adpilot.modules.advertising.vo.PerformanceSummaryVo;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Goal data correctness (Requirement 20): metrics are scoped to the Goal's <em>own</em> Campaigns
 * and a Goal edit propagates <strong>only</strong> a whitelisted set of fields downward.
 *
 * <p>Two responsibilities, each split into a <strong>pure</strong> core (so it can be property-tested
 * in isolation — Property 47, task 14.25) and a thin persistence wrapper:</p>
 * <ul>
 *   <li><b>Scoped metrics</b> (Req 20.1): {@link #aggregate(UUID, List)} aggregates only the
 *       Campaigns associated with the Goal, never the whole Store. ACoS is produced as a decimal
 *       ratio via {@link AcosScale}.</li>
 *   <li><b>Whitelisted propagation</b> (Req 20.2 / 20.3): {@link #planPropagation(GoalEntity, List)}
 *       copies <em>only</em> the target ACoS and the optimization goal onto associated Campaigns, and
 *       <em>never</em> the Goal name, budget, or personality default; it also never overwrites a
 *       Campaign that already defines its own value for a propagated field (a Campaign-level override
 *       always wins).</li>
 * </ul>
 *
 * <p>Validates: Requirements 20.1, 20.2, 20.3.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GoalMetricsService {

    /** Decimal places kept for derived monetary/ratio metrics. */
    private static final int METRIC_SCALE = 4;

    private final CampaignMapper campaignMapper;

    // =====================================================================
    // Scoped metrics (Req 20.1)
    // =====================================================================

    /**
     * Compute a Goal's metrics scoped to the Campaigns associated with that Goal (Req 20.1).
     *
     * @param goalId the goal id; must not be {@code null}
     * @return the goal-scoped {@link GoalMetrics}; never {@code null}
     */
    public GoalMetrics computeGoalMetrics(UUID goalId) {
        return aggregate(goalId, findGoalCampaigns(goalId));
    }

    /**
     * Pure aggregation of metrics over the supplied Campaigns. The caller guarantees the list is the
     * set of Campaigns associated with {@code goalId}; this method performs no Store-wide query.
     *
     * @param goalId    the goal the metrics belong to (echoed onto the result)
     * @param campaigns the Goal's associated Campaigns (a {@code null} list is treated as empty)
     * @return the aggregated {@link GoalMetrics}; never {@code null}
     */
    public static GoalMetrics aggregate(UUID goalId, List<CampaignEntity> campaigns) {
        List<CampaignEntity> scoped = campaigns == null ? Collections.emptyList() : campaigns;

        BigDecimal spend = BigDecimal.ZERO;
        BigDecimal sales = BigDecimal.ZERO;
        int orders = 0;
        long impressions = 0L;
        int clicks = 0;

        for (CampaignEntity c : scoped) {
            spend = spend.add(nvl(c.getSpend()));
            sales = sales.add(nvl(c.getSales()));
            orders += c.getOrders() != null ? c.getOrders() : 0;
            clicks += c.getClicks() != null ? c.getClicks() : 0;
            impressions += c.getImpressions() != null ? c.getImpressions() : 0L;
        }

        BigDecimal acos = sales.signum() > 0
                ? AcosScale.normalizeRatio(spend.divide(sales, AcosScale.RATIO_SCALE, RoundingMode.HALF_UP))
                : BigDecimal.ZERO;
        BigDecimal roas = spend.signum() > 0
                ? sales.divide(spend, METRIC_SCALE, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;
        BigDecimal conversionRate = clicks > 0
                ? BigDecimal.valueOf(orders).divide(BigDecimal.valueOf(clicks), METRIC_SCALE, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;
        BigDecimal avgCpc = clicks > 0
                ? spend.divide(BigDecimal.valueOf(clicks), METRIC_SCALE, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;

        return GoalMetrics.builder()
                .goalId(goalId)
                .campaignCount(scoped.size())
                .spend(spend)
                .sales(sales)
                .orders(orders)
                .impressions(impressions)
                .clicks(clicks)
                .acos(acos)
                .roas(roas)
                .conversionRate(conversionRate)
                .avgCpc(avgCpc)
                .build();
    }

    /**
     * Adapt {@link GoalMetrics} to the legacy {@link PerformanceSummaryVo} response shape. ACoS is
     * surfaced as a display percentage ({@code ratio * 100}) per the {@link AcosScale} display
     * convention, while the internal computation stays on the decimal-ratio scale.
     */
    public static PerformanceSummaryVo toPerformanceSummary(GoalMetrics metrics) {
        if (metrics == null) {
            return PerformanceSummaryVo.builder().build();
        }
        return PerformanceSummaryVo.builder()
                .spend(metrics.getSpend().doubleValue())
                .sales(metrics.getSales().doubleValue())
                .orders(metrics.getOrders())
                .impressions(metrics.getImpressions())
                .clicks(metrics.getClicks())
                .acos(AcosScale.percentForDisplay(metrics.getAcos()).doubleValue())
                .roas(metrics.getRoas().doubleValue())
                .conversionRate(metrics.getConversionRate().doubleValue())
                .avgCpc(metrics.getAvgCpc().doubleValue())
                .build();
    }

    // =====================================================================
    // Whitelisted propagation (Req 20.2, 20.3)
    // =====================================================================

    /**
     * Propagate the Goal's whitelisted fields to its associated Campaigns and persist the changes.
     * Loads the associated Campaigns, applies {@link #planPropagation(GoalEntity, List)}, and writes
     * back only the Campaigns that actually changed.
     *
     * @param goal   the updated goal; must not be {@code null}
     * @param userId the acting user id (for the audit column), may be {@code null}
     * @return the {@link GoalPropagationResult} describing updated vs. skipped-override Campaigns
     */
    public GoalPropagationResult propagateOnUpdate(GoalEntity goal, String userId) {
        if (goal == null || goal.getId() == null) {
            return new GoalPropagationResult(Collections.emptyList(), Collections.emptyList());
        }
        List<CampaignEntity> campaigns = findGoalCampaigns(goal.getId());
        GoalPropagationResult result = planPropagation(goal, campaigns);

        UUID actingUser = parseUuid(userId);
        for (CampaignEntity c : campaigns) {
            if (result.getUpdatedCampaignIds().contains(c.getId())) {
                if (actingUser != null) {
                    c.setUpdatedBy(actingUser);
                }
                campaignMapper.updateById(c);
            }
        }
        log.info("Goal {} propagation: updated {} campaign(s), {} kept their override",
                goal.getId(), result.updatedCount(), result.getSkippedOverrideCampaignIds().size());
        return result;
    }

    /**
     * Pure propagation planner. Mutates the supplied Campaign objects in place, copying <em>only</em>
     * the Goal's target ACoS and optimization goal onto Campaigns that do not already define their own
     * value for that field, and returns which Campaigns were changed and which kept an override.
     *
     * <p>Guarantees, for every Campaign in {@code campaigns}:</p>
     * <ul>
     *   <li>the Campaign {@code name}, {@code budget}/{@code budgetType}, and
     *       {@code campaignPersonality} are <strong>never</strong> modified (Req 20.2);</li>
     *   <li>a Campaign that already has a non-null target ACoS keeps it; a Campaign that already has a
     *       non-blank optimization goal keeps it (Req 20.3 — override always wins);</li>
     *   <li>only an unset (overridable) field receives the Goal's value, and only when the Goal
     *       actually carries that value.</li>
     * </ul>
     *
     * @param goal      the source Goal; must not be {@code null}
     * @param campaigns the Goal's associated Campaigns (a {@code null} list is treated as empty)
     * @return the {@link GoalPropagationResult}
     */
    public static GoalPropagationResult planPropagation(GoalEntity goal, List<CampaignEntity> campaigns) {
        if (goal == null || campaigns == null || campaigns.isEmpty()) {
            return new GoalPropagationResult(Collections.emptyList(), Collections.emptyList());
        }

        // The Goal's propagatable values, normalized. target_acos is kept on the AcosScale
        // decimal-ratio scale so it lands on Campaigns consistently with how it is stored/compared.
        BigDecimal goalAcos = goal.getTargetAcos() != null
                ? AcosScale.normalizeRatio(goal.getTargetAcos())
                : null;
        String goalOptimizationGoal = blankToNull(goal.getType());

        List<UUID> updated = new ArrayList<>();
        List<UUID> skippedOverride = new ArrayList<>();

        for (CampaignEntity c : campaigns) {
            boolean changed = false;
            boolean keptOverride = false;

            // --- target ACoS ---
            if (goalAcos != null) {
                if (hasTargetAcosOverride(c)) {
                    keptOverride = true; // Req 20.3: campaign-level target ACoS wins.
                } else {
                    c.setTargetAcos(goalAcos);
                    changed = true;
                }
            }

            // --- optimization goal ---
            if (goalOptimizationGoal != null) {
                if (hasOptimizationGoalOverride(c)) {
                    keptOverride = true; // Req 20.3: campaign-level optimization goal wins.
                } else {
                    c.setHostingGoal(goalOptimizationGoal);
                    changed = true;
                }
            }

            // Req 20.2: name, budget, and personality default are NEVER propagated. We deliberately
            // do not touch c.name, c.budget, c.budgetType, or c.campaignPersonality here.

            if (changed) {
                updated.add(c.getId());
            }
            if (keptOverride) {
                skippedOverride.add(c.getId());
            }
        }

        return new GoalPropagationResult(updated, skippedOverride);
    }

    /** A Campaign-level target ACoS override exists when the Campaign carries its own value. */
    public static boolean hasTargetAcosOverride(CampaignEntity campaign) {
        return campaign != null && campaign.getTargetAcos() != null;
    }

    /** A Campaign-level optimization-goal override exists when the Campaign carries its own value. */
    public static boolean hasOptimizationGoalOverride(CampaignEntity campaign) {
        return campaign != null && blankToNull(campaign.getHostingGoal()) != null;
    }

    // =====================================================================
    // Helpers
    // =====================================================================

    private List<CampaignEntity> findGoalCampaigns(UUID goalId) {
        if (goalId == null) {
            return Collections.emptyList();
        }
        LambdaQueryWrapper<CampaignEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(CampaignEntity::getGoalId, goalId);
        List<CampaignEntity> campaigns = campaignMapper.selectList(wrapper);
        return campaigns != null ? campaigns : Collections.emptyList();
    }

    private static BigDecimal nvl(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static UUID parseUuid(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
