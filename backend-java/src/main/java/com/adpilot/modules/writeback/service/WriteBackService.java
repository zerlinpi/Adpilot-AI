package com.adpilot.modules.writeback.service;

import com.adpilot.modules.writeback.vo.WriteBackResultVo;

/**
 * Applies an approved AI recommendation to the live platform (Req 13.1).
 *
 * <p>The single entry point {@link #apply(String)} submits the recommendation's
 * change to the platform that owns the store's
 * {@link com.adpilot.modules.apisync.entity.PlatformConnectionEntity} via the
 * platform's {@link com.adpilot.modules.apisync.connector.PlatformWriteConnector},
 * records the change, submitting user, and platform response in the audit trail
 * (Req 13.1.2), and—on platform rejection—records the reason while leaving the
 * internal record unchanged (Req 13.1.3). When the action is governed by an
 * enabled approval policy whose threshold is met, the submission is gated until
 * the approval completes (Req 13.1.4).
 */
public interface WriteBackService {

    /**
     * Submit the change corresponding to the given recommendation to the live
     * platform and, on acceptance, record the internal change.
     *
     * @param recommendationId the recommendation to apply
     * @return the apply outcome, or {@code null} when the action was gated into a
     *         pending-approval state by an enabled approval policy (Req 13.1.4);
     *         a {@code null} return means no change was submitted to the platform.
     */
    WriteBackResultVo apply(String recommendationId);
}
