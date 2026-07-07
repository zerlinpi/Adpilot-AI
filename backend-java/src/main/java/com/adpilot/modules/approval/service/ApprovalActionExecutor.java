package com.adpilot.modules.approval.service;

import com.adpilot.modules.approval.entity.ApprovalRequestEntity;

/**
 * Resumes and executes a governed action once its approval completes (Req 12.1.3).
 *
 * <p>When {@link com.adpilot.modules.approval.aspect.ApprovalAspect} gates an
 * action, the original method is short-circuited and the action is parked as a
 * pending {@code approval_request} carrying its {@code module}, {@code actionType}
 * and a JSON {@code payload} of the original arguments. After every required
 * approval level approves, {@link ApprovalWorkflowService} looks up the executor
 * registered for that {@code module}/{@code actionType} and invokes
 * {@link #execute(ApprovalRequestEntity)} exactly once to carry the action out.
 *
 * <p>This is a registry pattern: each governed module contributes one Spring
 * bean implementing this interface, keyed by {@link #module()} + {@link #actionType()}.
 * Modules that have not yet registered an executor simply have their approved
 * action recorded as completed without a resume step (logged as a no-op); the
 * approval bookkeeping (decisions, status) is unaffected.
 */
public interface ApprovalActionExecutor {

    /** The governed module this executor handles, e.g. {@code "advertising"}. */
    String module();

    /** The governed action type this executor handles, e.g. {@code "bid_change"}. */
    String actionType();

    /**
     * Executes the previously-gated action using the request's stored
     * {@code payload}. Called at most once per request, only after all required
     * approval levels have approved.
     *
     * @param request the fully-approved approval request describing the action
     */
    void execute(ApprovalRequestEntity request);
}
