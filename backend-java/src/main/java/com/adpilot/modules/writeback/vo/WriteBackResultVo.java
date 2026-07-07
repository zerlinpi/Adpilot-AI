package com.adpilot.modules.writeback.vo;

import lombok.Builder;
import lombok.Data;

/**
 * The outcome of a {@code WriteBackService.apply(recommendationId)} call.
 *
 * <p>Reports whether the change was submitted and accepted by the live platform
 * (Req 13.1.1), whether it was rejected with the platform's reason (Req 13.1.3),
 * or whether it was placed in a pending-approval state instead of being
 * submitted (Req 13.1.4).
 */
@Data
@Builder
public class WriteBackResultVo {

    /** The recommendation the apply targeted. */
    private String recommendationId;

    /**
     * Terminal outcome of the apply:
     * <ul>
     *   <li>{@code applied} — submitted and accepted by the platform; the
     *       internal record was updated.</li>
     *   <li>{@code rejected} — the platform rejected the change; the internal
     *       record was left unchanged and the reason recorded.</li>
     *   <li>{@code pending_approval} — gated by an enabled approval policy; no
     *       change was submitted.</li>
     * </ul>
     */
    private String status;

    /** {@code true} when the platform accepted and the internal record was updated. */
    private boolean applied;

    /** The platform-assigned reference for an accepted change, if any. */
    private String platformReference;

    /** A success message or the platform's rejection reason. */
    private String message;
}
