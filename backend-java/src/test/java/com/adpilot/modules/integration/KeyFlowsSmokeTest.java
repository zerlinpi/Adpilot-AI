package com.adpilot.modules.integration;

import com.adpilot.common.api.ApiResponse;
import com.adpilot.common.security.CurrentUser;
import com.adpilot.modules.advertising.independentsite.IndependentSiteWriteController;
import com.adpilot.modules.advertising.controller.ProductAdCampaignController;
import com.adpilot.modules.advertising.controller.ProductAdController;
import com.adpilot.modules.advertising.dto.FulfillmentRequest;
import com.adpilot.modules.advertising.dto.InventoryUpdateRequest;
import com.adpilot.modules.advertising.dto.ProductAdCampaignRequest;
import com.adpilot.modules.advertising.service.IndependentSiteWriteService;
import com.adpilot.modules.advertising.service.ProductAdCampaignService;
import com.adpilot.modules.advertising.service.ProductAdService;
import com.adpilot.modules.advertising.service.ProductAdSyncStatusService;
import com.adpilot.modules.advertising.vo.IndependentSiteConnectionStateVo;
import com.adpilot.modules.advertising.vo.OperationActionVo;
import com.adpilot.modules.advertising.vo.ProductAdCampaignResultVo;
import com.adpilot.modules.advertising.vo.ProductAdSyncStatusVo;
import com.adpilot.modules.advertising.vo.ProductCampaignVo;
import com.adpilot.modules.apisync.controller.IndependentSiteConnectionController;
import com.adpilot.modules.apisync.dto.IndependentSiteConnectRequest;
import com.adpilot.modules.apisync.service.IndependentSiteConnectionService;
import com.adpilot.modules.apisync.vo.PlatformConnectionVo;
import com.adpilot.modules.feishu.controller.FeishuController;
import com.adpilot.modules.feishu.service.FeishuService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.MethodOrderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Key-flow integration / smoke inspection (task 12.3, Req 8.1, 8.2).
 *
 * <p>Automated functional inspection of the platform's headline end-to-end flows. Each flow is
 * exercised through its real controller wiring with mocked collaborators (matching the repo's
 * controller-level smoke-test baseline, e.g. {@code HostingOperationControllerTest} and
 * {@code FeishuControllerTest}), and every item records a pass/fail outcome together with a
 * human-readable reason into a consolidated {@link #REPORT} (Req 8.1) so a failed flow surfaces
 * an actionable reason rather than a blank failure (Req 8.2).</p>
 *
 * <p>The covered flows are exactly those enumerated in Req 8.1:</p>
 * <ol>
 *   <li>Store connection (per platform family)</li>
 *   <li>Report sync &amp; product-ad data display</li>
 *   <li>Single-product ad creation submission ({@code POST /api/product-ads/campaign})</li>
 *   <li>Independent-site inventory / fulfillment write-back</li>
 *   <li>TikTok store connection</li>
 *   <li>Feishu notification sending</li>
 * </ol>
 *
 * <p>The consolidated pass/fail report is logged in {@link #logReport()} after all flows run.</p>
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class KeyFlowsSmokeTest {

    private static final Logger log = LoggerFactory.getLogger(KeyFlowsSmokeTest.class);

    /** One inspected key flow's outcome (Req 8.1: record pass/fail with a readable reason). */
    private record InspectionItem(String flow, boolean passed, String reason) {
    }

    /** Consolidated functional-inspection report, one row per inspected key flow (Req 8.1, 8.2). */
    private static final List<InspectionItem> REPORT = new ArrayList<>();

    // ── Flow 1 / 5: store + TikTok connection ───────────────────────────────
    private IndependentSiteConnectionService connectionService;
    private IndependentSiteConnectionController connectionController;

    // ── Flow 2: report sync & product-ad data display ───────────────────────
    private ProductAdService productAdService;
    private ProductAdSyncStatusService productAdSyncStatusService;
    private ProductAdController productAdController;

    // ── Flow 3: single-product ad creation ──────────────────────────────────
    private ProductAdCampaignService productAdCampaignService;
    private ProductAdCampaignController productAdCampaignController;

    // ── Flow 4: independent-site write-back ──────────────────────────────────
    private IndependentSiteWriteService independentSiteWriteService;
    private IndependentSiteWriteController independentSiteWriteController;

    // ── Flow 6: Feishu sending ───────────────────────────────────────────────
    private FeishuService feishuService;
    private FeishuController feishuController;

    private String userId;

    @BeforeEach
    void setUp() {
        connectionService = mock(IndependentSiteConnectionService.class);
        connectionController = new IndependentSiteConnectionController(connectionService);

        productAdService = mock(ProductAdService.class);
        productAdSyncStatusService = mock(ProductAdSyncStatusService.class);
        productAdController = new ProductAdController(productAdService, productAdSyncStatusService);

        productAdCampaignService = mock(ProductAdCampaignService.class);
        productAdCampaignController = new ProductAdCampaignController(productAdCampaignService);

        independentSiteWriteService = mock(IndependentSiteWriteService.class);
        independentSiteWriteController = new IndependentSiteWriteController(independentSiteWriteService);

        feishuService = mock(FeishuService.class);
        feishuController = new FeishuController(feishuService);

        // A representative authenticated operator so SecurityUtils resolves a real actor id.
        userId = UUID.randomUUID().toString();
        CurrentUser principal = CurrentUser.builder()
                .userId(userId)
                .email("operator@example.com")
                .orgId(UUID.randomUUID().toString())
                .name("Operator")
                .roles(Set.of("operations_manager"))
                .permissions(List.of("advertising:view", "advertising:manage",
                        "store:manage", "store:view", "product:manage", "order:manage", "feishu:manage"))
                .build();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    // ────────────────────────────────────────────────────────────────────────
    // Flow 1 — Store connection (per platform family)
    // ────────────────────────────────────────────────────────────────────────

    @Test
    @Order(1)
    @DisplayName("Flow 1: store connection (independent-site family) creates a connected store")
    void storeConnectionFlow() {
        inspect("店铺连接 / Store connection (independent_site)", () -> {
            String storeId = UUID.randomUUID().toString();
            PlatformConnectionVo connected = PlatformConnectionVo.builder()
                    .id(UUID.randomUUID().toString())
                    .storeId(storeId)
                    .platform("shopify")
                    .platformId("shopify")
                    .status("connected")
                    .message("Connected")
                    .build();
            when(connectionService.connectStore(any(IndependentSiteConnectRequest.class), eq(userId)))
                    .thenReturn(connected);

            IndependentSiteConnectRequest req = new IndependentSiteConnectRequest();
            req.setPlatform("shopify");
            req.setStoreName("My Shopify Store");

            ApiResponse<PlatformConnectionVo> resp =
                    connectionController.connectStore(req, "independent_site");

            verify(connectionService).connectStore(any(IndependentSiteConnectRequest.class), eq(userId));
            assertThat(resp.isSuccess()).isTrue();
            assertThat(resp.getData()).isNotNull();
            assertThat(resp.getData().getPlatform()).isEqualTo("shopify");
            assertThat(resp.getData().getStatus()).isEqualTo("connected");
            return "shopify store connected under independent_site block; status="
                    + resp.getData().getStatus();
        });
    }

    // ────────────────────────────────────────────────────────────────────────
    // Flow 2 — Report sync & product-ad data display
    // ────────────────────────────────────────────────────────────────────────

    @Test
    @Order(2)
    @DisplayName("Flow 2: report sync status surfaces failures honestly and product-ad data is displayed")
    void reportSyncAndProductAdDataFlow() {
        String storeId = UUID.randomUUID().toString();

        inspect("报表同步状态 / Report sync observability", () -> {
            ProductAdSyncStatusVo ok = new ProductAdSyncStatusVo(
                    storeId, "SP_CAMPAIGN", "completed", LocalDateTime.now().minusHours(20), null);
            ProductAdSyncStatusVo failed = new ProductAdSyncStatusVo(
                    storeId, "SP_KEYWORD", "failed", LocalDateTime.now().minusDays(2),
                    "Amazon Ads report request rejected: throttled (429)");
            when(productAdSyncStatusService.getSyncStatus(storeId)).thenReturn(List.of(ok, failed));

            ApiResponse<List<ProductAdSyncStatusVo>> resp = productAdController.getSyncStatus(storeId);

            verify(productAdSyncStatusService).getSyncStatus(storeId);
            assertThat(resp.isSuccess()).isTrue();
            assertThat(resp.getData()).hasSize(2);
            // A failed run must never be mapped to success and must carry a readable reason (Req 2.6).
            ProductAdSyncStatusVo failedRow = resp.getData().stream()
                    .filter(v -> "SP_KEYWORD".equals(v.reportType())).findFirst().orElseThrow();
            assertThat(failedRow.reportStatus()).isEqualTo("failed");
            assertThat(failedRow.lastError()).isNotBlank();
            return "sync status returned for 2 report types; failed run reported honestly with reason=\""
                    + failedRow.lastError() + "\"";
        });

        inspect("产品广告数据展示 / Product-ad data display", () -> {
            ProductCampaignVo campaign = ProductCampaignVo.builder()
                    .campaignId(UUID.randomUUID().toString())
                    .campaignName("SP - Parent ASIN")
                    .parentAsin("B0PARENT01")
                    .status("enabled")
                    .hostingEnabled(true)
                    .spend(123.45)
                    .clicks(420)
                    .orders(37)
                    .sales(980.0)
                    .acos(12.6)
                    .dataStatus("preliminary")
                    .build();
            when(productAdService.listProductCampaigns(storeId, "B0PARENT01", null))
                    .thenReturn(List.of(campaign));

            ApiResponse<List<ProductCampaignVo>> resp =
                    productAdController.listProductCampaigns(storeId, "B0PARENT01", null);

            verify(productAdService).listProductCampaigns(storeId, "B0PARENT01", null);
            assertThat(resp.isSuccess()).isTrue();
            assertThat(resp.getData()).hasSize(1);
            ProductCampaignVo row = resp.getData().get(0);
            // The five headline metrics (Req 2.2) and data status (Req 2.3) are carried through.
            assertThat(row.getSpend()).isEqualTo(123.45);
            assertThat(row.getClicks()).isEqualTo(420);
            assertThat(row.getOrders()).isEqualTo(37);
            assertThat(row.getSales()).isEqualTo(980.0);
            assertThat(row.getAcos()).isEqualTo(12.6);
            assertThat(row.getDataStatus()).isEqualTo("preliminary");
            return "1 product-linked campaign displayed with spend/clicks/orders/sales/ACoS and dataStatus="
                    + row.getDataStatus();
        });
    }

    // ────────────────────────────────────────────────────────────────────────
    // Flow 3 — Single-product ad creation submission
    // ────────────────────────────────────────────────────────────────────────

    @Test
    @Order(3)
    @DisplayName("Flow 3: POST /api/product-ads/campaign submits a per-product keyword ad")
    void singleProductAdCreationFlow() {
        inspect("单产品广告创建 / Single-product ad creation (POST /api/product-ads/campaign)", () -> {
            String storeId = UUID.randomUUID().toString();
            String campaignId = UUID.randomUUID().toString();
            ProductAdCampaignResultVo result = ProductAdCampaignResultVo.builder()
                    .campaignId(campaignId)
                    .parentAsin("B0PARENT01")
                    .executionMode("observe_only")
                    .build();
            when(productAdCampaignService.createProductAd(any(ProductAdCampaignRequest.class), eq(userId)))
                    .thenReturn(result);

            ProductAdCampaignRequest req = new ProductAdCampaignRequest();
            req.setStoreId(storeId);
            req.setParentAsin("B0PARENT01");
            req.setBudget(new java.math.BigDecimal("20.00"));
            req.setBudgetType("daily");
            req.setPersonality("balanced");
            req.setHostingEnabled(true);

            ApiResponse<ProductAdCampaignResultVo> resp =
                    productAdCampaignController.createProductAdCampaign(req);

            verify(productAdCampaignService).createProductAd(any(ProductAdCampaignRequest.class), eq(userId));
            assertThat(resp.isSuccess()).isTrue();
            assertThat(resp.getData()).isNotNull();
            assertThat(resp.getData().getCampaignId()).isEqualTo(campaignId);
            assertThat(resp.getData().getParentAsin()).isEqualTo("B0PARENT01");
            // Execution mode defaults to observe_only when not selected (Req 1.3).
            assertThat(resp.getData().getExecutionMode()).isEqualTo("observe_only");
            return "campaign " + campaignId + " created for ASIN B0PARENT01; executionMode="
                    + resp.getData().getExecutionMode();
        });
    }

    // ────────────────────────────────────────────────────────────────────────
    // Flow 4 — Independent-site inventory / fulfillment write-back
    // ────────────────────────────────────────────────────────────────────────

    @Test
    @Order(4)
    @DisplayName("Flow 4: independent-site inventory & fulfillment write-back enqueue Operations")
    void independentSiteWriteBackFlow() {
        String storeId = UUID.randomUUID().toString();

        inspect("独立站库存回写 / Independent-site inventory write-back", () -> {
            String productId = UUID.randomUUID().toString();
            OperationActionVo enqueued = OperationActionVo.builder()
                    .operationId(UUID.randomUUID().toString())
                    .action("inventory_update")
                    .syncState("pending")
                    .build();
            when(independentSiteWriteService.updateInventory(eq(productId), any(InventoryUpdateRequest.class), eq(userId)))
                    .thenReturn(enqueued);

            InventoryUpdateRequest req = new InventoryUpdateRequest();
            req.setStoreId(storeId);
            req.setQuantity(25);

            ApiResponse<OperationActionVo> resp =
                    independentSiteWriteController.updateInventory(productId, req);

            verify(independentSiteWriteService).updateInventory(eq(productId), any(InventoryUpdateRequest.class), eq(userId));
            assertThat(resp.isSuccess()).isTrue();
            assertThat(resp.getData()).isNotNull();
            assertThat(resp.getData().getSyncState()).isEqualTo("pending");
            return "inventory update enqueued as Operation " + resp.getData().getOperationId()
                    + " (syncState=" + resp.getData().getSyncState() + ", async write-back)";
        });

        inspect("独立站发货回写 / Independent-site fulfillment write-back", () -> {
            String orderId = UUID.randomUUID().toString();
            OperationActionVo enqueued = OperationActionVo.builder()
                    .operationId(UUID.randomUUID().toString())
                    .action("fulfillment")
                    .syncState("pending")
                    .build();
            when(independentSiteWriteService.markFulfillment(eq(orderId), any(FulfillmentRequest.class), eq(userId)))
                    .thenReturn(enqueued);

            FulfillmentRequest req = new FulfillmentRequest();
            req.setStoreId(storeId);
            req.setTrackingNumber("1Z999AA10123456784");
            req.setCarrier("UPS");

            ApiResponse<OperationActionVo> resp =
                    independentSiteWriteController.markFulfillment(orderId, req);

            verify(independentSiteWriteService).markFulfillment(eq(orderId), any(FulfillmentRequest.class), eq(userId));
            assertThat(resp.isSuccess()).isTrue();
            assertThat(resp.getData().getSyncState()).isEqualTo("pending");
            return "fulfillment mark enqueued as Operation " + resp.getData().getOperationId()
                    + " (syncState=" + resp.getData().getSyncState() + ", async write-back)";
        });

        inspect("独立站连接状态 / Independent-site connection-state visibility", () -> {
            IndependentSiteConnectionStateVo state = IndependentSiteConnectionStateVo.builder()
                    .storeId(storeId)
                    .platform("shopify")
                    .connectionState("connected")
                    .inventoryWriteSupported(true)
                    .fulfillmentWriteSupported(true)
                    .lastSuccessAt(LocalDateTime.now().minusHours(1))
                    .build();
            when(independentSiteWriteService.getConnectionStates(storeId)).thenReturn(List.of(state));

            ApiResponse<List<IndependentSiteConnectionStateVo>> resp =
                    independentSiteWriteController.connectionState(storeId);

            verify(independentSiteWriteService).getConnectionStates(storeId);
            assertThat(resp.isSuccess()).isTrue();
            assertThat(resp.getData()).hasSize(1);
            return "connection-state exposed: platform=" + resp.getData().get(0).getPlatform()
                    + ", state=" + resp.getData().get(0).getConnectionState();
        });
    }

    // ────────────────────────────────────────────────────────────────────────
    // Flow 5 — TikTok store connection
    // ────────────────────────────────────────────────────────────────────────

    @Test
    @Order(5)
    @DisplayName("Flow 5: TikTok store connection is scoped to the tiktok block")
    void tiktokConnectionFlow() {
        inspect("TikTok 连接 / TikTok store connection", () -> {
            String storeId = UUID.randomUUID().toString();
            PlatformConnectionVo connected = PlatformConnectionVo.builder()
                    .id(UUID.randomUUID().toString())
                    .storeId(storeId)
                    .platform("tiktok_shop")
                    .platformId("tiktok_shop")
                    .status("connected")
                    .message("Connected")
                    .build();
            when(connectionService.connectStore(any(IndependentSiteConnectRequest.class), eq(userId)))
                    .thenReturn(connected);

            IndependentSiteConnectRequest req = new IndependentSiteConnectRequest();
            req.setPlatform("tiktok_shop");
            req.setStoreName("My TikTok Shop");

            ApiResponse<PlatformConnectionVo> resp = connectionController.connectStore(req, "tiktok");

            verify(connectionService).connectStore(any(IndependentSiteConnectRequest.class), eq(userId));
            assertThat(resp.isSuccess()).isTrue();
            assertThat(resp.getData().getPlatform()).isEqualTo("tiktok_shop");
            assertThat(resp.getData().getStatus()).isEqualTo("connected");
            // The connection entry carries the ?platform=tiktok block-family scope into the request (Req 5.3, 6.2).
            assertThat(req.getBlockFamily()).isEqualTo("tiktok");
            return "tiktok_shop store connected under tiktok block; blockFamily="
                    + req.getBlockFamily() + ", status=" + resp.getData().getStatus();
        });
    }

    // ────────────────────────────────────────────────────────────────────────
    // Flow 6 — Feishu notification sending
    // ────────────────────────────────────────────────────────────────────────

    @Test
    @Order(6)
    @DisplayName("Flow 6: Feishu test-message dispatches through the bound integration")
    void feishuSendingFlow() {
        inspect("飞书发送 / Feishu notification sending", () -> {
            String integrationId = UUID.randomUUID().toString();
            doNothing().when(feishuService).sendTestMessage(integrationId, null);

            ApiResponse<Void> resp = feishuController.sendTestMessage(integrationId, null);

            // Dispatches a connectivity test through the bound integration's credentials (Req 7.5).
            verify(feishuService).sendTestMessage(integrationId, null);
            assertThat(resp.isSuccess()).isTrue();
            return "test message dispatched through bound integration " + integrationId;
        });
    }

    // ────────────────────────────────────────────────────────────────────────
    // Inspection recording + consolidated report (Req 8.1, 8.2)
    // ────────────────────────────────────────────────────────────────────────

    /**
     * Runs one inspected flow, recording its pass/fail outcome and a readable reason into
     * {@link #REPORT} (Req 8.1). On failure the readable reason captures the failure cause
     * (Req 8.2) before the assertion error is rethrown so the build still fails the flow.
     */
    private void inspect(String flow, Callable<String> check) {
        try {
            String reason = check.call();
            REPORT.add(new InspectionItem(flow, true, reason));
        } catch (Throwable t) {
            REPORT.add(new InspectionItem(flow, false, String.valueOf(t.getMessage())));
            if (t instanceof RuntimeException re) {
                throw re;
            }
            if (t instanceof Error err) {
                throw err;
            }
            throw new RuntimeException(t);
        }
    }

    @AfterAll
    static void logReport() {
        StringBuilder sb = new StringBuilder();
        sb.append("\n==== Key-Flow Functional Inspection Report (Req 8.1, 8.2) ====\n");
        for (InspectionItem item : REPORT) {
            sb.append(item.passed() ? "[PASS] " : "[FAIL] ")
                    .append(item.flow())
                    .append(" — ")
                    .append(item.reason())
                    .append('\n');
        }
        long passed = REPORT.stream().filter(InspectionItem::passed).count();
        sb.append(String.format("---- %d/%d flow checks passed ----%n", passed, REPORT.size()));
        log.info(sb.toString());
    }
}
