package com.adpilot.modules.advertising.controller;

import com.adpilot.common.api.ApiResponse;
import com.adpilot.common.exception.BusinessException;
import com.adpilot.modules.advertising.dto.OptimizationTriggerRequest;
import com.adpilot.modules.advertising.entity.CampaignEntity;
import com.adpilot.modules.advertising.hosting.HostingOptimizationService;
import com.adpilot.modules.advertising.hosting.HostingOrgIsolationGuard;
import com.adpilot.modules.advertising.hosting.OptimizationRunEntity;
import com.adpilot.modules.advertising.vo.HostingErrorVo;
import com.adpilot.modules.advertising.vo.OptimizationRunDetailVo;
import com.adpilot.modules.advertising.vo.OptimizationTriggerVo;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link HostingOptimizationController}: the synchronous-only 202 trigger contract
 * (Req 26.4, 28.1, 28.5), structured {@code HOSTING_*} errors carrying a {@code request_id}
 * (Req 26.1, 26.5), and the run-detail endpoint (Req 28.7).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class HostingOptimizationControllerTest {

    @Mock
    private HostingOptimizationService hostingOptimizationService;
    @Mock
    private HostingOrgIsolationGuard orgIsolationGuard;

    private HostingOptimizationController controller;

    @BeforeEach
    void setUp() {
        controller = new HostingOptimizationController(
                hostingOptimizationService, orgIsolationGuard, new ObjectMapper());
    }

    private OptimizationRunEntity runningRun(UUID storeId) {
        return OptimizationRunEntity.builder()
                .id(UUID.randomUUID())
                .storeId(storeId)
                .triggerType("manual")
                .status("running")
                .startedAt(LocalDateTime.now())
                .build();
    }

    // ── POST /optimize/trigger ───────────────────────────────────────────────────

    @Test
    void triggerReturns202WithRunIdAndRequestId() {
        UUID storeId = UUID.randomUUID();
        OptimizationRunEntity run = runningRun(storeId);
        when(hostingOptimizationService.triggerManualRun(eq(storeId), eq(null), any()))
                .thenReturn(run);

        OptimizationTriggerRequest request = new OptimizationTriggerRequest();
        request.setStoreId(storeId.toString());

        var response = controller.triggerOptimization(request);

        assertThat(response.getStatusCode().value()).isEqualTo(202);
        ApiResponse<Object> body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.isSuccess()).isTrue();
        assertThat(body.getData()).isInstanceOf(OptimizationTriggerVo.class);
        OptimizationTriggerVo vo = (OptimizationTriggerVo) body.getData();
        assertThat(vo.getRunId()).isEqualTo(run.getId().toString());
        assertThat(vo.getStatus()).isEqualTo("running");
        assertThat(vo.getRequestId()).isNotBlank();
        verify(orgIsolationGuard).resolveStoreInCallerOrg(storeId);
    }

    @Test
    void triggerWithCampaignResolvesCampaignInCallerOrg() {
        UUID storeId = UUID.randomUUID();
        UUID campaignId = UUID.randomUUID();
        CampaignEntity campaign = CampaignEntity.builder().id(campaignId).storeId(storeId).build();
        when(orgIsolationGuard.resolveCampaignInCallerOrg(campaignId)).thenReturn(campaign);
        when(hostingOptimizationService.triggerManualRun(eq(storeId), eq(campaignId), any()))
                .thenReturn(runningRun(storeId));

        OptimizationTriggerRequest request = new OptimizationTriggerRequest();
        request.setStoreId(storeId.toString());
        request.setCampaignId(campaignId.toString());

        var response = controller.triggerOptimization(request);

        assertThat(response.getStatusCode().value()).isEqualTo(202);
        verify(orgIsolationGuard).resolveStoreInCallerOrg(storeId);
        verify(orgIsolationGuard).resolveCampaignInCallerOrg(campaignId);
    }

    @Test
    void triggerWithCampaignNotInStoreReturns400() {
        UUID storeId = UUID.randomUUID();
        UUID campaignId = UUID.randomUUID();
        CampaignEntity campaign = CampaignEntity.builder()
                .id(campaignId).storeId(UUID.randomUUID()).build(); // different store
        when(orgIsolationGuard.resolveCampaignInCallerOrg(campaignId)).thenReturn(campaign);

        OptimizationTriggerRequest request = new OptimizationTriggerRequest();
        request.setStoreId(storeId.toString());
        request.setCampaignId(campaignId.toString());

        var response = controller.triggerOptimization(request);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        HostingErrorVo error = (HostingErrorVo) response.getBody().getData();
        assertThat(error.getCode()).isEqualTo("HOSTING_INVALID_PARAMETER");
        assertThat(error.getRequestId()).isNotBlank();
        verify(hostingOptimizationService, never()).triggerManualRun(any(), any(), any());
    }

    @Test
    void triggerWithInvalidStoreIdReturns400WithRequestId() {
        OptimizationTriggerRequest request = new OptimizationTriggerRequest();
        request.setStoreId("not-a-uuid");

        var response = controller.triggerOptimization(request);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        ApiResponse<Object> body = response.getBody();
        assertThat(body.isSuccess()).isFalse();
        assertThat(body.getError().getCode()).isEqualTo("HOSTING_INVALID_PARAMETER");
        HostingErrorVo error = (HostingErrorVo) body.getData();
        assertThat(error.getRequestId()).isNotBlank();
        verify(orgIsolationGuard, never()).resolveStoreInCallerOrg(any());
    }

    @Test
    void triggerWithMissingBodyReturns400() {
        var response = controller.triggerOptimization(null);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        HostingErrorVo error = (HostingErrorVo) response.getBody().getData();
        assertThat(error.getCode()).isEqualTo("HOSTING_INVALID_PARAMETER");
        assertThat(error.getRequestId()).isNotBlank();
    }

    @Test
    void triggerRateLimitedReturns429WithStructuredError() {
        UUID storeId = UUID.randomUUID();
        when(hostingOptimizationService.triggerManualRun(eq(storeId), eq(null), any()))
                .thenThrow(new BusinessException(429, HostingOptimizationService.CODE_RATE_LIMITED,
                        "too soon"));

        OptimizationTriggerRequest request = new OptimizationTriggerRequest();
        request.setStoreId(storeId.toString());

        var response = controller.triggerOptimization(request);

        assertThat(response.getStatusCode().value()).isEqualTo(429);
        ApiResponse<Object> body = response.getBody();
        assertThat(body.isSuccess()).isFalse();
        HostingErrorVo error = (HostingErrorVo) body.getData();
        assertThat(error.getCode()).isEqualTo(HostingOptimizationService.CODE_RATE_LIMITED);
        assertThat(error.getRequestId()).isNotBlank();
    }

    @Test
    void triggerCrossOrgStoreReturns403Mapped() {
        UUID storeId = UUID.randomUUID();
        when(orgIsolationGuard.resolveStoreInCallerOrg(storeId))
                .thenThrow(new BusinessException(403, "FORBIDDEN", "denied"));

        OptimizationTriggerRequest request = new OptimizationTriggerRequest();
        request.setStoreId(storeId.toString());

        var response = controller.triggerOptimization(request);

        assertThat(response.getStatusCode().value()).isEqualTo(403);
        HostingErrorVo error = (HostingErrorVo) response.getBody().getData();
        assertThat(error.getCode()).isEqualTo("HOSTING_FORBIDDEN");
        assertThat(error.getRequestId()).isNotBlank();
        verify(hostingOptimizationService, never()).triggerManualRun(any(), any(), any());
    }

    // ── GET /optimization-runs/{runId} ───────────────────────────────────────────

    @Test
    void getRunReturnsStatusAndPerCampaignResults() {
        UUID runId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();
        UUID campaignId = UUID.randomUUID();
        OptimizationRunEntity run = OptimizationRunEntity.builder()
                .id(runId).storeId(storeId).triggerType("manual").status("completed")
                .phase("V1").campaignsProcessed(1).campaignsSkipped(0).operationsCreated(2)
                .perCampaignResults("{\"" + campaignId + "\":{\"operationsCreated\":2}}")
                .skipReasons("{}")
                .startedAt(LocalDateTime.now()).completedAt(LocalDateTime.now())
                .build();
        when(orgIsolationGuard.resolveRunInCallerOrg(runId)).thenReturn(run);

        var response = controller.getOptimizationRun(runId.toString());

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        OptimizationRunDetailVo vo = (OptimizationRunDetailVo) response.getBody().getData();
        assertThat(vo.getRunId()).isEqualTo(runId.toString());
        assertThat(vo.getStatus()).isEqualTo("completed");
        assertThat(vo.getOperationsCreated()).isEqualTo(2);
        assertThat(vo.getRequestId()).isNotBlank();
        assertThat(vo.getPerCampaignResults()).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> perCampaign = (Map<String, Object>) vo.getPerCampaignResults();
        assertThat(perCampaign).containsKey(campaignId.toString());
    }

    @Test
    void getRunUnknownReturns404Mapped() {
        UUID runId = UUID.randomUUID();
        when(orgIsolationGuard.resolveRunInCallerOrg(runId))
                .thenThrow(new BusinessException(404, "NOT_FOUND", "Resource not found"));

        var response = controller.getOptimizationRun(runId.toString());

        assertThat(response.getStatusCode().value()).isEqualTo(404);
        HostingErrorVo error = (HostingErrorVo) response.getBody().getData();
        assertThat(error.getCode()).isEqualTo("HOSTING_RESOURCE_NOT_FOUND");
        assertThat(error.getRequestId()).isNotBlank();
    }

    @Test
    void getRunWithInvalidIdReturns400() {
        var response = controller.getOptimizationRun("not-a-uuid");

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        HostingErrorVo error = (HostingErrorVo) response.getBody().getData();
        assertThat(error.getCode()).isEqualTo("HOSTING_INVALID_PARAMETER");
        assertThat(error.getRequestId()).isNotBlank();
        verify(orgIsolationGuard, never()).resolveRunInCallerOrg(any());
    }
}
