package com.adpilot.modules.advertising.vo;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Response view for {@code POST /api/advertising/hosting/operations/{id}/rollback} (Req 10.6).
 *
 * <p>A rollback request can resolve to one of two outcomes:</p>
 * <ul>
 *   <li><b>Confirmation required</b>: overlapping subsequent effective operations were detected
 *       on the same entity+field. {@code confirmation_required} is {@code true}, the
 *       {@code conflicting_operation_ids} surface the operations the caller must acknowledge,
 *       and no compensating operation was created. The caller re-issues with {@code confirm=true}
 *       to proceed.</li>
 *   <li><b>Executed</b>: a compensating Operation was created and routed through the standard
 *       pipeline. {@code confirmation_required} is {@code false} and {@code compensating_operation_id}
 *       / {@code sync_state} carry the new Operation's identity and state.</li>
 * </ul>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RollbackVo {

    /** Whether explicit confirmation is required before the rollback can proceed. */
    @JsonProperty("confirmation_required")
    private boolean confirmationRequired;

    /** The overlapping subsequent operations that conflict with the rollback (when confirmation required). */
    @JsonProperty("conflicting_operation_ids")
    private List<String> conflictingOperationIds;

    /** A human-readable warning describing the overlap (when confirmation required). */
    @JsonProperty("warning_message")
    private String warningMessage;

    /** The created compensating Operation's id (when the rollback was executed). */
    @JsonProperty("compensating_operation_id")
    private String compensatingOperationId;

    /** The compensating Operation's resolved sync state (when the rollback was executed). */
    @JsonProperty("sync_state")
    private String syncState;
}
