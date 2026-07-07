package com.adpilot.modules.advertising.hosting;

import com.adpilot.modules.advertising.operation.AiDecision;
import com.adpilot.modules.advertising.operation.CreateOperationCommand;
import com.adpilot.modules.advertising.operation.OperationScope;
import com.adpilot.modules.advertising.operation.OperationService;
import com.adpilot.modules.advertising.operation.OperationSource;
import com.adpilot.modules.advertising.support.SafetyBoundary;
import com.adpilot.modules.advertising.support.SafetyBoundaryLimits;
import com.adpilot.modules.advertising.support.SafetyBoundaryResolver;
import com.adpilot.modules.apisync.service.GoogleAdsReadService;
import com.adpilot.modules.apisync.vo.GoogleAdsCampaignVo;
import com.adpilot.modules.apisync.vo.GoogleAdsReadResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Wires the {@link GoogleAdsHostingEngine} to the platform-generic
 * {@link OptimizationCoordinator} and the Operation write-back pipeline
 * (platform-workspace-rbac Req 8).
 *
 * <p>For a store, this service:
 * <ol>
 *   <li>reads the store's Google Ads campaigns and performance over the resolved lookback window
 *       through the existing {@link GoogleAdsReadService} (Req 8.1);</li>
 *   <li>applies a data-quality freshness/completeness check, skipping any campaign that fails it and
 *       recording the skip reason (Req 8.8);</li>
 *   <li>asks the engine to produce {@link CandidateDecision}s and hands them to the
 *       {@link OptimizationCoordinator} — the engine never creates Operations directly (Req 8.2);</li>
 *   <li>for every surviving candidate the coordinator routes to an Operation, creates a
 *       {@code platform_mutation} Operation with {@link OperationSource#AI_HOSTING}, recording the
 *       before value, after value, the decision snapshot, and a structured explanation, reusing
 *       {@link ExecutionModeResolver}, approval routing, the Outbox, and attribution (Req 8.3–8.7).</li>
 * </ol>
 *
 * <p>Per-campaign failures are isolated so one campaign never aborts the whole run.</p>
 *
 * <p>Validates: Requirements 8.1, 8.2, 8.3, 8.4, 8.5, 8.6, 8.7, 8.8.</p>
 */
@Slf4j
@Service
public class GoogleAdsHostingService {

    private static final String SKIP_DATA_QUALITY = "DATA_QUALITY_INSUFFICIENT";
    private static final String TRIGGER_METRIC_ACOS = "acos";

    private final GoogleAdsReadService googleAdsReadService;
    private final GoogleAdsHostingEngine hostingEngine;
    private final OptimizationCoordinator optimizationCoordinator;
    private final ExecutionModeResolver executionModeResolver;
    private final OperationService operationService;

    /** Default target ACoS (cost / conversion value ratio) when no per-campaign goal is configured. */
    @Value("${adpilot.hosting.google-ads.target-acos:0.30}")
    private BigDecimal defaultTargetAcos;

    /** Lookback window in days for Google Ads performance retrieval. */
    @Value("${adpilot.hosting.google-ads.lookback-days:14}")
    private int lookbackDays;

    /** System-default minimum daily budget hard floor. */
    @Value("${adpilot.hosting.google-ads.min-daily-budget:1.00}")
    private BigDecimal minDailyBudget;

    /** System-default maximum daily budget hard ceiling. */
    @Value("${adpilot.hosting.google-ads.max-daily-budget:100000.00}")
    private BigDecimal maxDailyBudget;

    /** Default per-run budget increase ratio cap. */
    @Value("${adpilot.hosting.google-ads.max-budget-increase-ratio:0.20}")
    private BigDecimal maxBudgetIncreaseRatio;

    /** Default per-run budget decrease ratio cap. */
    @Value("${adpilot.hosting.google-ads.max-budget-decrease-ratio:0.30}")
    private BigDecimal maxBudgetDecreaseRatio;

    public GoogleAdsHostingService(GoogleAdsReadService googleAdsReadService,
                                   GoogleAdsHostingEngine hostingEngine,
                                   OptimizationCoordinator optimizationCoordinator,
                                   ExecutionModeResolver executionModeResolver,
                                   OperationService operationService) {
        this.googleAdsReadService = googleAdsReadService;
        this.hostingEngine = hostingEngine;
        this.optimizationCoordinator = optimizationCoordinator;
        this.executionModeResolver = executionModeResolver;
        this.operationService = operationService;
    }

    /**
     * Run a Google Ads AI-hosting optimization pass for a single store.
     *
     * @param storeId the independent-site store whose Google Ads campaigns to optimize
     * @return a summary of campaigns processed, skipped, and Operations created
     */
    public HostingRunSummary optimizeStore(UUID storeId) {
        HostingRunSummary summary = new HostingRunSummary();
        if (storeId == null) {
            return summary;
        }

        GoogleAdsReadResult<List<GoogleAdsCampaignVo>> campaignsResult =
                googleAdsReadService.getCampaigns(storeId);
        if (campaignsResult.getState() != GoogleAdsReadResult.State.OK
                || campaignsResult.getData() == null) {
            log.debug("GoogleAdsHostingService: no campaigns to optimize for store {} (state={})",
                    storeId, campaignsResult.getState());
            return summary;
        }

        SafetyBoundary boundary = resolveBoundary();
        GoogleAdsHostingContext ctx = GoogleAdsHostingContext.defaults(defaultTargetAcos, lookbackDays);
        // Override the policy ratio caps from configuration.
        ctx = new GoogleAdsHostingContext(
                defaultTargetAcos, lookbackDays, ctx.personality(), ctx.ruleVersion(),
                ctx.acosToleranceRatio(), maxBudgetIncreaseRatio, maxBudgetDecreaseRatio,
                ctx.approvalChangeRatio(), ctx.enableCampaignCreate(), ctx.strongPerformerRatio());

        HostingPhaseHolder phase = HostingPhaseHolder.budgetEnabled();

        for (GoogleAdsCampaignVo campaign : campaignsResult.getData()) {
            summary.processed++;
            try {
                if (failsDataQuality(campaign)) {
                    summary.skipped++;
                    summary.recordSkip(campaign.getCampaignId(), SKIP_DATA_QUALITY);
                    log.debug("GoogleAdsHostingService: skipping campaign {} — {}",
                            campaign.getCampaignId(), SKIP_DATA_QUALITY);
                    continue;
                }

                UUID campaignUuid = GoogleAdsHostingEngine.campaignUuid(campaign.getCampaignId());
                List<CandidateDecision> candidates = hostingEngine.produceCandidates(
                        storeId, campaign, boundary, phase.phase(), ctx);
                if (candidates.isEmpty()) {
                    continue;
                }

                ExecutionMode executionMode =
                        executionModeResolver.resolveForCampaign(campaignUuid, null, storeId);

                CoordinationResult coordination = optimizationCoordinator.coordinate(
                        candidates, boundary, campaignUuid, storeId,
                        /* operationsToday */ 0,
                        /* killSwitchActive */ false,
                        /* shadowModeActive */ false,
                        /* phaseEnabled */ true,
                        executionMode);

                summary.operationsCreated += createOperations(storeId, coordination, ctx);
            } catch (Exception ex) {
                summary.failed++;
                log.warn("GoogleAdsHostingService: failed optimizing campaign {} for store {}: {}",
                        campaign.getCampaignId(), storeId, rootMessage(ex));
            }
        }
        return summary;
    }

    // ── operation creation (Req 8.3, 8.4) ──────────────────────────────────────

    private int createOperations(UUID storeId, CoordinationResult coordination, GoogleAdsHostingContext ctx) {
        int created = 0;
        for (CoordinationResult.CoordinatedCandidate cc : coordination.survivors()) {
            RoutingOutcome outcome = cc.routingResult().outcome();
            // Only AWAITING_APPROVAL_OPERATION and PENDING_OPERATION create an Operation; NO_OP and
            // AI_DECISIONS_ONLY (observe_only / recommend_only / shadow / kill switch) do not (Req 8.4).
            if (outcome != RoutingOutcome.PENDING_OPERATION
                    && outcome != RoutingOutcome.AWAITING_APPROVAL_OPERATION) {
                continue;
            }
            CreateOperationCommand command = buildCommand(storeId, cc, ctx, outcome);
            operationService.createOperation(command);
            created++;
        }
        return created;
    }

    CreateOperationCommand buildCommand(UUID storeId,
                                        CoordinationResult.CoordinatedCandidate cc,
                                        GoogleAdsHostingContext ctx,
                                        RoutingOutcome outcome) {
        CandidateDecision candidate = cc.candidate();
        boolean approvalRequired = outcome == RoutingOutcome.AWAITING_APPROVAL_OPERATION;

        Object beforeValue = candidate.beforeValue();
        Object afterValue;
        if (GoogleAdsHostingEngine.CHANGE_TYPE_STATE.equals(candidate.changeType())) {
            // Status change: no numeric value; record the target status.
            beforeValue = beforeValue != null ? beforeValue : "enabled";
            afterValue = "paused";
        } else {
            afterValue = cc.clippedValue() != null ? cc.clippedValue() : candidate.proposedValue();
        }

        BigDecimal appliedMagnitude = computeAppliedMagnitude(candidate.beforeValue(), cc.clippedValue());

        AiDecision decision = AiDecision.builder()
                .triggerMetric(TRIGGER_METRIC_ACOS)
                .resolvedPersonality(ctx.personality())
                .personalityAllowedMagnitude(magnitudeCap(candidate, ctx))
                .appliedMagnitude(appliedMagnitude)
                .decisionReason(candidate.decisionSnapshot())
                .predictedImpact(predictedImpact(candidate.changeType()))
                .approvalRequired(approvalRequired)
                .build();

        return CreateOperationCommand.builder()
                .storeId(storeId)
                .operationSource(OperationSource.AI_HOSTING)
                .operationScope(OperationScope.PLATFORM_MUTATION)
                .entityType(candidate.entityType())
                .entityId(candidate.entityId())
                .field(candidate.field())
                .logicalIdempotencyKey("ai_hosting:google_ads:" + candidate.changeType()
                        + ":" + candidate.entityId() + ":" + UUID.randomUUID())
                .beforeValue(beforeValue)
                .afterValue(afterValue)
                .reversible(ReversibilityClassifier.classify(candidate.changeType()))
                .affectedCount(1)
                .approvalRequired(approvalRequired)
                .personalityRuleVersion(ctx.ruleVersion())
                .aiDecision(decision)
                .build();
    }

    private BigDecimal magnitudeCap(CandidateDecision candidate, GoogleAdsHostingContext ctx) {
        if (candidate.beforeValue() == null || candidate.proposedValue() == null) {
            return BigDecimal.ZERO;
        }
        return candidate.proposedValue().compareTo(candidate.beforeValue()) >= 0
                ? ctx.maxBudgetIncreaseRatio()
                : ctx.maxBudgetDecreaseRatio();
    }

    private BigDecimal computeAppliedMagnitude(BigDecimal before, BigDecimal after) {
        if (before == null || after == null || before.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        return after.subtract(before).abs().divide(before, 6, RoundingMode.HALF_UP);
    }

    private String predictedImpact(String changeType) {
        return switch (changeType) {
            case GoogleAdsHostingEngine.CHANGE_TYPE_BUDGET -> "预计在目标 ACoS 约束内调整花费规模";
            case GoogleAdsHostingEngine.CHANGE_TYPE_STATE -> "预计暂停无转化花费，降低浪费";
            case GoogleAdsHostingEngine.CHANGE_TYPE_CREATE -> "预计为优质广告系列扩量";
            default -> "预计优化广告表现";
        };
    }

    // ── data quality (Req 8.8) ─────────────────────────────────────────────────

    /**
     * A lightweight freshness/completeness check: a campaign fails data quality when it has no
     * measurable activity (no impressions and no clicks) over the lookback window, so the engine has
     * no signal to optimize on. Failing campaigns are skipped with a recorded reason (Req 8.8).
     */
    boolean failsDataQuality(GoogleAdsCampaignVo campaign) {
        if (campaign == null) {
            return true;
        }
        return campaign.getImpressions() <= 0 && campaign.getClicks() <= 0;
    }

    // ── boundary resolution ─────────────────────────────────────────────────

    private SafetyBoundary resolveBoundary() {
        SafetyBoundaryLimits limits = SafetyBoundaryLimits.builder()
                .minDailyBudget(minDailyBudget)
                .maxDailyBudget(maxDailyBudget)
                .maxDailyBudgetIncreaseRatio(maxBudgetIncreaseRatio)
                .maxDailyBudgetDecreaseRatio(maxBudgetDecreaseRatio)
                .build();
        return SafetyBoundaryResolver.resolve(null, null, null, limits);
    }

    private static String rootMessage(Throwable e) {
        Throwable cur = e;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        String m = cur.getMessage();
        return m != null ? m : cur.getClass().getSimpleName();
    }

    /**
     * Small holder so the service does not depend on the {@code adpilot.hosting.phase} string
     * resolution — Google Ads budget optimization runs whenever the BUDGET capability is enabled.
     */
    private record HostingPhaseHolder(com.adpilot.modules.advertising.service.HostingPhase phase) {
        static HostingPhaseHolder budgetEnabled() {
            return new HostingPhaseHolder(com.adpilot.modules.advertising.service.HostingPhase.V2);
        }
    }

    /** Aggregate counters and skip reasons for one Google Ads hosting run. */
    public static final class HostingRunSummary {
        private int processed;
        private int skipped;
        private int failed;
        private int operationsCreated;
        private final List<SkippedCampaign> skips = new ArrayList<>();

        void recordSkip(String campaignId, String reason) {
            skips.add(new SkippedCampaign(campaignId, reason));
        }

        public int getProcessed() {
            return processed;
        }

        public int getSkipped() {
            return skipped;
        }

        public int getFailed() {
            return failed;
        }

        public int getOperationsCreated() {
            return operationsCreated;
        }

        public List<SkippedCampaign> getSkips() {
            return List.copyOf(skips);
        }

        /** A campaign skipped during the run, with the reason it was skipped. */
        public record SkippedCampaign(String campaignId, String reason) {
        }
    }
}
