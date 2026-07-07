package com.adpilot.modules.approval.service;

import com.adpilot.modules.approval.entity.ApprovalRequestEntity;

import java.util.List;
import java.util.UUID;

/**
 * Policy-driven approval workflow over governed actions gated by
 * {@link com.adpilot.modules.approval.aspect.ApprovalAspect} (Req 12.1).
 *
 * <p>Distinct from the store-centric {@link ApprovalService}: this service
 * operates on the policy fields of {@code approval_requests}
 * ({@code policy_id}/{@code module}/{@code action_type}/{@code initiated_by}/
 * {@code current_level}) and the {@code approval_decisions} table. It routes a
 * pending request to the approvers defined by its {@code ApprovalPolicy}
 * (Req 12.1.2), requires approval at each level in the policy's order
 * (Req 12.1.5), executes the gated action exactly once when all levels approve
 * (Req 12.1.3), cancels and records on rejection (Req 12.1.4), forbids the
 * initiator from approving their own action (Req 12.1.6), and expires/cancels
 * requests left unapproved past their expiration (Req 12.1.7).
 */
public interface ApprovalWorkflowService {

    /**
     * Returns the approvers authorized to act on the request's <em>current</em>
     * level, as defined by its governing policy (Req 12.1.2). Empty when the
     * request is not found, is no longer pending, or its policy defines no
     * approvers.
     *
     * @param requestId the approval request id
     * @return ordered list of approver user ids for the current level
     */
    List<UUID> currentLevelApprovers(UUID requestId);

    /**
     * Returns the pending policy-driven requests currently awaiting a decision
     * from the given approver (i.e. the approver is authorized for each request's
     * current level), supporting routing of requests to approvers (Req 12.1.2).
     *
     * @param approverId the candidate approver's user id
     * @return pending requests awaiting this approver at their current level
     */
    List<ApprovalRequestEntity> pendingForApprover(UUID approverId);

    /**
     * Records an approval at the request's current level. Advances to the next
     * level when more remain (Req 12.1.5); when the final level is approved,
     * marks the request approved and executes the gated action exactly once
     * (Req 12.1.3). The initiating user may never approve their own action
     * (Req 12.1.6), and only an approver defined for the current level may
     * approve it.
     *
     * @param requestId  the approval request id
     * @param approverId the approving user's id
     * @param comment    optional approval comment
     * @return the updated request (still {@code pending} if levels remain, else {@code approved})
     */
    ApprovalRequestEntity approve(UUID requestId, UUID approverId, String comment);

    /**
     * Records a rejection at the current level, cancels the action, and marks the
     * request {@code rejected} (Req 12.1.4). The initiating user may not reject as
     * an approver, and only an approver defined for the current level may reject.
     *
     * @param requestId  the approval request id
     * @param approverId the rejecting user's id
     * @param reason     the rejection reason
     * @return the updated, rejected request
     */
    ApprovalRequestEntity reject(UUID requestId, UUID approverId, String reason);

    /**
     * Marks every pending request whose expiration has passed as {@code expired}
     * and cancels it (Req 12.1.7). Intended to be driven on a schedule.
     *
     * @return the number of requests expired by this sweep
     */
    int expireOverdue();
}
