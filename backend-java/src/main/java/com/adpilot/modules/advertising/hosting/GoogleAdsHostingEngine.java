package com.adpilot.modules.advertising.hosting;

import com.adpilot.modules.advertising.service.HostingAdjustmentType;
import com.adpilot.modules.advertising.service.HostingPhase;
import com.adpilot.modules.advertising.support.RiskScoreCalculator;
import com.adpilot.modules.advertising.support.RiskScoreInput;
import com.adpilot.modules.advertising.support.RiskScoreResult;
import com.adpilot.modules.advertising.support.SafetyBoundary;
import com.adpilot.modules.advertising.support.SafetyBoundaryLimit;
import com.adpilot.modules.apisync.vo.GoogleAdsCampaignVo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * The net-new Google-Ads-specific AI optimization engine (platform-workspace-rbac Req 8).
 *
 * <p>Mirrors the {@link V1BidEngine} / {@link V2BudgetEngineImpl} pattern: it reads a Google Ads
 * campaign's aggregated performance over the resolved personality policy's lookback window and
 * produces {@link CandidateDecision} objects (campaign-create and budget / status adjustments)
 * for the platform-generic {@link OptimizationCoordinator} — it never creates Operations directly
 * (Req 8.1, 8.2). The {@code GoogleAdsHostingService} wiring layer hands these candidates to the
 * coordinator and turns accepted survivors into {@code platform_mutation} Operations with
 * {@link com.adpilot.modules.advertising.operation.OperationSource#AI_HOSTING} (Req 8.3).</p>
 *
 * <p><b>Safety-boundary clamping (Req 8.5).</b> Every numeric proposed value (budget, campaign-create
 * initial budget) is clamped within the resolved {@link SafetyBoundary} before the candidate is
 * emitted, so the candidate already lies within the boundary independent of the coordinator's own
 * clipping. Status changes (pause) and campaign-create carry no in-place numeric before/after, so
 * only their initial-budget value (for create) is clamped.</p>
 *
 * <p><b>Change types.</b> The engine emits the change types the existing {@code GoogleAdsWriteConnector}
 * routes — {@code budget} (campaign daily budget) and {@code state} (enable/pause) — plus
 * {@code create} for campaign-create candidates (surfaced as a recommendation; only emitted when the
 * context explicitly enables it). Bid adjustment is gated on a current campaign bid being available;
 * the campaign-level read VO does not expose one today, so no bid candidate is produced until
 * ad-group / keyword data is surfaced.</p>
 *
 * <p>Data-quality gating (Req 8.8) is performed by the wiring layer before this engine is invoked;
 * the engine assumes the supplied campaign data already passed the freshness/completeness check.</p>
 *
 * <p>Validates: Requirements 8.1, 8.2, 8.5.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GoogleAdsHostingEngine {

    /** Engine identifier recorded on every candidate this engine produces. */
    public static final String ENGINE_GOOGLE_ADS = "GOOGLE_ADS";

    static final String ENTITY_TYPE_CAMPAIGN = "campaign";
    static final String FIELD_DAILY_BUDGET = "daily_budget";
    static final String FIELD_STATUS = "status";

    static final String CHANGE_TYPE_BUDGET = "budget";
    static final String CHANGE_TYPE_STATE = "state";
    static final String CHANGE_TYPE_CREATE = "create";

    /** Status values for which a paused campaign is considered "active" / enabled. */
    private static final String STATUS_ENABLED = "ENABLED";
    private static final String STATUS_PAUSED = "PAUSED";

    /** ACoS sentinel when a campaign spent money but produced no conversion value. */
    static final BigDecimal NO_SALES_ACOS = new BigDecimal("99999");

    private static final MathContext MC = new MathContext(16, RoundingMode.HALF_UP);
    private static final int BUDGET_SCALE = 2;
    private static final BigDecimal RISK_STATUS_PAUSE = new BigDecimal("0.40");
    private static final BigDecimal RISK_CAMPAIGN_CREATE = new BigDecimal("0.70");

    private final RiskScoreCalculator riskScoreCalculator;

    /**
     * Produce all candidate decisions for one Google Ads campaign over the lookback window.
     *
     * @param storeId  the independent-site store that owns the campaign
     * @param campaign the campaign with aggregated lookback metrics (cost, conversions, value, budget)
     * @param boundary the resolved safety boundary for this campaign
     * @param phase    the active hosting phase (budget candidates require {@link HostingAdjustmentType#BUDGET})
     * @param ctx      the resolved personality-policy inputs
     * @return the list of emitted candidates (possibly empty); never {@code null}
     */
    public List<CandidateDecision> produceCandidates(UUID storeId,
                                                     GoogleAdsCampaignVo campaign,
                                                     SafetyBoundary boundary,
                                                     HostingPhase phase,
                                                     GoogleAdsHostingContext ctx) {
        return produceWithReason(storeId, campaign, boundary, phase, ctx).candidates();
    }

    /**
     * Produce candidates with a per-campaign skip reason for the no-candidate case (Req 8.8 logging).
     */
    public EngineResult produceWithReason(UUID storeId,
                                          GoogleAdsCampaignVo campaign,
                                          SafetyBoundary boundary,
                                          HostingPhase phase,
                                          GoogleAdsHostingContext ctx) {
        if (storeId == null || campaign == null || ctx == null) {
            return EngineResult.skipped("INVALID_INPUT");
        }
        UUID campaignUuid = campaignUuid(campaign.getCampaignId());
        BigDecimal acos = computeAcos(campaign);

        List<CandidateDecision> candidates = new ArrayList<>();

        // --- Status: pause a campaign that is spending with zero conversions (Req 8.1) ---
        CandidateDecision pause = maybePauseCandidate(storeId, campaign, campaignUuid, acos, ctx);
        if (pause != null) {
            candidates.add(pause);
        }

        // --- Budget adjustment (Req 8.1, 8.5) ---
        if (phase != null && phase.supports(HostingAdjustmentType.BUDGET)) {
            CandidateDecision budget = maybeBudgetCandidate(storeId, campaign, campaignUuid, acos, boundary, ctx);
            if (budget != null) {
                candidates.add(budget);
            }
        }

        // --- Campaign create for strong performers (Req 8.1) ---
        if (ctx.enableCampaignCreate()) {
            CandidateDecision create = maybeCreateCandidate(storeId, campaign, campaignUuid, acos, boundary, ctx);
            if (create != null) {
                candidates.add(create);
            }
        }

        if (candidates.isEmpty()) {
            return EngineResult.skipped("NO_CHANGE");
        }
        log.debug("GoogleAdsHostingEngine produced {} candidate(s) for campaign {} (store {})",
                candidates.size(), campaign.getCampaignId(), storeId);
        return EngineResult.of(candidates);
    }

    // ── candidate builders ────────────────────────────────────────────────────

    private CandidateDecision maybeBudgetCandidate(UUID storeId,
                                                   GoogleAdsCampaignVo campaign,
                                                   UUID campaignUuid,
                                                   BigDecimal acos,
                                                   SafetyBoundary boundary,
                                                   GoogleAdsHostingContext ctx) {
        BigDecimal targetAcos = ctx.targetAcos();
        BigDecimal currentBudget = campaign.getBudget();
        if (targetAcos == null || targetAcos.signum() <= 0) {
            return null;
        }
        if (currentBudget == null || currentBudget.signum() <= 0) {
            return null;
        }
        if (acos == null) {
            return null; // no spend/conversion signal in the window
        }

        BigDecimal proposed = computeProposedBudget(currentBudget, acos, targetAcos, ctx);
        if (proposed == null) {
            return null;
        }
        // Clamp within the resolved Safety_Boundary BEFORE emitting (Req 8.5).
        proposed = clampBudget(currentBudget, proposed, boundary, ctx);
        if (proposed.compareTo(currentBudget) == 0) {
            return null;
        }

        BigDecimal changeMagnitude = proposed.subtract(currentBudget).abs()
                .divide(currentBudget, 6, RoundingMode.HALF_UP);
        BigDecimal dollarImpact = proposed.subtract(currentBudget).abs();
        BigDecimal dataConfidence = computeDataConfidence(campaign);
        RiskScoreResult risk = riskScoreCalculator.calculate(new RiskScoreInput(
                changeMagnitude, dataConfidence, BigDecimal.ZERO, dollarImpact));

        String direction = proposed.compareTo(currentBudget) > 0 ? "INCREASE" : "DECREASE";
        String snapshot = snapshotJson(campaign, acos, targetAcos, currentBudget, proposed,
                direction, risk, ctx);

        return new CandidateDecision(
                UUID.randomUUID(), storeId, campaignUuid,
                ENTITY_TYPE_CAMPAIGN, campaignUuid, FIELD_DAILY_BUDGET,
                HostingAdjustmentType.BUDGET, CHANGE_TYPE_BUDGET, ENGINE_GOOGLE_ADS,
                currentBudget, proposed, risk.score(), null, dataConfidence, snapshot);
    }

    private CandidateDecision maybePauseCandidate(UUID storeId,
                                                  GoogleAdsCampaignVo campaign,
                                                  UUID campaignUuid,
                                                  BigDecimal acos,
                                                  GoogleAdsHostingContext ctx) {
        // Pause only an enabled campaign that spent money but produced no conversion value.
        if (!isEnabled(campaign.getStatus())) {
            return null;
        }
        boolean spentNoSales = campaign.getCost() != null && campaign.getCost().signum() > 0
                && (campaign.getConversionValue() == null || campaign.getConversionValue().signum() <= 0)
                && campaign.getConversions() <= 0d;
        if (!spentNoSales) {
            return null;
        }

        BigDecimal dataConfidence = computeDataConfidence(campaign);
        String snapshot = statusSnapshotJson(campaign, acos, ctx);

        // Status changes carry no in-place numeric value: before/proposed are null so the
        // coordinator performs no numeric clipping. The change is campaign-level like budget.
        return new CandidateDecision(
                UUID.randomUUID(), storeId, campaignUuid,
                ENTITY_TYPE_CAMPAIGN, campaignUuid, FIELD_STATUS,
                HostingAdjustmentType.BUDGET, CHANGE_TYPE_STATE, ENGINE_GOOGLE_ADS,
                null, null, RISK_STATUS_PAUSE, null, dataConfidence, snapshot);
    }

    private CandidateDecision maybeCreateCandidate(UUID storeId,
                                                   GoogleAdsCampaignVo campaign,
                                                   UUID sourceCampaignUuid,
                                                   BigDecimal acos,
                                                   SafetyBoundary boundary,
                                                   GoogleAdsHostingContext ctx) {
        BigDecimal targetAcos = ctx.targetAcos();
        if (targetAcos == null || targetAcos.signum() <= 0 || acos == null) {
            return null;
        }
        if (acos.compareTo(NO_SALES_ACOS) >= 0) {
            return null; // never scale a no-sales spender
        }
        if (campaign.getConversions() <= 0d) {
            return null;
        }
        // Strong performer: ACoS well below target → propose scaling out a sibling campaign.
        BigDecimal strongThreshold = targetAcos.multiply(ctx.strongPerformerRatio(), MC);
        if (acos.compareTo(strongThreshold) > 0) {
            return null;
        }

        BigDecimal initialBudget = campaign.getBudget() != null && campaign.getBudget().signum() > 0
                ? campaign.getBudget()
                : BigDecimal.ZERO;
        // Clamp the new campaign's initial daily budget to the safety ceiling/floor (Req 8.5).
        initialBudget = clampToBudgetBounds(initialBudget, boundary);

        BigDecimal dataConfidence = computeDataConfidence(campaign);
        String snapshot = createSnapshotJson(campaign, acos, targetAcos, initialBudget, ctx);

        // The created campaign is a new entity; mint a fresh id for it. beforeValue is null
        // (nothing exists yet); proposedValue carries the initial daily budget.
        return new CandidateDecision(
                UUID.randomUUID(), storeId, sourceCampaignUuid,
                ENTITY_TYPE_CAMPAIGN, UUID.randomUUID(), FIELD_DAILY_BUDGET,
                HostingAdjustmentType.BUDGET, CHANGE_TYPE_CREATE, ENGINE_GOOGLE_ADS,
                null, initialBudget.signum() > 0 ? initialBudget : null,
                RISK_CAMPAIGN_CREATE, null, dataConfidence, snapshot);
    }

    // ── math ───────────────────────────────────────────────────────────────────

    /**
     * Compute the campaign's ACoS as cost / conversion value over the window.
     * Returns {@code null} when there is no signal (no cost and no value), and the
     * {@link #NO_SALES_ACOS} sentinel when there was cost but no conversion value.
     */
    BigDecimal computeAcos(GoogleAdsCampaignVo campaign) {
        BigDecimal cost = campaign.getCost() == null ? BigDecimal.ZERO : campaign.getCost();
        BigDecimal value = campaign.getConversionValue() == null ? BigDecimal.ZERO : campaign.getConversionValue();
        if (cost.signum() <= 0 && value.signum() <= 0) {
            return null;
        }
        if (value.signum() <= 0) {
            return NO_SALES_ACOS;
        }
        return cost.divide(value, 6, RoundingMode.HALF_UP);
    }

    /**
     * Compute the proposed daily budget from ACoS performance, mirroring the V2 direction rules:
     * increase when ACoS is below target, decrease when ACoS exceeds target*(1+tolerance), bounded
     * by the personality policy's increase/decrease ratios. Returns {@code null} for no change.
     */
    BigDecimal computeProposedBudget(BigDecimal currentBudget,
                                     BigDecimal acos,
                                     BigDecimal targetAcos,
                                     GoogleAdsHostingContext ctx) {
        BigDecimal decreaseThreshold = targetAcos.multiply(
                BigDecimal.ONE.add(ctx.acosToleranceRatio(), MC), MC);

        if (acos.compareTo(targetAcos) < 0) {
            // Below target — scale up, proportional to how far below, capped by the increase ratio.
            BigDecimal ratio = targetAcos.subtract(acos, MC).divide(targetAcos, MC);
            BigDecimal factor = ratio.min(ctx.maxBudgetIncreaseRatio());
            BigDecimal proposed = currentBudget.add(currentBudget.multiply(factor, MC), MC)
                    .setScale(BUDGET_SCALE, RoundingMode.HALF_UP);
            return proposed.compareTo(currentBudget) > 0 ? proposed : null;
        } else if (acos.compareTo(decreaseThreshold) > 0) {
            // Above tolerance — scale down, proportional to overage, capped by the decrease ratio.
            BigDecimal ratio = acos.subtract(targetAcos, MC).divide(targetAcos, MC);
            BigDecimal factor = ratio.min(ctx.maxBudgetDecreaseRatio());
            BigDecimal proposed = currentBudget.subtract(currentBudget.multiply(factor, MC), MC)
                    .setScale(BUDGET_SCALE, RoundingMode.HALF_UP);
            return proposed.compareTo(currentBudget) < 0 ? proposed : null;
        }
        return null; // within tolerance
    }

    /**
     * Clamp a proposed budget within the resolved Safety_Boundary's absolute min/max daily budget
     * and the per-run increase/decrease ratio relative to the current budget (Req 8.5).
     */
    BigDecimal clampBudget(BigDecimal currentBudget,
                           BigDecimal proposed,
                           SafetyBoundary boundary,
                           GoogleAdsHostingContext ctx) {
        BigDecimal result = proposed;

        // Per-run ratio caps relative to current.
        if (result.compareTo(currentBudget) > 0) {
            BigDecimal maxInc = boundary.get(SafetyBoundaryLimit.MAX_DAILY_BUDGET_INCREASE_RATIO)
                    .orElse(ctx.maxBudgetIncreaseRatio());
            BigDecimal ceiling = currentBudget.add(currentBudget.multiply(maxInc, MC), MC);
            if (result.compareTo(ceiling) > 0) {
                result = ceiling;
            }
        } else if (result.compareTo(currentBudget) < 0) {
            BigDecimal maxDec = boundary.get(SafetyBoundaryLimit.MAX_DAILY_BUDGET_DECREASE_RATIO)
                    .orElse(ctx.maxBudgetDecreaseRatio());
            BigDecimal floor = currentBudget.subtract(currentBudget.multiply(maxDec, MC), MC);
            if (result.compareTo(floor) < 0) {
                result = floor;
            }
        }

        result = clampToBudgetBounds(result, boundary);
        return result.setScale(BUDGET_SCALE, RoundingMode.HALF_UP);
    }

    /** Clamp a value to the absolute [MIN_DAILY_BUDGET, MAX_DAILY_BUDGET] boundary, when defined. */
    BigDecimal clampToBudgetBounds(BigDecimal value, SafetyBoundary boundary) {
        BigDecimal result = value;
        BigDecimal min = boundary.get(SafetyBoundaryLimit.MIN_DAILY_BUDGET).orElse(null);
        if (min != null && result.compareTo(min) < 0) {
            result = min;
        }
        BigDecimal max = boundary.get(SafetyBoundaryLimit.MAX_DAILY_BUDGET).orElse(null);
        if (max != null && result.compareTo(max) > 0) {
            result = max;
        }
        return result.setScale(BUDGET_SCALE, RoundingMode.HALF_UP);
    }

    /**
     * Data confidence in [0,1] from total clicks: clicks / (clicks + 50). Higher click volume
     * yields higher confidence with diminishing returns, matching the V1 engine's blend intent.
     */
    BigDecimal computeDataConfidence(GoogleAdsCampaignVo campaign) {
        long clicks = Math.max(0L, campaign.getClicks());
        return BigDecimal.valueOf(clicks)
                .divide(BigDecimal.valueOf(clicks + 50), 6, RoundingMode.HALF_UP);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private boolean isEnabled(String status) {
        if (status == null) {
            return false;
        }
        String s = status.trim().toUpperCase();
        return s.equals(STATUS_ENABLED) || s.equals("ENABLE") || s.equals("ACTIVE");
    }

    /**
     * Deterministically map a Google Ads external campaign id (a string) to a stable UUID, so the
     * candidate/Operation/in-flight-conflict keying is consistent across runs for the same campaign.
     */
    static UUID campaignUuid(String externalCampaignId) {
        String key = "google_ads:campaign:" + (externalCampaignId == null ? "unknown" : externalCampaignId);
        return UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8));
    }

    private String snapshotJson(GoogleAdsCampaignVo campaign, BigDecimal acos, BigDecimal targetAcos,
                                BigDecimal current, BigDecimal proposed, String direction,
                                RiskScoreResult risk, GoogleAdsHostingContext ctx) {
        StringBuilder sb = new StringBuilder("{");
        sb.append("\"engine\":\"").append(ENGINE_GOOGLE_ADS).append("\"");
        sb.append(",\"changeType\":\"").append(CHANGE_TYPE_BUDGET).append("\"");
        sb.append(",\"campaignId\":\"").append(safe(campaign.getCampaignId())).append("\"");
        sb.append(",\"personality\":\"").append(safe(ctx.personality())).append("\"");
        sb.append(",\"ruleVersion\":\"").append(safe(ctx.ruleVersion())).append("\"");
        sb.append(",\"acos\":").append(plain(acos));
        sb.append(",\"targetAcos\":").append(plain(targetAcos));
        sb.append(",\"currentBudget\":").append(plain(current));
        sb.append(",\"proposedBudget\":").append(plain(proposed));
        sb.append(",\"direction\":\"").append(direction).append("\"");
        sb.append(",\"riskScore\":").append(plain(risk.score()));
        sb.append(",\"formulaVersion\":\"").append(risk.formulaVersion()).append("\"");
        sb.append(",\"lookbackDays\":").append(ctx.lookbackDays());
        sb.append("}");
        return sb.toString();
    }

    private String statusSnapshotJson(GoogleAdsCampaignVo campaign, BigDecimal acos, GoogleAdsHostingContext ctx) {
        StringBuilder sb = new StringBuilder("{");
        sb.append("\"engine\":\"").append(ENGINE_GOOGLE_ADS).append("\"");
        sb.append(",\"changeType\":\"").append(CHANGE_TYPE_STATE).append("\"");
        sb.append(",\"campaignId\":\"").append(safe(campaign.getCampaignId())).append("\"");
        sb.append(",\"personality\":\"").append(safe(ctx.personality())).append("\"");
        sb.append(",\"ruleVersion\":\"").append(safe(ctx.ruleVersion())).append("\"");
        sb.append(",\"acos\":").append(plain(acos));
        sb.append(",\"proposedStatus\":\"").append(STATUS_PAUSED).append("\"");
        sb.append(",\"reason\":\"spend_without_conversions\"");
        sb.append(",\"cost\":").append(plain(campaign.getCost()));
        sb.append("}");
        return sb.toString();
    }

    private String createSnapshotJson(GoogleAdsCampaignVo campaign, BigDecimal acos, BigDecimal targetAcos,
                                      BigDecimal initialBudget, GoogleAdsHostingContext ctx) {
        StringBuilder sb = new StringBuilder("{");
        sb.append("\"engine\":\"").append(ENGINE_GOOGLE_ADS).append("\"");
        sb.append(",\"changeType\":\"").append(CHANGE_TYPE_CREATE).append("\"");
        sb.append(",\"sourceCampaignId\":\"").append(safe(campaign.getCampaignId())).append("\"");
        sb.append(",\"personality\":\"").append(safe(ctx.personality())).append("\"");
        sb.append(",\"ruleVersion\":\"").append(safe(ctx.ruleVersion())).append("\"");
        sb.append(",\"acos\":").append(plain(acos));
        sb.append(",\"targetAcos\":").append(plain(targetAcos));
        sb.append(",\"initialBudget\":").append(plain(initialBudget));
        sb.append(",\"reason\":\"strong_performer_scale_out\"");
        sb.append("}");
        return sb.toString();
    }

    private static String plain(BigDecimal v) {
        return v == null ? "null" : v.toPlainString();
    }

    private static String safe(String v) {
        return v == null ? "" : v.replace("\"", "'");
    }

    /**
     * Result carrying produced candidates and an optional per-campaign skip reason
     * (mirrors {@link V2BudgetEngine.EvaluationResult}).
     */
    public record EngineResult(List<CandidateDecision> candidates, String skipReason) {
        public static EngineResult skipped(String reason) {
            return new EngineResult(Collections.emptyList(), reason);
        }

        public static EngineResult of(List<CandidateDecision> candidates) {
            return new EngineResult(candidates, null);
        }

        public boolean isSkipped() {
            return skipReason != null;
        }
    }
}
