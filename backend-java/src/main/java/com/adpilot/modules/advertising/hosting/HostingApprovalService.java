package com.adpilot.modules.advertising.hosting;

import java.util.UUID;

/**
 * Service interface for AI Hosting Operation approvals, integrating with the
 * existing {@code approval_requests} infrastructure (Requirement 40).
 *
 * <p>This service bridges the hosting routing pipeline's
 * {@link RoutingOutcome#AWAITING_APPROVAL_OPERATION} outcome to the platform's
 * existing approval_requests table. It creates approval records when Operations
 * are routed to awaiting_approval, and provides atomic approve/reject that
 * synchronizes the approval_requests status with the Operation's SyncState in
 * a single transaction.</p>
 *
 * <p>Design invariants:</p>
 * <ul>
 *   <li>The Operation SyncState is the authoritative execution state (Req 40.3).</li>
 *   <li>The approval_requests record drives the human decision but SHALL NOT
 *       diverge from the Operation's state.</li>
 *   <li>Self-approval is forbidden: the decision originator cannot approve their
 *       own decision (Req 40.4).</li>
 * </ul>
 *
 * <p>Validates: Requirements 24.2, 24.3, 40.1, 40.2, 40.3, 40.4.</p>
 */
public interface HostingApprovalService {

    /**
     * Create an {@code approval_requests} record linked to an Operation that has
     * been created in {@code awaiting_approval} by the routing pipeline.
     *
     * <p>This is called within the same transaction that creates the Operation,
     * so both records are atomically visible or invisible (Req 40.1).</p>
     *
     * @param operationId  the ID of the Operation in awaiting_approval state
     * @param storeId      the store the operation belongs to
     * @param requesterId  the user who triggered/created the decision (originator)
     * @param title        a human-readable title for the approval request
     * @param reason       the routing reason (e.g., "high_risk_state_change",
     *                     "risk_above_threshold", "approval_required_mode")
     * @param riskLevel    the risk level classification ("low", "medium", "high")
     * @return the ID of the created approval_requests record
     */
    UUID createApprovalRequest(UUID operationId, UUID storeId, UUID requesterId,
                               String title, String reason, String riskLevel);

    /**
     * Atomically approve an awaiting_approval Operation and its linked
     * approval_requests record (Req 40.2, 24.2).
     *
     * <p>In a single transaction:</p>
     * <ol>
     *   <li>Validates the approver is not the decision originator (Req 40.4).</li>
     *   <li>Updates the approval_requests status to "approved".</li>
     *   <li>Transitions the Operation from awaiting_approval → pending via the
     *       APPROVE event.</li>
     *   <li>Writes the Outbox entry so OutboxWorker can claim and submit.</li>
     * </ol>
     *
     * @param operationId the ID of the Operation to approve
     * @param approverId  the user approving the request
     * @throws com.adpilot.common.exception.BusinessException if self-approval is
     *         attempted, if the Operation is not in awaiting_approval, or if no
     *         linked approval request exists
     */
    void approveOperation(UUID operationId, UUID approverId);

    /**
     * Atomically reject an awaiting_approval Operation and its linked
     * approval_requests record (Req 40.2, 24.3).
     *
     * <p>In a single transaction:</p>
     * <ol>
     *   <li>Updates the approval_requests status to "rejected" with the reason.</li>
     *   <li>Transitions the Operation from awaiting_approval → cancelled via the
     *       REJECT event.</li>
     * </ol>
     *
     * @param operationId the ID of the Operation to reject
     * @param approverId  the user rejecting the request
     * @param reason      the rejection reason provided by the approver
     * @throws com.adpilot.common.exception.BusinessException if the Operation is
     *         not in awaiting_approval or if no linked approval request exists
     */
    void rejectOperation(UUID operationId, UUID approverId, String reason);

    /**
     * Close any open approval_requests linked to an Operation that is being
     * cancelled/superseded by external forces (kill switch, phase downgrade,
     * decision expiry) — Requirement 40.6.
     *
     * @param operationId the ID of the Operation being cancelled/superseded
     * @param closeReason the reason for closure (e.g., "KILL_SWITCH",
     *                    "PHASE_DOWNGRADE", "DECISION_EXPIRED")
     */
    void closeApprovalRequest(UUID operationId, String closeReason);
}
