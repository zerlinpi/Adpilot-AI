package com.adpilot.modules.advertising.service;

import com.adpilot.common.exception.BusinessException;
import com.adpilot.modules.advertising.entity.CampaignEntity;
import com.adpilot.modules.advertising.entity.KeywordEntity;
import com.adpilot.modules.advertising.entity.OperationEntity;
import com.adpilot.modules.advertising.entity.PerformanceDailyEntity;
import com.adpilot.modules.advertising.entity.PersonalityPolicyEntity;
import com.adpilot.modules.advertising.mapper.CampaignMapper;
import com.adpilot.modules.advertising.mapper.KeywordMapper;
import com.adpilot.modules.advertising.mapper.OperationMapper;
import com.adpilot.modules.advertising.mapper.PerformanceDailyMapper;
import com.adpilot.modules.advertising.hosting.ReversibilityClassifier;
import com.adpilot.modules.advertising.operation.AiDecision;
import com.adpilot.modules.advertising.operation.CreateOperationCommand;
import com.adpilot.modules.advertising.operation.InFlightConflictException;
import com.adpilot.modules.advertising.operation.OperationMachineValues;
import com.adpilot.modules.advertising.operation.OperationScope;
import com.adpilot.modules.advertising.operation.OperationService;
import com.adpilot.modules.advertising.operation.OperationSource;
import com.adpilot.modules.advertising.operation.SyncState;
import com.adpilot.modules.advertising.support.AdMetrics;
import com.adpilot.modules.advertising.support.SafetyBoundary;
import com.adpilot.modules.advertising.support.SafetyBoundaryLimits;
import com.adpilot.modules.advertising.support.SafetyBoundaryResolver;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Scheduled optimizer that drives executable AI_Hosting (Req 9.3, 22, 49, 54).
 *
 * <p><b>Operation path (Req 9.3).</b> The optimizer no longer writes {@code bid_changes} or
 * {@code keyword.bid} directly. For each proposed adjustment it calls
 * {@link OperationService#createOperation(CreateOperationCommand)} with
 * {@link OperationSource#AI_HOSTING} and {@link OperationScope#PLATFORM_MUTATION}, so hosting drives
 * the same write path as manual and recommendation changes: the affected entity's confirmed value
 * changes only when the resulting Operation reaches {@code effective}, and a non-write-capable Store
 * resolves the Operation to the terminal {@code local-only} state (Req 9.4).</p>
 *
 * <p><b>Personality &amp; Safety_Boundary (Req 49).</b> The Campaign's effective AI_Personality is
 * resolved by {@link PersonalityResolver} (Campaign override &gt; Goal default &gt; Store default
 * &gt; {@code balanced}); its {@link PersonalityPolicyEntity} (loaded from the configurable
 * {@code personality_policies} table by {@link PersonalityPolicyService}) supplies the
 * personality-allowed magnitudes and approval ratios. The pure
 * {@link HostingBidOptimizer#adjustBidWithinBoundary} clamp applies at most the minimum of the
 * personality-allowed magnitude and the resolved Safety_Boundary, and never widens a limit (Req 22.4,
 * 49.10/49.11). Every emitted Operation records the AI decision audit fields (Req 49.9): trigger
 * metric, resolved personality, {@code Personality_Rule_Version}, personality-allowed magnitude,
 * actual applied magnitude, decision reason, before/after, predicted impact, and the approval flag.</p>
 *
 * <p><b>Phase gating (Req 22.1/22.2, 54).</b> The configurable {@code adpilot.hosting.phase}
 * (default {@code V1}) gates which {@link HostingAdjustmentType}s the optimizer emits: V1 = bid,
 * V2 = + budget, V3 = + keyword/negative. The current optimizer implements bid adjustment only, so
 * it emits a BID Operation only while the active phase supports it; budget/keyword/negative emission
 * is reached behind the same {@link HostingPhase#supports} gate as those capabilities are added.</p>
 *
 * <p><b>Pause / turn-off (Req 22.9, 22.12).</b> While the global pause switch
 * ({@code adpilot.hosting.global-pause}) is engaged, the scheduled run generates NO new Operations.
 * Turning off hosting for a Campaign or engaging the global pause also cancels that Campaign's (or
 * all) hosting Operations sitting in {@code awaiting_approval} (transitioned to {@code cancelled}),
 * while already-submitted / in-flight Operations are left to resolve their platform final state
 * rather than being silently dropped — see {@link #cancelAwaitingApprovalForCampaign(UUID)} and
 * {@link #cancelAwaitingApprovalForAllHosting()}.</p>
 *
 * <p>Per-campaign and per-keyword failures are isolated (logged and skipped) so one error never
 * aborts the whole tick (mirrors {@code ApprovalExpirationSweeper}); a keyword that already has an
 * in-flight Operation is skipped via the in-flight conflict lock (Req 5.6).</p>
 *
 * <p>Validates: Requirements 9.3, 22.1, 22.2, 22.9, 22.12, 49.8, 49.9, 49.12, 54.1, 54.2, 54.3, 54.4.</p>
 */
@Slf4j
@Component
public class AiHostingOptimizer {

    private static final String ENTITY_TYPE_KEYWORD = "keyword";
    private static final String FIELD_BID = "bid";
    private static final String TRIGGER_METRIC_ACOS = "acos";
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    /**
     * ACoS sentinel used when a campaign spent money but produced no sales in the lookback window:
     * ACoS is effectively unbounded, so we treat it as far above any target to drive bids down.
     */
    private static final BigDecimal NO_SALES_ACOS = new BigDecimal("99999");

    private final CampaignMapper campaignMapper;
    private final KeywordMapper keywordMapper;
    private final PerformanceDailyMapper performanceDailyMapper;
    private final OperationService operationService;
    private final PersonalityResolver personalityResolver;
    private final PersonalityPolicyService personalityPolicyService;
    private final OperationMapper operationMapper;
    private final ReversibilityClassifier reversibilityClassifier;

    /** Trailing window (days) used to compute a campaign's recent ACoS. */
    @Value("${adpilot.hosting.lookback-days:14}")
    private int lookbackDays;

    /** Absolute floor a hosted bid is never clamped below (System-default Safety_Boundary). */
    @Value("${adpilot.hosting.min-bid:0.02}")
    private BigDecimal minBid;

    /** Absolute ceiling a hosted bid is never clamped above (System-default Safety_Boundary). */
    @Value("${adpilot.hosting.max-bid:1000}")
    private BigDecimal maxBid;

    /** Active executable-hosting phase (Req 54). Gates which adjustment types the optimizer emits. */
    @Value("${adpilot.hosting.phase:V1}")
    private String phaseConfig;

    /** Global pause switch (Req 22.9): when engaged, the optimizer generates no new hosting Operations. */
    @Value("${adpilot.hosting.global-pause:false}")
    private boolean globalPause;

    public AiHostingOptimizer(CampaignMapper campaignMapper,
                              KeywordMapper keywordMapper,
                              PerformanceDailyMapper performanceDailyMapper,
                              OperationService operationService,
                              PersonalityResolver personalityResolver,
                              PersonalityPolicyService personalityPolicyService,
                              OperationMapper operationMapper,
                              ReversibilityClassifier reversibilityClassifier) {
        this.campaignMapper = campaignMapper;
        this.keywordMapper = keywordMapper;
        this.performanceDailyMapper = performanceDailyMapper;
        this.operationService = operationService;
        this.personalityResolver = personalityResolver;
        this.personalityPolicyService = personalityPolicyService;
        this.operationMapper = operationMapper;
        this.reversibilityClassifier = reversibilityClassifier;
    }

    /** The active executable-hosting phase resolved from configuration (Req 54). */
    public HostingPhase activePhase() {
        return HostingPhase.parse(phaseConfig);
    }

    /**
     * Scheduled entry point (Req 21.2). Fires on the configured fixed delay and delegates to
     * {@link #runOnce()}; the run is fully self-contained and never propagates an exception so the
     * scheduler keeps ticking.
     */
    @Scheduled(fixedDelayString = "${adpilot.hosting.optimize-ms:300000}")
    public void optimize() {
        try {
            OptimizationSummary summary = runOnce();
            if (summary.getCampaignsProcessed() > 0 || summary.isPaused()) {
                log.info("AI hosting optimization tick: paused={}, {} campaigns, {} operations, {} failed",
                        summary.isPaused(), summary.getCampaignsProcessed(),
                        summary.getOperationsCreated(), summary.getCampaignsFailed());
            }
        } catch (Exception ex) {
            log.warn("AI hosting optimization tick failed", ex);
        }
    }

    /**
     * Optimize every hosted campaign once and return an aggregate summary. While the global pause
     * switch is engaged, NO new Operations are generated (Req 22.9/22.12). Per-campaign failures are
     * isolated (logged and skipped) so one campaign's error never aborts the run.
     */
    public OptimizationSummary runOnce() {
        OptimizationSummary summary = new OptimizationSummary();

        // Global pause (Req 22.9/22.12): stop generating NEW hosting Operations entirely. In-flight
        // and awaiting_approval Operations are resolved by the pause-engagement hook
        // (cancelAwaitingApprovalForAllHosting), not by silently dropping them here.
        if (globalPause) {
            summary.paused = true;
            return summary;
        }

        HostingPhase phase = activePhase();

        List<CampaignEntity> hosted = campaignMapper.selectList(
                new LambdaQueryWrapper<CampaignEntity>()
                        .eq(CampaignEntity::getHostingEnabled, true)
                        .isNotNull(CampaignEntity::getTargetAcos));

        for (CampaignEntity campaign : hosted) {
            summary.campaignsProcessed++;
            try {
                summary.operationsCreated += optimizeCampaign(campaign, phase);
            } catch (Exception ex) {
                summary.campaignsFailed++;
                log.warn("AI hosting optimization failed for campaign {}: {}",
                        campaign.getId(), rootMessage(ex));
            }
        }
        return summary;
    }

    /**
     * Optimize a single hosted campaign for the active phase: compute its recent ACoS, and for each
     * enabled keyword emit a bid-adjustment Operation (when the phase supports bid adjustment) that
     * moves the bid toward the Target_ACoS within the personality-allowed magnitude and the resolved
     * Safety_Boundary. Returns the number of Operations created.
     *
     * @param campaign the hosted campaign to optimize; must not be {@code null}
     * @param phase    the active executable-hosting phase (capability gate, Req 54)
     * @return the number of hosting Operations created for this campaign
     */
    public int optimizeCampaign(CampaignEntity campaign, HostingPhase phase) {
        if (campaign == null || campaign.getTargetAcos() == null) {
            return 0;
        }
        // Phase gate (Req 22.1/22.2, 54): the optimizer currently implements bid adjustment only, so
        // it emits nothing when the active phase does not yet enable bid adjustment. Budget/keyword/
        // negative emission is added behind the same supports() gate as those capabilities land.
        if (!phase.supports(HostingAdjustmentType.BID)) {
            return 0;
        }

        BigDecimal recentAcos = computeRecentAcos(campaign);
        if (recentAcos == null) {
            // No performance signal in the lookback window — nothing to act on.
            return 0;
        }
        BigDecimal targetAcos = campaign.getTargetAcos();

        // Resolve the Campaign's effective AI_Personality and its configurable Personality_Policy
        // (Req 49.2/49.3, 49.5/49.6). The in-effect rule_version is recorded on every AI Operation.
        AiPersonality personality = personalityResolver.resolveForCampaign(campaign);
        PersonalityPolicyEntity policy = personalityPolicyService.resolvePolicy(personality.machineValue());
        String ruleVersion = policy.getRuleVersion();

        // Resolve the effective Safety_Boundary (Req 22.11/49.11). Campaign/Goal/Store boundary
        // overrides are not yet persisted as boundary fields, so only the System-default hard
        // floor/ceiling participates today; the resolver still owns the precedence so higher levels
        // bind automatically once persisted.
        SafetyBoundary boundary = SafetyBoundaryResolver.resolve(
                null, null, null,
                SafetyBoundaryLimits.builder().minBid(minBid).maxBid(maxBid).build());

        List<KeywordEntity> keywords = keywordMapper.selectList(
                new LambdaQueryWrapper<KeywordEntity>()
                        .eq(KeywordEntity::getCampaignId, campaign.getId())
                        .eq(KeywordEntity::getStatus, "enabled")
                        .isNotNull(KeywordEntity::getBid));

        int created = 0;
        for (KeywordEntity keyword : keywords) {
            try {
                if (emitBidAdjustment(campaign, keyword, recentAcos, targetAcos, personality, policy, ruleVersion, boundary)) {
                    created++;
                }
            } catch (InFlightConflictException conflict) {
                // The keyword already has an in-flight hosting Operation (Req 5.6) — skip this run
                // rather than stacking a second conflicting Operation.
                log.debug("Skipping keyword {} — an Operation is already in flight", keyword.getId());
            }
        }
        return created;
    }

    /**
     * Compute the proposed bid for one keyword, clamp it within the personality magnitude and the
     * Safety_Boundary, and (when it produces an effective change) emit an {@code ai_hosting}
     * {@code platform_mutation} Operation. Returns whether an Operation was created.
     */
    private boolean emitBidAdjustment(CampaignEntity campaign,
                                      KeywordEntity keyword,
                                      BigDecimal recentAcos,
                                      BigDecimal targetAcos,
                                      AiPersonality personality,
                                      PersonalityPolicyEntity policy,
                                      String ruleVersion,
                                      SafetyBoundary boundary) {
        BigDecimal current = keyword.getBid();
        if (current == null || current.signum() <= 0) {
            return false;
        }

        int direction = recentAcos.compareTo(targetAcos);
        if (direction == 0) {
            return false; // fixpoint / no effective change at the target.
        }

        // Personality-allowed magnitude (ratio): the decrease budget when ACoS is above target,
        // otherwise the increase budget (Req 49.4/49.10). Converted to a percentage for the clamp.
        BigDecimal allowedRatio = direction > 0
                ? nvl(policy.getMaxBidDecreaseRatio())
                : nvl(policy.getMaxBidIncreaseRatio());
        BigDecimal maxChangePct = allowedRatio.multiply(HUNDRED);

        HostingBidOptimizer.SafeBidAdjustment adjustment = HostingBidOptimizer.adjustBidWithinBoundary(
                current, recentAcos, targetAcos, maxChangePct, boundary, minBid, maxBid);
        BigDecimal adjusted = adjustment.getAdjustedBid().setScale(4, RoundingMode.HALF_UP);

        if (adjustment.isFlagged()) {
            // Current value is outside the hard Safety_Boundary: the clamp only moves it toward the
            // safe range (Req 22.5). Flag the Campaign and raise an alert.
            log.warn("Hosting bid for keyword {} (campaign {}) is outside the Safety_Boundary; "
                            + "adjusting only toward the safe range (current={}, adjusted={})",
                    keyword.getId(), campaign.getId(), current, adjusted);
        }

        if (adjusted.compareTo(current) == 0) {
            return false; // clamp produced no effective change.
        }

        // Actual applied magnitude (ratio) and the approval gate (Req 22.8/49.19): approval is
        // required when the absolute change ratio meets/exceeds the personality's approval ratio.
        BigDecimal appliedMagnitude = adjusted.subtract(current).abs()
                .divide(current, 6, RoundingMode.HALF_UP);
        BigDecimal approvalRatio = nvl(policy.getApprovalBidChangeRatio());
        boolean approvalRequired = appliedMagnitude.compareTo(approvalRatio) >= 0;

        AiDecision decision = AiDecision.builder()
                .triggerMetric(TRIGGER_METRIC_ACOS)
                .triggerValue(recentAcos)
                .resolvedPersonality(personality.machineValue())
                .personalityAllowedMagnitude(allowedRatio)
                .appliedMagnitude(appliedMagnitude)
                .decisionReason(buildReason(personality, recentAcos, targetAcos, current, adjusted, direction))
                .predictedImpact(buildPredictedImpact(direction))
                .approvalRequired(approvalRequired)
                .build();

        CreateOperationCommand command = CreateOperationCommand.builder()
                .storeId(campaign.getStoreId())
                .operationSource(OperationSource.AI_HOSTING)
                .operationScope(OperationScope.PLATFORM_MUTATION)
                .entityType(ENTITY_TYPE_KEYWORD)
                .entityId(keyword.getId())
                .field(FIELD_BID)
                // Unique per run so the in-flight conflict lock (Req 5.6) blocks a second Operation
                // on a keyword that already has one in flight, rather than coalescing across runs.
                .logicalIdempotencyKey("ai_hosting:bid:" + keyword.getId() + ":" + UUID.randomUUID())
                .beforeValue(current)
                .afterValue(adjusted)
                .reversible(reversibilityClassifier.isReversible(FIELD_BID))
                .affectedCount(1)
                .expectedVersion(keyword.getVersion())
                .approvalRequired(approvalRequired)
                .personalityRuleVersion(ruleVersion)
                .aiDecision(decision)
                .build();

        operationService.createOperation(command);
        return true;
    }

    /**
     * Cancel a Campaign's hosting Operations that are sitting in {@code awaiting_approval} (Req 22.12)
     * — invoked when AI hosting is turned off for that Campaign. Already-submitted / in-flight
     * Operations are intentionally NOT touched here; they are left to resolve their platform final
     * state rather than being silently dropped. Returns the number of Operations cancelled.
     *
     * @param campaignId the Campaign whose hosting is being turned off; must not be {@code null}
     * @return the number of {@code awaiting_approval} hosting Operations cancelled
     */
    public int cancelAwaitingApprovalForCampaign(UUID campaignId) {
        if (campaignId == null) {
            return 0;
        }
        // Hosting Operations target the campaign's keywords (the campaign id itself is also covered
        // for future campaign-level adjustments).
        List<UUID> entityIds = new ArrayList<>();
        entityIds.add(campaignId);
        keywordMapper.selectList(new LambdaQueryWrapper<KeywordEntity>()
                        .eq(KeywordEntity::getCampaignId, campaignId))
                .forEach(k -> entityIds.add(k.getId()));

        List<OperationEntity> awaiting = operationMapper.selectList(awaitingApprovalHostingQuery()
                .in(OperationEntity::getEntityId, entityIds));
        return cancelEach(awaiting);
    }

    /**
     * Cancel ALL hosting Operations sitting in {@code awaiting_approval} (Req 22.9/22.12) — invoked
     * when the global pause switch is engaged. Already-submitted / in-flight Operations are left to
     * resolve their platform final state. Returns the number of Operations cancelled.
     *
     * @return the number of {@code awaiting_approval} hosting Operations cancelled
     */
    public int cancelAwaitingApprovalForAllHosting() {
        return cancelEach(operationMapper.selectList(awaitingApprovalHostingQuery()));
    }

    private LambdaQueryWrapper<OperationEntity> awaitingApprovalHostingQuery() {
        return new LambdaQueryWrapper<OperationEntity>()
                .eq(OperationEntity::getOperationSource, OperationMachineValues.toValue(OperationSource.AI_HOSTING))
                .eq(OperationEntity::getSyncState, OperationMachineValues.toValue(SyncState.AWAITING_APPROVAL));
    }

    private int cancelEach(List<OperationEntity> operations) {
        int cancelled = 0;
        for (OperationEntity operation : operations) {
            try {
                operationService.cancel(operation.getId());
                cancelled++;
            } catch (BusinessException ex) {
                // Isolate per-Operation failures so one cancellation never aborts the rest.
                log.warn("Failed to cancel awaiting_approval hosting Operation {}: {}",
                        operation.getId(), rootMessage(ex));
            }
        }
        return cancelled;
    }

    /**
     * Compute the campaign's recent ACoS over the lookback window from {@code performance_daily}.
     * Returns {@code null} when there is no signal (no spend and no sales) so the caller can skip the
     * campaign; returns a large sentinel when there was spend but no sales (ACoS unbounded).
     */
    private BigDecimal computeRecentAcos(CampaignEntity campaign) {
        LocalDate since = LocalDate.now().minusDays(Math.max(0, lookbackDays));
        List<PerformanceDailyEntity> rows = performanceDailyMapper.selectList(
                new LambdaQueryWrapper<PerformanceDailyEntity>()
                        .eq(PerformanceDailyEntity::getCampaignId, campaign.getId())
                        .ge(PerformanceDailyEntity::getDate, since));

        BigDecimal spend = BigDecimal.ZERO;
        BigDecimal sales = BigDecimal.ZERO;
        for (PerformanceDailyEntity row : rows) {
            if (row.getSpend() != null) {
                spend = spend.add(row.getSpend());
            }
            if (row.getSales() != null) {
                sales = sales.add(row.getSales());
            }
        }

        if (spend.signum() <= 0 && sales.signum() <= 0) {
            return null;
        }
        if (sales.signum() <= 0) {
            return NO_SALES_ACOS;
        }
        return AdMetrics.acos(spend, sales);
    }

    private String buildReason(AiPersonality personality,
                               BigDecimal recentAcos,
                               BigDecimal targetAcos,
                               BigDecimal current,
                               BigDecimal adjusted,
                               int direction) {
        String move = direction > 0 ? "下调" : "上调";
        return String.format("%s人格：近%d天 ACoS %s，目标 ACoS %s，%s竞价 %s → %s。",
                personality.machineValue(), Math.max(0, lookbackDays),
                recentAcos.toPlainString(), targetAcos.toPlainString(),
                move, current.toPlainString(), adjusted.toPlainString());
    }

    private String buildPredictedImpact(int direction) {
        return direction > 0
                ? "预计降低 ACoS 并控制花费"
                : "预计提升曝光与销量";
    }

    private static BigDecimal nvl(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private static String rootMessage(Throwable e) {
        Throwable cur = e;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        String m = cur.getMessage();
        return m != null ? m : cur.getClass().getSimpleName();
    }

    /** Aggregate counters for one optimization run. */
    @Getter
    public static final class OptimizationSummary {
        private int campaignsProcessed;
        private int campaignsFailed;
        private int operationsCreated;
        private boolean paused;

        /**
         * Backwards-compatible alias for {@link #getOperationsCreated()} — the count of hosting
         * Operations created on this run (previously the count of bids written directly).
         */
        public int getBidsChanged() {
            return operationsCreated;
        }
    }
}
