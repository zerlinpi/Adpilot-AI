package com.adpilot.modules.advertising.controller;

import com.adpilot.common.api.ApiResponse;
import com.adpilot.common.exception.BusinessException;
import com.adpilot.common.security.CurrentUser;
import com.adpilot.modules.advertising.dto.BrandWordCreateRequest;
import com.adpilot.modules.advertising.dto.HostingConfigRequest;
import com.adpilot.modules.advertising.dto.OperationRejectRequest;
import com.adpilot.modules.advertising.dto.OptimizationTriggerRequest;
import com.adpilot.modules.advertising.dto.PhaseChangeRequest;
import com.adpilot.modules.advertising.entity.CampaignEntity;
import com.adpilot.modules.advertising.entity.GoalEntity;
import com.adpilot.modules.advertising.entity.OperationEntity;
import com.adpilot.modules.advertising.hosting.AiDecisionEntity;
import com.adpilot.modules.advertising.hosting.AiDecisionMapper;
import com.adpilot.modules.advertising.hosting.BrandWordService;
import com.adpilot.modules.advertising.hosting.CanaryRolloutService;
import com.adpilot.modules.advertising.hosting.HostingApprovalService;
import com.adpilot.modules.advertising.hosting.HostingConfigService;
import com.adpilot.modules.advertising.hosting.HostingDashboardService;
import com.adpilot.modules.advertising.hosting.HostingOptimizationService;
import com.adpilot.modules.advertising.hosting.HostingOrgIsolationGuard;
import com.adpilot.modules.advertising.hosting.OptimizationRunEntity;
import com.adpilot.modules.advertising.hosting.OptimizationRunMapper;
import com.adpilot.modules.advertising.hosting.PhaseConfigurationService;
import com.adpilot.modules.advertising.hosting.RollbackResult;
import com.adpilot.modules.advertising.hosting.RollbackService;
import com.adpilot.modules.advertising.mapper.CampaignMapper;
import com.adpilot.modules.advertising.mapper.GoalMapper;
import com.adpilot.modules.advertising.mapper.OperationMapper;
import com.adpilot.modules.advertising.operation.OperationResult;
import com.adpilot.modules.advertising.operation.SyncState;
import com.adpilot.modules.advertising.service.HostingPhase;
import com.adpilot.modules.store.entity.StoreEntity;
import com.adpilot.modules.store.mapper.StoreMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Cross-organization negative integration suite for every hosting endpoint (task 24.5).
 *
 * <p><b>Validates: Requirements 39.4.</b>
 *
 * <p>This suite wires the four hosting controllers ({@link HostingConfigController},
 * {@link HostingDashboardController}, {@link HostingOptimizationController},
 * {@link HostingOperationController}) over the <em>real</em> {@link HostingOrgIsolationGuard}
 * (constructed with mocked persistence) and mocked downstream services. It models two
 * organizations:
 * <ul>
 *   <li><b>org A</b> — the caller's organization, owning {@code storeA} plus a campaign, goal,
 *       operation, optimization-run, and AI decision;</li>
 *   <li><b>org B</b> — a foreign organization, owning {@code storeB} plus the same kinds of
 *       resources.</li>
 * </ul>
 *
 * <p>Every test authenticates as a user in org A. The {@code crossOrgIsAlwaysDenied} group then
 * drives <em>every</em> hosting endpoint with an org-B resource id and asserts the request is
 * denied (HTTP 403, or 404 with no detail) and that <em>no downstream service is ever invoked</em>,
 * so no org-B data can leak across the boundary (Req 39.4). The {@code sameOrgIsAccessible} group
 * proves the very same endpoints succeed for the caller's own org-A resources, confirming the
 * denial is genuinely cross-org isolation and not a blanket failure.</p>
 */
@DisplayName("Hosting cross-organization negative integration suite (Req 39.4)")
class HostingCrossOrgIsolationIntegrationTest {

    // ── Two organizations ────────────────────────────────────────────────────────
    private static final UUID ORG_A = UUID.randomUUID();
    private static final UUID ORG_B = UUID.randomUUID();

    // ── Org A resources (caller's own) ───────────────────────────────────────────
    private final UUID storeA = UUID.randomUUID();
    private final UUID campaignA = UUID.randomUUID();
    private final UUID goalA = UUID.randomUUID();
    private final UUID operationA = UUID.randomUUID();
    private final UUID runA = UUID.randomUUID();
    private final UUID decisionA = UUID.randomUUID();
    private final UUID brandWordA = UUID.randomUUID();

    // ── Org B resources (foreign) ────────────────────────────────────────────────
    private final UUID storeB = UUID.randomUUID();
    private final UUID campaignB = UUID.randomUUID();
    private final UUID goalB = UUID.randomUUID();
    private final UUID operationB = UUID.randomUUID();
    private final UUID runB = UUID.randomUUID();
    private final UUID decisionB = UUID.randomUUID();
    private final UUID brandWordB = UUID.randomUUID();

    // ── Mocked persistence backing the real guard ────────────────────────────────
    private StoreMapper storeMapper;
    private CampaignMapper campaignMapper;
    private GoalMapper goalMapper;
    private OperationMapper operationMapper;
    private OptimizationRunMapper optimizationRunMapper;
    private AiDecisionMapper aiDecisionMapper;

    // ── Mocked downstream services ───────────────────────────────────────────────
    private HostingConfigService hostingConfigService;
    private BrandWordService brandWordService;
    private HostingDashboardService hostingDashboardService;
    private HostingOptimizationService hostingOptimizationService;
    private HostingApprovalService hostingApprovalService;
    private RollbackService rollbackService;
    private PhaseConfigurationService phaseConfigurationService;

    // ── Controllers under test ───────────────────────────────────────────────────
    private HostingConfigController configController;
    private HostingDashboardController dashboardController;
    private HostingOptimizationController optimizationController;
    private HostingOperationController operationController;

    @BeforeEach
    void setUp() {
        storeMapper = mock(StoreMapper.class);
        campaignMapper = mock(CampaignMapper.class);
        goalMapper = mock(GoalMapper.class);
        operationMapper = mock(OperationMapper.class);
        optimizationRunMapper = mock(OptimizationRunMapper.class);
        aiDecisionMapper = mock(AiDecisionMapper.class);

        HostingOrgIsolationGuard guard = new HostingOrgIsolationGuard(
                storeMapper, campaignMapper, goalMapper,
                operationMapper, optimizationRunMapper, aiDecisionMapper);

        hostingConfigService = mock(HostingConfigService.class);
        brandWordService = mock(BrandWordService.class);
        hostingDashboardService = mock(HostingDashboardService.class);
        hostingOptimizationService = mock(HostingOptimizationService.class);
        hostingApprovalService = mock(HostingApprovalService.class);
        rollbackService = mock(RollbackService.class);
        phaseConfigurationService = mock(PhaseConfigurationService.class);

        configController = new HostingConfigController(
                hostingConfigService, brandWordService, mock(CanaryRolloutService.class), guard);
        dashboardController = new HostingDashboardController(hostingDashboardService, guard);
        optimizationController = new HostingOptimizationController(
                hostingOptimizationService, guard, new com.fasterxml.jackson.databind.ObjectMapper());
        operationController = new HostingOperationController(
                hostingApprovalService, rollbackService, phaseConfigurationService, guard);

        // Persistence fixtures: each id resolves to a store in its owning org.
        lenient().when(storeMapper.selectById(storeA)).thenReturn(store(storeA, ORG_A));
        lenient().when(storeMapper.selectById(storeB)).thenReturn(store(storeB, ORG_B));

        lenient().when(campaignMapper.selectById(campaignA)).thenReturn(campaign(campaignA, storeA));
        lenient().when(campaignMapper.selectById(campaignB)).thenReturn(campaign(campaignB, storeB));

        lenient().when(goalMapper.selectById(goalA)).thenReturn(goal(goalA, storeA));
        lenient().when(goalMapper.selectById(goalB)).thenReturn(goal(goalB, storeB));

        lenient().when(operationMapper.selectById(operationA))
                .thenReturn(operation(operationA, storeA, "awaiting_approval"));
        lenient().when(operationMapper.selectById(operationB))
                .thenReturn(operation(operationB, storeB, "awaiting_approval"));

        lenient().when(optimizationRunMapper.selectById(runA)).thenReturn(run(runA, storeA));
        lenient().when(optimizationRunMapper.selectById(runB)).thenReturn(run(runB, storeB));

        lenient().when(aiDecisionMapper.selectById(decisionA)).thenReturn(decision(decisionA, storeA));
        lenient().when(aiDecisionMapper.selectById(decisionB)).thenReturn(decision(decisionB, storeB));

        authenticateAs(ORG_A);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // ══════════════════════════════════════════════════════════════════════════════
    // Cross-org: every endpoint denies an org-B resource and leaks nothing (Req 39.4)
    // ══════════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("org A caller is denied every org-B hosting resource")
    class CrossOrgIsAlwaysDenied {

        // ── HostingConfigController ──────────────────────────────────────────────

        @Test
        @DisplayName("GET /config/{storeId} — read store config")
        void readStoreConfigDenied() {
            assertForbidden(() -> configController.getStoreConfig(storeB.toString()), storeB);
            verifyNoInteractions(hostingConfigService);
        }

        @Test
        @DisplayName("PUT /config/{storeId} — write store config")
        void writeStoreConfigDenied() {
            assertForbidden(() -> configController.putStoreConfig(storeB.toString(), new HostingConfigRequest()), storeB);
            verifyNoInteractions(hostingConfigService);
        }

        @Test
        @DisplayName("GET /config/{storeId}/goals/{goalId} — read goal override")
        void readGoalConfigDenied() {
            assertForbidden(() -> configController.getGoalConfig(storeB.toString(), goalB.toString()), storeB);
            verifyNoInteractions(hostingConfigService);
        }

        @Test
        @DisplayName("GET /config/{storeId}/campaigns/{campaignId} — read campaign override")
        void readCampaignConfigDenied() {
            assertForbidden(() -> configController.getCampaignConfig(storeB.toString(), campaignB.toString()), storeB);
            verifyNoInteractions(hostingConfigService);
        }

        @Test
        @DisplayName("GET /brand-words/{storeId} — list brand words")
        void listBrandWordsDenied() {
            assertForbidden(() -> configController.listBrandWords(storeB.toString()), storeB);
            verifyNoInteractions(brandWordService);
        }

        @Test
        @DisplayName("POST /brand-words/{storeId} — add brand word")
        void addBrandWordDenied() {
            assertForbidden(() -> configController.addBrandWord(storeB.toString(),
                    BrandWordCreateRequest.builder().word("acme").matchType("exact").build()), storeB);
            verifyNoInteractions(brandWordService);
        }

        @Test
        @DisplayName("DELETE /brand-words/{storeId}/{wordId} — remove brand word")
        void deleteBrandWordDenied() {
            assertForbidden(() -> configController.deleteBrandWord(storeB.toString(), brandWordB.toString()), storeB);
            verifyNoInteractions(brandWordService);
        }

        // ── HostingDashboardController ───────────────────────────────────────────

        @Test
        @DisplayName("GET /dashboard/summary — summary cards")
        void dashboardSummaryDenied() {
            assertForbidden(() -> dashboardController.getSummary(storeB.toString()), storeB);
            verifyNoInteractions(hostingDashboardService);
        }

        @Test
        @DisplayName("GET /decisions — recent decisions list")
        void listDecisionsDenied() {
            assertForbidden(() -> dashboardController.listDecisions(storeB.toString(), 50), storeB);
            verifyNoInteractions(hostingDashboardService);
        }

        @Test
        @DisplayName("GET /decisions/{id} — decision explanation")
        void decisionDetailDenied() {
            assertForbidden(() -> dashboardController.getDecision(decisionB.toString()), decisionB);
            verifyNoInteractions(hostingDashboardService);
        }

        @Test
        @DisplayName("GET /analytics — historical analytics")
        void analyticsDenied() {
            assertForbidden(() -> dashboardController.getAnalytics(storeB.toString(), "7d"), storeB);
            verifyNoInteractions(hostingDashboardService);
        }

        // ── HostingOptimizationController (returns a ResponseEntity, never throws) ─

        @Test
        @DisplayName("POST /optimize/trigger — trigger optimization")
        void triggerOptimizationDenied() {
            OptimizationTriggerRequest request = new OptimizationTriggerRequest();
            request.setStoreId(storeB.toString());

            ResponseEntity<ApiResponse<Object>> response = optimizationController.triggerOptimization(request);

            assertThat(response.getStatusCode().value()).isEqualTo(403);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().isSuccess()).isFalse();
            assertThat(String.valueOf(response.getBody())).doesNotContain(storeB.toString());
            verifyNoInteractions(hostingOptimizationService);
        }

        @Test
        @DisplayName("GET /optimization-runs/{runId} — run detail")
        void optimizationRunDetailDenied() {
            ResponseEntity<ApiResponse<Object>> response = optimizationController.getOptimizationRun(runB.toString());

            assertThat(response.getStatusCode().value()).isEqualTo(403);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().isSuccess()).isFalse();
            assertThat(String.valueOf(response.getBody())).doesNotContain(runB.toString());
        }

        // ── HostingOperationController ───────────────────────────────────────────

        @Test
        @DisplayName("POST /operations/{id}/approve — approve operation")
        void approveDenied() {
            assertForbidden(() -> operationController.approve(operationB.toString()), operationB);
            verifyNoInteractions(hostingApprovalService);
        }

        @Test
        @DisplayName("POST /operations/{id}/reject — reject operation")
        void rejectDenied() {
            assertForbidden(() -> operationController.reject(operationB.toString(),
                    new OperationRejectRequest("nope")), operationB);
            verifyNoInteractions(hostingApprovalService);
        }

        @Test
        @DisplayName("POST /operations/{id}/rollback — rollback operation")
        void rollbackDenied() {
            assertForbidden(() -> operationController.rollback(operationB.toString(), false), operationB);
            verifyNoInteractions(rollbackService);
        }

        @Test
        @DisplayName("POST /phase — admin phase change")
        void changePhaseDenied() {
            assertForbidden(() -> operationController.changePhase(
                    new PhaseChangeRequest(storeB.toString(), "V2")), storeB);
            verifyNoInteractions(phaseConfigurationService);
        }
    }

    // ══════════════════════════════════════════════════════════════════════════════
    // Same-org contrast: the identical endpoints succeed for the caller's org-A data
    // ══════════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("org A caller can access its own org-A hosting resources")
    class SameOrgIsAccessible {

        @Test
        @DisplayName("GET /config/{storeId} succeeds for own store")
        void readStoreConfigAllowed() {
            ApiResponse<?> response = configController.getStoreConfig(storeA.toString());
            assertThat(response.isSuccess()).isTrue();
            verify(hostingConfigService).getConfig(storeA, "store", storeA);
        }

        @Test
        @DisplayName("PUT /config/{storeId} succeeds for own store")
        void writeStoreConfigAllowed() {
            ApiResponse<?> response = configController.putStoreConfig(storeA.toString(), new HostingConfigRequest());
            assertThat(response.isSuccess()).isTrue();
            verify(hostingConfigService).saveStoreConfig(any(), any(), any());
        }

        @Test
        @DisplayName("GET /config/{storeId}/goals/{goalId} succeeds for own goal")
        void readGoalConfigAllowed() {
            ApiResponse<?> response = configController.getGoalConfig(storeA.toString(), goalA.toString());
            assertThat(response.isSuccess()).isTrue();
            verify(hostingConfigService).getConfig(storeA, "goal", goalA);
        }

        @Test
        @DisplayName("GET /config/{storeId}/campaigns/{campaignId} succeeds for own campaign")
        void readCampaignConfigAllowed() {
            ApiResponse<?> response = configController.getCampaignConfig(storeA.toString(), campaignA.toString());
            assertThat(response.isSuccess()).isTrue();
            verify(hostingConfigService).getConfig(storeA, "campaign", campaignA);
        }

        @Test
        @DisplayName("GET /brand-words/{storeId} succeeds for own store")
        void listBrandWordsAllowed() {
            when(brandWordService.listBrandWords(storeA)).thenReturn(List.of());
            ApiResponse<?> response = configController.listBrandWords(storeA.toString());
            assertThat(response.isSuccess()).isTrue();
            verify(brandWordService).listBrandWords(storeA);
        }

        @Test
        @DisplayName("POST /brand-words/{storeId} succeeds for own store")
        void addBrandWordAllowed() {
            ApiResponse<?> response = configController.addBrandWord(storeA.toString(),
                    BrandWordCreateRequest.builder().word("acme").matchType("exact").build());
            assertThat(response.isSuccess()).isTrue();
            verify(brandWordService).addBrandWord(any(), any(), any());
        }

        @Test
        @DisplayName("DELETE /brand-words/{storeId}/{wordId} succeeds for own store")
        void deleteBrandWordAllowed() {
            ApiResponse<?> response = configController.deleteBrandWord(storeA.toString(), brandWordA.toString());
            assertThat(response.isSuccess()).isTrue();
            verify(brandWordService).deleteBrandWord(eq(storeA), eq(brandWordA), any());
        }

        @Test
        @DisplayName("GET /dashboard/summary succeeds for own store")
        void dashboardSummaryAllowed() {
            ApiResponse<?> response = dashboardController.getSummary(storeA.toString());
            assertThat(response.isSuccess()).isTrue();
            verify(hostingDashboardService).getSummary(storeA);
        }

        @Test
        @DisplayName("GET /decisions succeeds for own store")
        void listDecisionsAllowed() {
            when(hostingDashboardService.listDecisions(storeA, 50)).thenReturn(List.of());
            ApiResponse<?> response = dashboardController.listDecisions(storeA.toString(), 50);
            assertThat(response.isSuccess()).isTrue();
            verify(hostingDashboardService).listDecisions(storeA, 50);
        }

        @Test
        @DisplayName("GET /decisions/{id} succeeds for own decision")
        void decisionDetailAllowed() {
            ApiResponse<?> response = dashboardController.getDecision(decisionA.toString());
            assertThat(response.isSuccess()).isTrue();
            verify(hostingDashboardService).getDecisionDetail(any());
        }

        @Test
        @DisplayName("GET /analytics succeeds for own store")
        void analyticsAllowed() {
            ApiResponse<?> response = dashboardController.getAnalytics(storeA.toString(), "7d");
            assertThat(response.isSuccess()).isTrue();
            verify(hostingDashboardService).getAnalytics(storeA, "7d");
        }

        @Test
        @DisplayName("POST /optimize/trigger succeeds for own store (202 + run_id)")
        void triggerOptimizationAllowed() {
            when(hostingOptimizationService.triggerManualRun(any(), any(), any()))
                    .thenReturn(runWithStatus(runA, storeA, "running"));

            OptimizationTriggerRequest request = new OptimizationTriggerRequest();
            request.setStoreId(storeA.toString());

            ResponseEntity<ApiResponse<Object>> response = optimizationController.triggerOptimization(request);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().isSuccess()).isTrue();
            verify(hostingOptimizationService).triggerManualRun(eq(storeA), isNull(), any());
        }

        @Test
        @DisplayName("GET /optimization-runs/{runId} succeeds for own run")
        void optimizationRunDetailAllowed() {
            ResponseEntity<ApiResponse<Object>> response = optimizationController.getOptimizationRun(runA.toString());

            assertThat(response.getStatusCode().value()).isEqualTo(200);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().isSuccess()).isTrue();
        }

        @Test
        @DisplayName("POST /operations/{id}/approve succeeds for own operation")
        void approveAllowed() {
            ApiResponse<?> response = operationController.approve(operationA.toString());
            assertThat(response.isSuccess()).isTrue();
            verify(hostingApprovalService).approveOperation(eq(operationA), any());
        }

        @Test
        @DisplayName("POST /operations/{id}/reject succeeds for own operation")
        void rejectAllowed() {
            ApiResponse<?> response = operationController.reject(operationA.toString(),
                    new OperationRejectRequest("too risky"));
            assertThat(response.isSuccess()).isTrue();
            verify(hostingApprovalService).rejectOperation(eq(operationA), any(), eq("too risky"));
        }

        @Test
        @DisplayName("POST /operations/{id}/rollback succeeds for own operation")
        void rollbackAllowed() {
            when(rollbackService.rollback(operationA)).thenReturn(
                    RollbackResult.success(OperationResult.builder()
                            .operationId(UUID.randomUUID())
                            .syncState(SyncState.PENDING)
                            .build()));

            ApiResponse<?> response = operationController.rollback(operationA.toString(), false);
            assertThat(response.isSuccess()).isTrue();
            verify(rollbackService).rollback(operationA);
        }

        @Test
        @DisplayName("POST /phase succeeds for own store")
        void changePhaseAllowed() {
            when(phaseConfigurationService.getCurrentPhase(storeA))
                    .thenReturn(HostingPhase.V1)
                    .thenReturn(HostingPhase.V2);

            ApiResponse<?> response = operationController.changePhase(new PhaseChangeRequest(storeA.toString(), "V2"));
            assertThat(response.isSuccess()).isTrue();
            verify(phaseConfigurationService).transitionPhase(any(), any(), any());
        }
    }

    // ──────────────────────────────────────────────────────────────────────────────
    // Helpers
    // ──────────────────────────────────────────────────────────────────────────────

    /**
     * Assert that the given endpoint invocation fails closed for a cross-org id: it raises a
     * {@link BusinessException} with HTTP 403 (cross-org) or 404 (not found), and the error message
     * never echoes the requested id, so no cross-org resource detail leaks (Req 39.4).
     */
    private static void assertForbidden(Runnable invocation, UUID requestedId) {
        assertThatThrownBy(invocation::run)
                .isInstanceOfSatisfying(BusinessException.class, ex -> {
                    assertThat(ex.getStatus()).isIn(403, 404);
                    assertThat(String.valueOf(ex.getMessage())).doesNotContain(requestedId.toString());
                });
    }

    private static void authenticateAs(UUID orgId) {
        CurrentUser principal = CurrentUser.builder()
                .userId(UUID.randomUUID().toString())
                .orgId(orgId != null ? orgId.toString() : null)
                .build();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, List.of()));
    }

    private static StoreEntity store(UUID id, UUID orgId) {
        StoreEntity s = new StoreEntity();
        s.setId(id);
        s.setOrgId(orgId);
        return s;
    }

    private static CampaignEntity campaign(UUID id, UUID storeId) {
        return CampaignEntity.builder().id(id).storeId(storeId).build();
    }

    private static GoalEntity goal(UUID id, UUID storeId) {
        return GoalEntity.builder().id(id).storeId(storeId).build();
    }

    private static OperationEntity operation(UUID id, UUID storeId, String syncState) {
        return OperationEntity.builder().id(id).storeId(storeId).syncState(syncState).build();
    }

    private static OptimizationRunEntity run(UUID id, UUID storeId) {
        return OptimizationRunEntity.builder().id(id).storeId(storeId).build();
    }

    private static OptimizationRunEntity runWithStatus(UUID id, UUID storeId, String status) {
        return OptimizationRunEntity.builder().id(id).storeId(storeId).status(status).build();
    }

    private static AiDecisionEntity decision(UUID id, UUID storeId) {
        return AiDecisionEntity.builder().id(id).storeId(storeId).build();
    }
}
