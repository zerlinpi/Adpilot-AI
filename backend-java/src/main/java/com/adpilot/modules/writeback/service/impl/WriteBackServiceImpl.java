package com.adpilot.modules.writeback.service.impl;

import com.adpilot.common.exception.BusinessException;
import com.adpilot.modules.advertising.entity.OperationEntity;
import com.adpilot.modules.advertising.entity.RecommendationEntity;
import com.adpilot.modules.advertising.mapper.OperationMapper;
import com.adpilot.modules.advertising.mapper.RecommendationMapper;
import com.adpilot.modules.advertising.operation.CreateOperationCommand;
import com.adpilot.modules.advertising.operation.OperationResult;
import com.adpilot.modules.advertising.operation.OperationScope;
import com.adpilot.modules.advertising.operation.OperationService;
import com.adpilot.modules.advertising.operation.OperationSource;
import com.adpilot.modules.advertising.operation.OperationWriteBack;
import com.adpilot.modules.advertising.operation.SyncState;
import com.adpilot.modules.approval.annotation.RequiresApproval;
import com.adpilot.modules.writeback.service.WriteBackService;
import com.adpilot.modules.writeback.vo.WriteBackResultVo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Default {@link WriteBackService}, reconciled to delegate to the generic
 * {@link OperationWriteBack} path (Req 2.3, 9.1, 9.5).
 *
 * <h2>Delegation (Req 2.3, 9.1)</h2>
 * <p>Applying a Recommendation no longer carries its own recommendation-only platform-submission
 * and audit code. Instead {@link #apply(String)} builds a {@code recommendation}-sourced
 * {@code platform_mutation} {@link CreateOperationCommand}, persists it through
 * {@link OperationService#createOperation(CreateOperationCommand)} (which resolves write-capability,
 * the Sync_State, the audit entry, and — for a write-capable Store — the Outbox entry in one
 * transaction with no platform call), and then submits it through
 * {@link OperationWriteBack#applyOperation(UUID)}. The recommendation thus rides the same generic
 * write path and Sync_State lifecycle (Requirements 3 and 4) as every other source.</p>
 *
 * <h2>Approval gating (Req 13.1.4, preserved)</h2>
 * <p>{@link #apply(String)} keeps its {@link RequiresApproval} annotation (module
 * {@code "recommendation"} / action {@code "apply"}). The {@code ApprovalAspect} intercepts the call
 * before it runs: when an enabled {@code approval_policies} row governs this module/action for the
 * caller's organization, the aspect persists a pending {@code approval_request} and short-circuits
 * the method (returning {@code null}) so that <em>no Operation is created and no change is submitted
 * until the approval completes</em>. AOP advice only fires through the Spring proxy.</p>
 *
 * <h2>Unsupported Recommendation_Type rejected (Req 11.1, 11.2)</h2>
 * <p>Before any Operation is created, {@link #apply(String)} validates that the Recommendation_Type
 * maps to a defined Operation (see {@link #SUPPORTED_RECOMMENDATION_TYPES}). An unsupported type is
 * rejected with an error naming the type, no Operation is created, and the Recommendation is left in
 * its {@code pending} state (its status is never rewritten).</p>
 *
 * <h2>Effective only on an effective Operation (Req 9.5)</h2>
 * <p>This path NEVER marks the Recommendation effective. A successful synchronous submission only
 * advances the Operation to {@code submitted} (awaiting Amazon's asynchronous acknowledgement), so
 * the Recommendation is set to {@code applying}; a not-write-capable Store yields {@code local-only};
 * a rejection/transport error yields {@code failed} (still actionable for retry). The Recommendation
 * is promoted to {@code effective} only when its Operation reaches the {@code effective} Sync_State,
 * which is driven by the platform callback / status poller, not here.</p>
 *
 * <p>The method is deliberately NOT {@code @Transactional}: {@code createOperation} owns its own
 * transaction, and the subsequent platform submission performed by {@code applyOperation} MUST run
 * OUTSIDE any database transaction (Req 6.3).</p>
 */
@Slf4j
@Service
public class WriteBackServiceImpl implements WriteBackService {

    /** Approval governance coordinates for recommendation application (Req 13.1.4). */
    static final String APPROVAL_MODULE = "recommendation";
    static final String APPROVAL_ACTION = "apply";

    /** Recommendation statuses that may still be applied. */
    static final String STATUS_PENDING = "pending";
    static final String STATUS_WATCHING = "watching";

    /**
     * The Recommendation_Type values that map to a defined Operation and can therefore be applied
     * (Req 11.1). Any other type is unsupported and is rejected on apply WITHOUT creating an
     * Operation, leaving the Recommendation in its {@code pending} state (Req 11.2). This set mirrors
     * the change types the recommendation apply switch knows how to act on (bid change, negative
     * keyword, exact-match harvest, budget change, pause target).
     */
    static final Set<String> SUPPORTED_RECOMMENDATION_TYPES = Set.of(
            "decrease_bid",
            "increase_bid",
            "add_negative",
            "add_exact",
            "increase_budget",
            "pause_target");

    /** Recommendation_Status values surfaced by the delegation (Req 9.5, 10.x). */
    static final String REC_STATUS_APPLYING = "applying";
    static final String REC_STATUS_LOCAL_ONLY = "local-only";
    static final String REC_STATUS_FAILED = "failed";

    private final RecommendationMapper recommendationMapper;
    private final OperationMapper operationMapper;
    private final OperationService operationService;
    private final OperationWriteBack operationWriteBack;

    public WriteBackServiceImpl(RecommendationMapper recommendationMapper,
                                OperationMapper operationMapper,
                                OperationService operationService,
                                OperationWriteBack operationWriteBack) {
        this.recommendationMapper = recommendationMapper;
        this.operationMapper = operationMapper;
        this.operationService = operationService;
        this.operationWriteBack = operationWriteBack;
    }

    @Override
    @RequiresApproval(module = APPROVAL_MODULE, action = APPROVAL_ACTION)
    public WriteBackResultVo apply(String recommendationId) {
        if (recommendationId == null || recommendationId.isBlank()) {
            throw new BusinessException("INVALID_REQUEST", "recommendationId is required");
        }
        UUID recId = parseUuid(recommendationId, "recommendationId");

        RecommendationEntity recommendation = recommendationMapper.selectById(recId);
        if (recommendation == null) {
            throw new BusinessException("RECOMMENDATION_NOT_FOUND",
                    "Recommendation not found: " + recommendationId);
        }
        if (!isApplicable(recommendation.getStatus())) {
            throw new BusinessException("RECOMMENDATION_NOT_APPLICABLE",
                    "Recommendation is not in an applicable state: " + recommendation.getStatus());
        }

        // Req 11.1 / 11.2: reject an unsupported Recommendation_Type BEFORE creating any Operation,
        // naming the offending type, and leave the Recommendation in its pending state (no Operation
        // is created and the status is not rewritten).
        validateSupportedType(recommendation.getType());

        // Req 2.3 / 9.1: build a recommendation-sourced platform_mutation Operation and route it
        // through the generic write path rather than a separate recommendation-only write path.
        CreateOperationCommand command = toCreateCommand(recommendation);
        OperationResult created = operationService.createOperation(command);

        // The Store is write-capable and the Operation is ready to submit: drive it through
        // Operation_Write_Back (outside any DB transaction, Req 6.3). A local-only / awaiting_approval
        // Operation is not submitted here.
        OperationResult outcome = created;
        if (created.getSyncState() == SyncState.PENDING) {
            outcome = operationWriteBack.applyOperation(created.getOperationId());
        }

        // Req 9.5: NEVER mark the Recommendation effective here — only when its Operation reaches
        // the effective Sync_State (driven by the callback / poller). Reflect the in-flight,
        // local-only, or failed outcome honestly instead.
        OperationEntity operation = operationMapper.selectById(created.getOperationId());
        SyncState resultState = outcome.getSyncState();
        applyRecommendationStatus(recommendation, resultState);

        return toResultVo(recommendationId, resultState, operation);
    }

    // ── command construction ──────────────────────────────────────────────────

    private CreateOperationCommand toCreateCommand(RecommendationEntity rec) {
        String entityType;
        UUID entityId;
        if (rec.getKeywordId() != null) {
            entityType = "keyword";
            entityId = rec.getKeywordId();
        } else if (rec.getTargetId() != null) {
            entityType = "target";
            entityId = rec.getTargetId();
        } else if (rec.getCampaignId() != null) {
            entityType = "campaign";
            entityId = rec.getCampaignId();
        } else {
            throw new BusinessException("RECOMMENDATION_NOT_APPLICABLE",
                    "Recommendation has no target entity to apply: " + rec.getId());
        }

        return CreateOperationCommand.builder()
                .storeId(rec.getStoreId())
                .operationSource(OperationSource.RECOMMENDATION)
                .operationScope(OperationScope.PLATFORM_MUTATION)
                .entityType(entityType)
                .entityId(entityId)
                // The Recommendation_Type is carried as the changed field so the connector receives
                // the same change-type the recommendation-only path used to send.
                .field(rec.getType())
                // Coalesce repeated applies of the SAME recommendation into one logical Operation.
                .logicalIdempotencyKey("recommendation:" + rec.getId())
                .beforeValue(rec.getCurrentValue())
                .afterValue(rec.getRecommendedValue())
                .reversible(Boolean.FALSE)
                .affectedCount(1)
                .build();
    }

    // ── recommendation status (Req 9.5, 10.x) ───────────────────────────────────

    /**
     * Reflect the Operation's outcome on the Recommendation WITHOUT ever marking it effective
     * (Req 9.5). The promotion to {@code effective} happens only when the Operation reaches the
     * {@code effective} Sync_State, handled by the callback / status poller.
     */
    private void applyRecommendationStatus(RecommendationEntity recommendation, SyncState state) {
        String status;
        if (state == SyncState.LOCAL_ONLY) {
            status = REC_STATUS_LOCAL_ONLY;          // saved locally, Amazon unchanged (Req 9.4, 10.5)
        } else if (state == SyncState.FAILED) {
            status = REC_STATUS_FAILED;              // keep actionable for retry (Req 10.4)
        } else {
            status = REC_STATUS_APPLYING;            // any Unsettled_State -> applying (Req 10.2/10.8)
        }
        if (status.equals(recommendation.getStatus())) {
            return;
        }
        recommendation.setStatus(status);
        recommendationMapper.updateById(recommendation);
    }

    // ── result mapping ──────────────────────────────────────────────────────────

    private WriteBackResultVo toResultVo(String recommendationId, SyncState state, OperationEntity op) {
        WriteBackResultVo.WriteBackResultVoBuilder vo = WriteBackResultVo.builder()
                .recommendationId(recommendationId);
        if (state == SyncState.EFFECTIVE) {
            return vo.status("applied").applied(true)
                    .platformReference(op != null ? op.getPlatformReference() : null)
                    .message("Amazon 已确认生效").build();
        }
        if (state == SyncState.FAILED) {
            return vo.status("rejected").applied(false)
                    .message(op != null ? op.getStatusReason() : "平台拒绝了该变更").build();
        }
        if (state == SyncState.LOCAL_ONLY) {
            return vo.status("local-only").applied(false)
                    .message("仅保存在系统，Amazon 未变更").build();
        }
        // pending / awaiting_approval / submitted / amazon-processing: in flight, not yet effective.
        return vo.status("submitted").applied(false)
                .platformReference(op != null ? op.getPlatformReference() : null)
                .message("已提交，等待 Amazon 确认").build();
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static boolean isApplicable(String status) {
        return status == null || STATUS_PENDING.equalsIgnoreCase(status)
                || STATUS_WATCHING.equalsIgnoreCase(status);
    }

    /**
     * Reject a Recommendation whose Recommendation_Type does not map to a defined Operation
     * (Req 11.2). The error names the unsupported type. Because this runs before any Operation is
     * created and the Recommendation's status is never written here, the Recommendation is left in
     * its existing {@code pending} state.
     */
    private static void validateSupportedType(String type) {
        String normalized = type == null ? null : type.trim().toLowerCase(Locale.ROOT);
        if (normalized == null || !SUPPORTED_RECOMMENDATION_TYPES.contains(normalized)) {
            throw new BusinessException("UNSUPPORTED_RECOMMENDATION_TYPE",
                    "Recommendation type is not supported and cannot be applied: " + type);
        }
    }

    private static UUID parseUuid(String value, String field) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            throw new BusinessException("INVALID_REQUEST", field + " is not a valid id: " + value);
        }
    }
}
