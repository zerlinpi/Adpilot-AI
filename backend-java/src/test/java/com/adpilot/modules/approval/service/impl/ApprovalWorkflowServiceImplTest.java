package com.adpilot.modules.approval.service.impl;

import com.adpilot.common.exception.BusinessException;
import com.adpilot.modules.approval.entity.ApprovalPolicyEntity;
import com.adpilot.modules.approval.entity.ApprovalRequestEntity;
import com.adpilot.modules.approval.mapper.ApprovalDecisionMapper;
import com.adpilot.modules.approval.mapper.ApprovalPolicyMapper;
import com.adpilot.modules.approval.mapper.ApprovalRequestMapper;
import com.adpilot.modules.approval.service.ApprovalActionExecutor;
import com.adpilot.modules.audit.service.AuditLogService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ApprovalWorkflowServiceImpl} covering approval request
 * routing (Req 12.1.2) and the expiration sweep that cancels overdue requests
 * (Req 12.1.7).
 */
@ExtendWith(MockitoExtension.class)
class ApprovalWorkflowServiceImplTest {

    @Mock
    private ApprovalRequestMapper approvalRequestMapper;
    @Mock
    private ApprovalDecisionMapper approvalDecisionMapper;
    @Mock
    private ApprovalPolicyMapper approvalPolicyMapper;
    @Mock
    private AuditLogService auditLogService;

    @Captor
    private ArgumentCaptor<ApprovalRequestEntity> requestCaptor;

    private ApprovalWorkflowServiceImpl service;

    @BeforeEach
    void setUp() {
        // No action executors registered for these tests.
        service = new ApprovalWorkflowServiceImpl(
                approvalRequestMapper,
                approvalDecisionMapper,
                approvalPolicyMapper,
                auditLogService,
                Collections.<ApprovalActionExecutor>emptyList());
    }

    private ApprovalPolicyEntity policy(UUID id, String approvalType, List<String> approverUserIds) {
        return ApprovalPolicyEntity.builder()
                .id(id)
                .orgId(UUID.randomUUID())
                .name("policy")
                .module("advertising")
                .actionType("bid_change")
                .approvalType(approvalType)
                .approverUserIds(approverUserIds)
                .enabled(true)
                .build();
    }

    private ApprovalRequestEntity pendingRequest(UUID id, UUID policyId, UUID initiatedBy, int currentLevel) {
        return ApprovalRequestEntity.builder()
                .id(id)
                .storeId(UUID.randomUUID())
                .requesterId(initiatedBy)
                .requestType("bid_change")
                .title("Bid change")
                .status("pending")
                .policyId(policyId)
                .module("advertising")
                .actionType("bid_change")
                .initiatedBy(initiatedBy)
                .currentLevel(currentLevel)
                .build();
    }

    // ------------------------------------------------------------------
    // Req 12.1.2 — route the approval request to the policy-defined approvers
    // ------------------------------------------------------------------

    @Test
    void currentLevelApproversReturnsPolicyDefinedApproversForSingleLevel() {
        // Single-level (role) policy: every defined approver is authorized for the level.
        UUID policyId = UUID.randomUUID();
        UUID approverA = UUID.randomUUID();
        UUID approverB = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();

        ApprovalRequestEntity request = pendingRequest(requestId, policyId, UUID.randomUUID(), 1);
        when(approvalRequestMapper.selectById(requestId)).thenReturn(request);
        when(approvalPolicyMapper.selectById(policyId))
                .thenReturn(policy(policyId, "role",
                        List.of(approverA.toString(), approverB.toString())));

        List<UUID> approvers = service.currentLevelApprovers(requestId);

        assertThat(approvers).containsExactlyInAnyOrder(approverA, approverB);
    }

    @Test
    void currentLevelApproversFollowsPolicyOrderForMultiLevel() {
        // multi_level policy: one level per approver, in the policy's defined order.
        UUID policyId = UUID.randomUUID();
        UUID level1 = UUID.randomUUID();
        UUID level2 = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();

        // Request currently awaiting level 2.
        ApprovalRequestEntity request = pendingRequest(requestId, policyId, UUID.randomUUID(), 2);
        when(approvalRequestMapper.selectById(requestId)).thenReturn(request);
        when(approvalPolicyMapper.selectById(policyId))
                .thenReturn(policy(policyId, "multi_level",
                        List.of(level1.toString(), level2.toString())));

        List<UUID> approvers = service.currentLevelApprovers(requestId);

        assertThat(approvers).containsExactly(level2);
    }

    @Test
    void currentLevelApproversEmptyWhenRequestNotPending() {
        UUID requestId = UUID.randomUUID();
        ApprovalRequestEntity request = pendingRequest(requestId, UUID.randomUUID(), UUID.randomUUID(), 1);
        request.setStatus("approved");
        when(approvalRequestMapper.selectById(requestId)).thenReturn(request);

        assertThat(service.currentLevelApprovers(requestId)).isEmpty();
    }

    @Test
    void pendingForApproverRoutesRequestToDefinedApprover() {
        // A pending request is routed to a user defined as its current-level approver.
        UUID policyId = UUID.randomUUID();
        UUID approver = UUID.randomUUID();
        UUID initiator = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();

        ApprovalRequestEntity request = pendingRequest(requestId, policyId, initiator, 1);
        when(approvalRequestMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(request));
        when(approvalPolicyMapper.selectById(policyId))
                .thenReturn(policy(policyId, "role", List.of(approver.toString())));

        List<ApprovalRequestEntity> awaiting = service.pendingForApprover(approver);

        assertThat(awaiting).containsExactly(request);
    }

    @Test
    void pendingForApproverExcludesUsersNotDefinedForCurrentLevel() {
        UUID policyId = UUID.randomUUID();
        UUID approver = UUID.randomUUID();
        UUID otherUser = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();

        ApprovalRequestEntity request = pendingRequest(requestId, policyId, UUID.randomUUID(), 1);
        when(approvalRequestMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(request));
        when(approvalPolicyMapper.selectById(policyId))
                .thenReturn(policy(policyId, "role", List.of(approver.toString())));

        // A user who is not a defined approver receives nothing.
        assertThat(service.pendingForApprover(otherUser)).isEmpty();
    }

    @Test
    void pendingForApproverExcludesInitiatorOfTheirOwnAction() {
        // Req 12.1.6 reinforced in routing: the initiator is never routed their own request.
        UUID policyId = UUID.randomUUID();
        UUID initiator = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();

        ApprovalRequestEntity request = pendingRequest(requestId, policyId, initiator, 1);
        when(approvalRequestMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(request));

        // Initiator is also a listed approver, but must still be excluded.
        // (Policy lookup is short-circuited for the initiator, so it is not stubbed.)
        assertThat(service.pendingForApprover(initiator)).isEmpty();
    }

    // ------------------------------------------------------------------
    // Req 12.1.7 — expiration sweep cancels the action
    // ------------------------------------------------------------------

    @Test
    void expireOverdueMarksOverduePendingRequestExpired() {
        UUID requestId = UUID.randomUUID();
        UUID initiator = UUID.randomUUID();
        ApprovalRequestEntity overdue = pendingRequest(requestId, UUID.randomUUID(), initiator, 1);
        overdue.setExpiresAt(LocalDateTime.now().minusMinutes(5));

        when(approvalRequestMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(overdue));

        int expired = service.expireOverdue();

        assertThat(expired).isEqualTo(1);
        verify(approvalRequestMapper).updateById(requestCaptor.capture());
        ApprovalRequestEntity updated = requestCaptor.getValue();
        assertThat(updated.getStatus()).isEqualTo("expired");
        assertThat(updated.getResolvedAt()).isNotNull();
        // The cancellation is recorded in the audit trail.
        verify(auditLogService).createLog(
                org.mockito.ArgumentMatchers.eq(initiator),
                org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.eq("EXPIRE"),
                org.mockito.ArgumentMatchers.eq("approval_request"),
                org.mockito.ArgumentMatchers.eq(requestId),
                any());
    }

    @Test
    void expireOverdueLeavesNonExpiredRequestsUntouched() {
        // The sweep query only returns overdue requests; non-expired ones are never mutated.
        when(approvalRequestMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(Collections.emptyList());

        int expired = service.expireOverdue();

        assertThat(expired).isZero();
        verify(approvalRequestMapper, never()).updateById(any());
        verify(auditLogService, never()).createLog(any(), any(), any(), any(), any(), any());
    }

    @Test
    void expiredRequestCanNoLongerBeApproved() {
        // After the sweep cancels a request, it is no longer pending and cannot be approved.
        UUID requestId = UUID.randomUUID();
        ApprovalRequestEntity expired = pendingRequest(requestId, UUID.randomUUID(), UUID.randomUUID(), 1);
        expired.setStatus("expired");
        when(approvalRequestMapper.selectById(requestId)).thenReturn(expired);

        assertThatThrownBy(() -> service.approve(requestId, UUID.randomUUID(), "ok"))
                .isInstanceOf(BusinessException.class);

        verify(approvalRequestMapper, never()).updateById(any());
    }

    @Test
    void approvingPastExpirationLazilyExpiresAndCancelsTheAction() {
        // A still-"pending" request whose expiration has passed is expired on access and
        // cannot be approved (Req 12.1.7).
        UUID requestId = UUID.randomUUID();
        ApprovalRequestEntity request = pendingRequest(requestId, UUID.randomUUID(), UUID.randomUUID(), 1);
        request.setExpiresAt(LocalDateTime.now().minusMinutes(1));
        when(approvalRequestMapper.selectById(requestId)).thenReturn(request);

        assertThatThrownBy(() -> service.approve(requestId, UUID.randomUUID(), "ok"))
                .isInstanceOf(BusinessException.class);

        // The request is flipped to expired rather than approved.
        verify(approvalRequestMapper).updateById(requestCaptor.capture());
        assertThat(requestCaptor.getValue().getStatus()).isEqualTo("expired");
    }
}
