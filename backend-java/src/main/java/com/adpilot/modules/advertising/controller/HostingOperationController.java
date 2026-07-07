package com.adpilot.modules.advertising.controller;

import com.adpilot.common.api.ApiResponse;
import com.adpilot.common.exception.BusinessException;
import com.adpilot.common.security.RequirePermission;
import com.adpilot.common.utils.SecurityUtils;
import com.adpilot.modules.advertising.dto.OperationRejectRequest;
import com.adpilot.modules.advertising.dto.PhaseChangeRequest;
import com.adpilot.modules.advertising.entity.OperationEntity;
import com.adpilot.modules.advertising.hosting.HostingApprovalService;
import com.adpilot.modules.advertising.hosting.HostingOrgIsolationGuard;
import com.adpilot.modules.advertising.hosting.PhaseConfigurationService;
import com.adpilot.modules.advertising.hosting.RollbackResult;
import com.adpilot.modules.advertising.hosting.RollbackService;
import com.adpilot.modules.advertising.operation.OperationResult;
import com.adpilot.modules.advertising.service.HostingPhase;
import com.adpilot.modules.advertising.vo.OperationActionVo;
import com.adpilot.modules.advertising.vo.PhaseChangeVo;
import com.adpilot.modules.advertising.vo.RollbackVo;
import com.adpilot.modules.store.entity.StoreEntity;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * AI hosting Operation lifecycle APIs under {@code /api/advertising/hosting} — approve, reject,
 * rollback, and the admin phase-change endpoint (Req 24.1–24.5, 10.6, 17.5).
 *
 * <p>This controller is a thin wiring layer over the existing hosting services and is kept
 * separate from {@code HostingConfigController} so config and operation lifecycle concerns do
 * not share a class:</p>
 * <ul>
 *   <li><b>approve / reject</b> bridge to {@link HostingApprovalService}, which atomically
 *       synchronizes the {@code approval_requests} record with the Operation's SyncState and
 *       rejects self-approval (Req 24.2, 24.3).</li>
 *   <li><b>rollback</b> delegates to {@link RollbackService}; an overlap that needs operator
 *       acknowledgement is surfaced as a {@code confirmation_required} result carrying the
 *       conflicting operation ids, and the caller re-issues with {@code confirm=true} (Req 10.6).</li>
 *   <li><b>phase change</b> delegates to {@link PhaseConfigurationService}, which enforces
 *       adjacent-only transitions and downgrade cleanup (Req 17.5).</li>
 * </ul>
 *
 * <p>Permissions follow the design API table: {@code advertising:approve} for approve/reject and
 * {@code advertising:execute} for rollback and phase change.</p>
 *
 * <p>Cross-organization isolation is enforced by resolving every inbound id through
 * {@link HostingOrgIsolationGuard} (Req 24.3 / 39) before any state-changing call; the guard fails
 * closed with 404 (not found) or 403 (cross-org) without leaking resource detail. The acting
 * user/approver is resolved from the authenticated security context.</p>
 *
 * <p>Validates: Requirements 24.1, 24.2, 24.3, 24.4, 24.5, 10.6, 17.5.</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/advertising/hosting")
@RequiredArgsConstructor
public class HostingOperationController {

    private final HostingApprovalService hostingApprovalService;
    private final RollbackService rollbackService;
    private final PhaseConfigurationService phaseConfigurationService;
    private final HostingOrgIsolationGuard orgIsolationGuard;

    // ────────────────────────────────────────────────────────────────────────────
    // Approve / Reject (Req 24.2, 24.3)
    // ────────────────────────────────────────────────────────────────────────────

    /**
     * POST /api/advertising/hosting/operations/{id}/approve — approve an awaiting-approval
     * Operation, advancing it to {@code pending} so the OutboxWorker claims it (Req 24.2).
     */
    @PostMapping("/operations/{id}/approve")
    @RequirePermission("advertising:approve")
    public ApiResponse<OperationActionVo> approve(@PathVariable("id") String id) {
        UUID operationId = parseUuid(id, "operationId");
        // Cross-org isolation: resolve and assert the operation belongs to the caller's org first.
        orgIsolationGuard.resolveOperationInCallerOrg(operationId);
        UUID approverId = currentActor();

        hostingApprovalService.approveOperation(operationId, approverId);
        log.info("Hosting operation approved: operation={} approver={}", operationId, approverId);

        // Re-resolve to surface the post-transition sync state for real-time list updates (Req 24.5).
        OperationEntity updated = orgIsolationGuard.resolveOperationInCallerOrg(operationId);
        return ApiResponse.ok(OperationActionVo.builder()
                .operationId(operationId.toString())
                .action("approved")
                .syncState(updated.getSyncState())
                .build());
    }

    /**
     * POST /api/advertising/hosting/operations/{id}/reject — reject an awaiting-approval Operation
     * with a reason, advancing it to {@code cancelled} and notifying the originator (Req 24.3).
     */
    @PostMapping("/operations/{id}/reject")
    @RequirePermission("advertising:approve")
    public ApiResponse<OperationActionVo> reject(@PathVariable("id") String id,
                                                 @Valid @RequestBody OperationRejectRequest request) {
        UUID operationId = parseUuid(id, "operationId");
        orgIsolationGuard.resolveOperationInCallerOrg(operationId);
        UUID approverId = currentActor();

        hostingApprovalService.rejectOperation(operationId, approverId, request.getReason());
        log.info("Hosting operation rejected: operation={} approver={}", operationId, approverId);

        OperationEntity updated = orgIsolationGuard.resolveOperationInCallerOrg(operationId);
        return ApiResponse.ok(OperationActionVo.builder()
                .operationId(operationId.toString())
                .action("rejected")
                .syncState(updated.getSyncState())
                .build());
    }

    // ────────────────────────────────────────────────────────────────────────────
    // Rollback (Req 10.6)
    // ────────────────────────────────────────────────────────────────────────────

    /**
     * POST /api/advertising/hosting/operations/{id}/rollback — roll back an effective, reversible
     * Operation by creating a compensating Operation (Req 10.6).
     *
     * <p>When subsequent overlapping operations exist on the same entity+field, the response is a
     * {@code confirmation_required} result carrying the conflicting operation ids; re-issue with
     * {@code confirm=true} to proceed despite the overlap.</p>
     */
    @PostMapping("/operations/{id}/rollback")
    @RequirePermission("advertising:execute")
    public ApiResponse<RollbackVo> rollback(@PathVariable("id") String id,
                                            @RequestParam(name = "confirm", defaultValue = "false") boolean confirm) {
        UUID operationId = parseUuid(id, "operationId");
        orgIsolationGuard.resolveOperationInCallerOrg(operationId);

        RollbackResult result = confirm
                ? rollbackService.rollbackWithConfirmation(operationId)
                : rollbackService.rollback(operationId);
        log.info("Hosting operation rollback requested: operation={} confirm={} confirmationRequired={}",
                operationId, confirm, result.isConfirmationRequired());

        return ApiResponse.ok(toRollbackVo(result));
    }

    // ────────────────────────────────────────────────────────────────────────────
    // Admin phase change (Req 17.5)
    // ────────────────────────────────────────────────────────────────────────────

    /**
     * POST /api/advertising/hosting/phase — admin phase-change endpoint performing an adjacent-only
     * hosting phase transition (V1↔V2↔V3) for a store (Req 17.5).
     */
    @PostMapping("/phase")
    @RequirePermission("advertising:execute")
    public ApiResponse<PhaseChangeVo> changePhase(@Valid @RequestBody PhaseChangeRequest request) {
        UUID storeId = parseUuid(request.getStoreId(), "storeId");
        // Cross-org isolation: assert the store belongs to the caller's org before transitioning.
        StoreEntity store = orgIsolationGuard.resolveStoreInCallerOrg(storeId);
        HostingPhase target = parsePhase(request.getPhase());
        UUID actorId = currentActor();

        HostingPhase previous = phaseConfigurationService.getCurrentPhase(store.getId());
        phaseConfigurationService.transitionPhase(store.getId(), target, actorId);
        HostingPhase active = phaseConfigurationService.getCurrentPhase(store.getId());
        log.info("Hosting phase changed for store={} from={} to={} by actor={}",
                store.getId(), previous, active, actorId);

        return ApiResponse.ok(PhaseChangeVo.builder()
                .storeId(store.getId().toString())
                .previousPhase(previous == null ? null : previous.name())
                .activePhase(active == null ? null : active.name())
                .build());
    }

    // ────────────────────────────────────────────────────────────────────────────
    // Helpers
    // ────────────────────────────────────────────────────────────────────────────

    private static RollbackVo toRollbackVo(RollbackResult result) {
        RollbackVo.RollbackVoBuilder vo = RollbackVo.builder()
                .confirmationRequired(result.isConfirmationRequired());

        if (result.isConfirmationRequired()) {
            List<String> conflicts = result.getConflictingOperationIds() == null
                    ? List.of()
                    : result.getConflictingOperationIds().stream()
                        .map(UUID::toString)
                        .collect(Collectors.toList());
            vo.conflictingOperationIds(conflicts)
              .warningMessage(result.getWarningMessage());
        } else {
            OperationResult op = result.getOperationResult();
            if (op != null) {
                vo.compensatingOperationId(op.getOperationId() == null ? null : op.getOperationId().toString())
                  .syncState(op.getSyncState() == null ? null : op.getSyncState().name());
            }
        }
        return vo.build();
    }

    private static HostingPhase parsePhase(String value) {
        if (value == null || value.isBlank()) {
            throw new BusinessException(400, "HOSTING_INVALID_PARAMETER", "phase must not be blank");
        }
        String normalized = value.trim().toUpperCase(java.util.Locale.ROOT);
        for (HostingPhase phase : HostingPhase.values()) {
            if (phase.name().equals(normalized)) {
                return phase;
            }
        }
        throw new BusinessException(400, "HOSTING_INVALID_PARAMETER",
                "Invalid phase '" + value + "': expected one of V1, V2, V3");
    }

    private static UUID parseUuid(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new BusinessException(400, "HOSTING_INVALID_PARAMETER", field + " must not be blank");
        }
        try {
            return UUID.fromString(value.trim());
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(400, "HOSTING_INVALID_PARAMETER",
                    "Invalid " + field + " '" + value + "': not a valid identifier");
        }
    }

    /**
     * Resolve the authenticated acting user (approver / actor). These lifecycle actions are
     * operator-driven and require an authenticated principal, so a missing/unresolvable user
     * fails closed rather than attributing the action to a non-interactive actor.
     */
    private static UUID currentActor() {
        String userId = SecurityUtils.getCurrentUserIdOrNull();
        if (userId == null) {
            throw new BusinessException(401, "HOSTING_UNAUTHENTICATED",
                    "An authenticated user is required to perform this action");
        }
        try {
            return UUID.fromString(userId);
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(401, "HOSTING_UNAUTHENTICATED",
                    "The authenticated user id is not a valid identifier");
        }
    }
}
