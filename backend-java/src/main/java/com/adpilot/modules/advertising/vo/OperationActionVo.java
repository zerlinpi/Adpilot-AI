package com.adpilot.modules.advertising.vo;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Response view for the approve/reject endpoints (Req 24.2, 24.3).
 *
 * <p>Surfaces the affected Operation's id and its resolved {@code sync_state} after the
 * approve/reject transition so the dashboard can update the decision list in real time
 * without a full reload (Req 24.5). After an approve the state is {@code pending}
 * (the OutboxWorker then claims it); after a reject the state is {@code cancelled}.</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OperationActionVo {

    /** The Operation that was approved or rejected. */
    @JsonProperty("operation_id")
    private String operationId;

    /** The action applied: {@code approved} or {@code rejected}. */
    @JsonProperty("action")
    private String action;

    /** The Operation's resolved sync state after the action. */
    @JsonProperty("sync_state")
    private String syncState;
}
