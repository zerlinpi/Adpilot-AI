package com.adpilot.modules.advertising.vo;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Success body for {@code POST /api/advertising/hosting/optimize/trigger} (Req 28.1, 28.5).
 *
 * <p>The trigger response is synchronous-only: a valid trigger immediately returns HTTP 202
 * carrying the {@code run_id} the frontend polls for completion, while the actual optimization
 * work executes asynchronously (Req 26.4, 28.1). Asynchronous platform failures are recorded on
 * the Operation / optimization-run result, never on this response.</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OptimizationTriggerVo {

    /** The created optimization run's id, used for polling completion (Req 28.5). */
    @JsonProperty("run_id")
    private String runId;

    /** The run's status at trigger time (always {@code running}). */
    @JsonProperty("status")
    private String status;

    /** Correlation id echoed back so the caller can trace this trigger (Req 26.5). */
    @JsonProperty("request_id")
    private String requestId;
}
