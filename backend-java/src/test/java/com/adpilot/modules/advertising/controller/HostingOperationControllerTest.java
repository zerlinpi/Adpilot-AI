package com.adpilot.modules.advertising.controller;

import com.adpilot.common.api.ApiResponse;
import com.adpilot.common.exception.BusinessException;
import com.adpilot.common.security.CurrentUser;
import com.adpilot.modules.advertising.dto.OperationRejectRequest;
import com.adpilot.modules.advertising.dto.PhaseChangeRequest;
import com.adpilot.modules.advertising.entity.OperationEntity;
import com.adpilot.modules.advertising.hosting.HostingApprovalService;
import com.adpilot.modules.advertising.hosting.HostingOrgIsolationGuard;
import com.adpilot.modules.advertising.hosting.PhaseConfigurationService;
import com.adpilot.modules.advertising.hosting.RollbackResult;
import com.adpilot.modules.advertising.hosting.RollbackService;
import com.adpilot.modules.advertising.operation.OperationResult;
import com.adpilot.modules.advertising.operation.SyncState;
import com.adpilot.modules.advertising.service.HostingPhase;
import com.adpilot.modules.advertising.vo.OperationActionVo;
import com.adpilot.modules.advertising.vo.PhaseChangeVo;
import com.adpilot.modules.advertising.vo.RollbackVo;
import com.adpilot.modules.store.entity.StoreEntity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link HostingOperationController} wiring (task 24.12).
 *
 * <p>Validates: Requirements 24.1, 24.2, 24.3, 24.4, 24.5, 10.6, 17.5.
 *
 * <p>These tests assert the controller correctly: (a) resolves every inbound id through the
 * org-isolation guard before mutating state, (b) resolves the approver/actor from the security
 * context, (c) delegates to the right service with the right arguments, (d) chooses the
 * confirmation vs. non-confirmation rollback path from the {@code confirm} flag, and
 * (e) surfaces structured results (post-transition sync state, conflicting operation ids, phase).
 */
@ExtendWith(MockitoExtension.class)
class HostingOperationControllerTest {

    @Mock
    private HostingApprovalService hostingApprovalService;

    @Mock
    private RollbackService rollbackService;

    @Mock
    private PhaseConfigurationService phaseConfigurationService;

    @Mock
    private HostingOrgIsolationGuard orgIsolationGuard;

    @InjectMocks
    private HostingOperationController controller;

    private UUID actorId;

    @BeforeEach
    void authenticate() {
        actorId = UUID.randomUUID();
        CurrentUser principal = CurrentUser.builder()
                .userId(actorId.toString())
                .email("approver@example.com")
                .orgId(UUID.randomUUID().toString())
                .name("Approver")
                .roles(Set.of("operations_manager"))
                .permissions(List.of("advertising:approve", "advertising:execute"))
                .build();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    // ──────────────────────────────────────────────────────────────────────
    // Approve (Req 24.2)
    // ──────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("approve resolves org isolation, delegates with the authenticated approver, and returns post-transition sync state")
    void approveDelegatesAndReturnsState() {
        UUID operationId = UUID.randomUUID();
        when(orgIsolationGuard.resolveOperationInCallerOrg(operationId))
                .thenReturn(operationEntity(operationId, "pending"));

        ApiResponse<OperationActionVo> response = controller.approve(operationId.toString());

        verify(hostingApprovalService).approveOperation(operationId, actorId);
        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getData().getOperationId()).isEqualTo(operationId.toString());
        assertThat(response.getData().getAction()).isEqualTo("approved");
        assertThat(response.getData().getSyncState()).isEqualTo("pending");
    }

    @Test
    @DisplayName("approve fails closed for an invalid operation id without calling the approval service")
    void approveRejectsInvalidId() {
        assertThatThrownBy(() -> controller.approve("not-a-uuid"))
                .isInstanceOf(BusinessException.class);
        verify(hostingApprovalService, never()).approveOperation(any(), any());
    }

    @Test
    @DisplayName("approve propagates the org-isolation guard denial and never calls the approval service")
    void approvePropagatesOrgIsolationDenial() {
        UUID operationId = UUID.randomUUID();
        when(orgIsolationGuard.resolveOperationInCallerOrg(operationId))
                .thenThrow(new BusinessException(403, "FORBIDDEN", "denied"));

        assertThatThrownBy(() -> controller.approve(operationId.toString()))
                .isInstanceOf(BusinessException.class);
        verify(hostingApprovalService, never()).approveOperation(any(), any());
    }

    @Test
    @DisplayName("approve fails closed when there is no authenticated user")
    void approveRequiresAuthenticatedUser() {
        SecurityContextHolder.clearContext();
        UUID operationId = UUID.randomUUID();
        when(orgIsolationGuard.resolveOperationInCallerOrg(operationId))
                .thenReturn(operationEntity(operationId, "awaiting_approval"));

        assertThatThrownBy(() -> controller.approve(operationId.toString()))
                .isInstanceOf(BusinessException.class);
        verify(hostingApprovalService, never()).approveOperation(any(), any());
    }

    // ──────────────────────────────────────────────────────────────────────
    // Reject (Req 24.3)
    // ──────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("reject delegates with the provided reason and returns the cancelled sync state")
    void rejectDelegatesWithReason() {
        UUID operationId = UUID.randomUUID();
        when(orgIsolationGuard.resolveOperationInCallerOrg(operationId))
                .thenReturn(operationEntity(operationId, "cancelled"));

        ApiResponse<OperationActionVo> response =
                controller.reject(operationId.toString(), new OperationRejectRequest("too risky"));

        verify(hostingApprovalService).rejectOperation(operationId, actorId, "too risky");
        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getData().getAction()).isEqualTo("rejected");
        assertThat(response.getData().getSyncState()).isEqualTo("cancelled");
    }

    // ──────────────────────────────────────────────────────────────────────
    // Rollback (Req 10.6)
    // ──────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("rollback without confirm uses rollback() and surfaces a created compensating operation")
    void rollbackWithoutConfirmUsesPlainRollback() {
        UUID operationId = UUID.randomUUID();
        UUID compensatingId = UUID.randomUUID();
        when(orgIsolationGuard.resolveOperationInCallerOrg(operationId))
                .thenReturn(operationEntity(operationId, "effective"));
        when(rollbackService.rollback(operationId))
                .thenReturn(RollbackResult.success(operationResult(compensatingId, SyncState.PENDING)));

        ApiResponse<RollbackVo> response = controller.rollback(operationId.toString(), false);

        verify(rollbackService).rollback(operationId);
        verify(rollbackService, never()).rollbackWithConfirmation(any());
        assertThat(response.getData().isConfirmationRequired()).isFalse();
        assertThat(response.getData().getCompensatingOperationId()).isEqualTo(compensatingId.toString());
        assertThat(response.getData().getSyncState()).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("rollback without confirm surfaces conflicting operation ids when confirmation is required")
    void rollbackSurfacesConflictingIds() {
        UUID operationId = UUID.randomUUID();
        UUID conflictA = UUID.randomUUID();
        UUID conflictB = UUID.randomUUID();
        when(orgIsolationGuard.resolveOperationInCallerOrg(operationId))
                .thenReturn(operationEntity(operationId, "effective"));
        when(rollbackService.rollback(operationId)).thenReturn(
                RollbackResult.requiresConfirmation(List.of(conflictA, conflictB), "overlap detected"));

        ApiResponse<RollbackVo> response = controller.rollback(operationId.toString(), false);

        assertThat(response.getData().isConfirmationRequired()).isTrue();
        assertThat(response.getData().getConflictingOperationIds())
                .containsExactlyInAnyOrder(conflictA.toString(), conflictB.toString());
        assertThat(response.getData().getWarningMessage()).isEqualTo("overlap detected");
        assertThat(response.getData().getCompensatingOperationId()).isNull();
    }

    @Test
    @DisplayName("rollback with confirm=true uses rollbackWithConfirmation()")
    void rollbackWithConfirmUsesConfirmationPath() {
        UUID operationId = UUID.randomUUID();
        UUID compensatingId = UUID.randomUUID();
        when(orgIsolationGuard.resolveOperationInCallerOrg(operationId))
                .thenReturn(operationEntity(operationId, "effective"));
        when(rollbackService.rollbackWithConfirmation(operationId))
                .thenReturn(RollbackResult.success(operationResult(compensatingId, SyncState.AWAITING_APPROVAL)));

        ApiResponse<RollbackVo> response = controller.rollback(operationId.toString(), true);

        verify(rollbackService).rollbackWithConfirmation(operationId);
        verify(rollbackService, never()).rollback(any());
        assertThat(response.getData().isConfirmationRequired()).isFalse();
        assertThat(response.getData().getCompensatingOperationId()).isEqualTo(compensatingId.toString());
        assertThat(response.getData().getSyncState()).isEqualTo("AWAITING_APPROVAL");
    }

    // ──────────────────────────────────────────────────────────────────────
    // Phase change (Req 17.5)
    // ──────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("changePhase resolves the store in the caller org, transitions, and returns previous/active phases")
    void changePhaseDelegatesAndReturnsPhases() {
        UUID storeId = UUID.randomUUID();
        StoreEntity store = new StoreEntity();
        store.setId(storeId);
        when(orgIsolationGuard.resolveStoreInCallerOrg(storeId)).thenReturn(store);
        when(phaseConfigurationService.getCurrentPhase(storeId))
                .thenReturn(HostingPhase.V1)   // before
                .thenReturn(HostingPhase.V2);  // after

        ApiResponse<PhaseChangeVo> response =
                controller.changePhase(new PhaseChangeRequest(storeId.toString(), "v2"));

        ArgumentCaptor<HostingPhase> phaseCaptor = ArgumentCaptor.forClass(HostingPhase.class);
        verify(phaseConfigurationService).transitionPhase(eq(storeId), phaseCaptor.capture(), eq(actorId));
        assertThat(phaseCaptor.getValue()).isEqualTo(HostingPhase.V2);
        assertThat(response.getData().getStoreId()).isEqualTo(storeId.toString());
        assertThat(response.getData().getPreviousPhase()).isEqualTo("V1");
        assertThat(response.getData().getActivePhase()).isEqualTo("V2");
    }

    @Test
    @DisplayName("changePhase rejects an unrecognized phase without transitioning")
    void changePhaseRejectsInvalidPhase() {
        UUID storeId = UUID.randomUUID();
        StoreEntity store = new StoreEntity();
        store.setId(storeId);
        when(orgIsolationGuard.resolveStoreInCallerOrg(storeId)).thenReturn(store);

        assertThatThrownBy(() ->
                controller.changePhase(new PhaseChangeRequest(storeId.toString(), "V9")))
                .isInstanceOf(BusinessException.class);
        verify(phaseConfigurationService, never()).transitionPhase(any(), any(), any());
    }

    // ──────────────────────────────────────────────────────────────────────
    // Helpers
    // ──────────────────────────────────────────────────────────────────────

    private static OperationEntity operationEntity(UUID id, String syncState) {
        OperationEntity entity = new OperationEntity();
        entity.setId(id);
        entity.setStoreId(UUID.randomUUID());
        entity.setSyncState(syncState);
        return entity;
    }

    private static OperationResult operationResult(UUID id, SyncState syncState) {
        return OperationResult.builder()
                .operationId(id)
                .syncState(syncState)
                .build();
    }
}
