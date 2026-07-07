package com.adpilot.modules.advertising.controller;

import com.adpilot.common.api.ApiResponse;
import com.adpilot.common.exception.BusinessException;
import com.adpilot.common.security.RequirePermission;
import com.adpilot.common.utils.SecurityUtils;
import com.adpilot.modules.advertising.dto.OptimizationTriggerRequest;
import com.adpilot.modules.advertising.entity.CampaignEntity;
import com.adpilot.modules.advertising.hosting.HostingOptimizationService;
import com.adpilot.modules.advertising.hosting.HostingOrgIsolationGuard;
import com.adpilot.modules.advertising.hosting.OptimizationRunEntity;
import com.adpilot.modules.advertising.vo.HostingErrorVo;
import com.adpilot.modules.advertising.vo.OptimizationRunDetailVo;
import com.adpilot.modules.advertising.vo.OptimizationTriggerVo;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.UUID;

/**
 * Optimization trigger and run-detail APIs under {@code /api/advertising/hosting} (Req 26, 28).
 *
 * <p>Exposes:
 * <ul>
 *   <li>{@code POST /optimize/trigger} — start a manual optimization run; returns HTTP 202 with a
 *       {@code run_id} (Req 28.1, 28.5). Requires {@code advertising:execute}.</li>
 *   <li>{@code GET /optimization-runs/{runId}} — poll a run's status / per-campaign results
 *       (Req 28.7). Requires {@code advertising:view}.</li>
 * </ul>
 *
 * <p><b>Synchronous-only trigger (Req 26.4, 28.1).</b> The trigger validates parameters and the
 * per-store minimum interval synchronously (returning HTTP 400/422/429 on failure), then returns
 * 202 + run_id immediately; the per-campaign work runs asynchronously and Amazon Ads failures that
 * occur during that work are recorded on the Operation / run result, never on this response.</p>
 *
 * <p><b>Cross-organization isolation (Req 39).</b> Every inbound {@code storeId}/{@code campaignId}/
 * {@code runId} is resolved to the caller's organization via {@link HostingOrgIsolationGuard}
 * before any work happens; a cross-org or unknown id fails closed (403/404) with no resource
 * detail.</p>
 *
 * <p><b>Structured errors (Req 26.1, 26.5).</b> Every error response carries a {@code HOSTING_*}
 * code and a {@code request_id} inside the standard {@link ApiResponse} envelope.</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/advertising/hosting")
@RequiredArgsConstructor
public class HostingOptimizationController {

    private final HostingOptimizationService hostingOptimizationService;
    private final HostingOrgIsolationGuard orgIsolationGuard;
    private final ObjectMapper objectMapper;

    // ────────────────────────────────────────────────────────────────────────────
    // POST /optimize/trigger (Req 28.1–28.6)
    // ────────────────────────────────────────────────────────────────────────────

    @PostMapping("/optimize/trigger")
    @RequirePermission("advertising:execute")
    public ResponseEntity<ApiResponse<Object>> triggerOptimization(
            @RequestBody(required = false) OptimizationTriggerRequest request) {
        String requestId = newRequestId();
        try {
            if (request == null) {
                throw new BusinessException(400, "HOSTING_INVALID_PARAMETER", "request body is required");
            }
            UUID storeId = parseUuid(request.getStoreId(), "storeId");
            UUID campaignId = parseOptionalUuid(request.getCampaignId(), "campaignId");

            // Cross-org isolation first (Req 39): resolve every inbound id to the caller's org.
            orgIsolationGuard.resolveStoreInCallerOrg(storeId);
            if (campaignId != null) {
                CampaignEntity campaign = orgIsolationGuard.resolveCampaignInCallerOrg(campaignId);
                if (!storeId.equals(campaign.getStoreId())) {
                    throw new BusinessException(400, "HOSTING_INVALID_PARAMETER",
                            "campaignId does not belong to storeId");
                }
            }

            UUID actorId = currentActor();
            OptimizationRunEntity run = hostingOptimizationService.triggerManualRun(storeId, campaignId, actorId);

            OptimizationTriggerVo body = OptimizationTriggerVo.builder()
                    .runId(run.getId().toString())
                    .status(run.getStatus())
                    .requestId(requestId)
                    .build();
            return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiResponse.ok(body));
        } catch (BusinessException ex) {
            return hostingError(ex, requestId);
        }
    }

    // ────────────────────────────────────────────────────────────────────────────
    // GET /optimization-runs/{runId} (Req 28.7)
    // ────────────────────────────────────────────────────────────────────────────

    @GetMapping("/optimization-runs/{runId}")
    @RequirePermission("advertising:view")
    public ResponseEntity<ApiResponse<Object>> getOptimizationRun(@PathVariable String runId) {
        String requestId = newRequestId();
        try {
            UUID id = parseUuid(runId, "runId");
            // Resolves the run to the caller's org (404 unknown, 403 cross-org).
            OptimizationRunEntity run = orgIsolationGuard.resolveRunInCallerOrg(id);
            return ResponseEntity.ok(ApiResponse.ok(toDetailVo(run, requestId)));
        } catch (BusinessException ex) {
            return hostingError(ex, requestId);
        }
    }

    // ────────────────────────────────────────────────────────────────────────────
    // Helpers
    // ────────────────────────────────────────────────────────────────────────────

    private Object toDetailVo(OptimizationRunEntity run, String requestId) {
        return OptimizationRunDetailVo.builder()
                .runId(run.getId() != null ? run.getId().toString() : null)
                .storeId(run.getStoreId() != null ? run.getStoreId().toString() : null)
                .triggerType(run.getTriggerType())
                .status(run.getStatus())
                .phase(run.getPhase())
                .campaignsProcessed(run.getCampaignsProcessed())
                .campaignsSkipped(run.getCampaignsSkipped())
                .operationsCreated(run.getOperationsCreated())
                .decisionsGenerated(run.getDecisionsGenerated())
                .decisionsAutoExecuted(run.getDecisionsAutoExecuted())
                .decisionsRequiringApproval(run.getDecisionsRequiringApproval())
                .perCampaignResults(parseJson(run.getPerCampaignResults()))
                .skipReasons(parseJson(run.getSkipReasons()))
                .startedAt(run.getStartedAt())
                .completedAt(run.getCompletedAt())
                .requestId(requestId)
                .build();
    }

    private Object parseJson(String json) {
        if (json == null || json.isBlank()) {
            return new LinkedHashMap<String, Object>();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<LinkedHashMap<String, Object>>() {});
        } catch (Exception e) {
            // Surface the raw value rather than failing the read if it is not a JSON object.
            return json;
        }
    }

    private ResponseEntity<ApiResponse<Object>> hostingError(BusinessException ex, String requestId) {
        String code = hostingCode(ex);
        HostingErrorVo errorBody = HostingErrorVo.builder()
                .code(code)
                .message(ex.getMessage())
                .requestId(requestId)
                .build();
        ApiResponse<Object> response = new ApiResponse<>();
        response.setSuccess(false);
        response.setData(errorBody);
        response.setError(new ApiResponse.ErrorInfo(code, ex.getMessage()));
        return ResponseEntity.status(ex.getStatus()).body(response);
    }

    /** Map a thrown code to a structured {@code HOSTING_*} code (Req 26.1), preserving HTTP status. */
    private static String hostingCode(BusinessException ex) {
        String code = ex.getCode();
        if (code != null && code.startsWith("HOSTING_")) {
            return code;
        }
        return switch (ex.getStatus()) {
            case 404 -> "HOSTING_RESOURCE_NOT_FOUND";
            case 403 -> "HOSTING_FORBIDDEN";
            case 401 -> "HOSTING_UNAUTHORIZED";
            default -> "HOSTING_REQUEST_REJECTED";
        };
    }

    private static UUID parseUuid(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new BusinessException(400, "HOSTING_INVALID_PARAMETER", field + " must not be blank");
        }
        try {
            return UUID.fromString(value.trim());
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(400, "HOSTING_INVALID_PARAMETER",
                    "Invalid " + field + ": not a valid identifier");
        }
    }

    private static UUID parseOptionalUuid(String value, String field) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return parseUuid(value, field);
    }

    private static UUID currentActor() {
        String userId = SecurityUtils.getCurrentUserIdOrNull();
        return userId == null ? null : UUID.fromString(userId);
    }

    private static String newRequestId() {
        return UUID.randomUUID().toString();
    }
}
