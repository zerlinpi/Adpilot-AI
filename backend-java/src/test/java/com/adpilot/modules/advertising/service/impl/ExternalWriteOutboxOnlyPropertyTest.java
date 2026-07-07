package com.adpilot.modules.advertising.service.impl;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import com.adpilot.common.security.DataScopeService;
import com.adpilot.common.utils.SecurityUtils;
import com.adpilot.modules.advertising.dto.FulfillmentRequest;
import com.adpilot.modules.advertising.dto.InventoryUpdateRequest;
import com.adpilot.modules.advertising.dto.ProductAdCampaignRequest;
import com.adpilot.modules.advertising.hosting.HostingConfigService;
import com.adpilot.modules.advertising.mapper.CampaignProductLinkMapper;
import com.adpilot.modules.advertising.mapper.SafetyBoundaryMapper;
import com.adpilot.modules.advertising.operation.CreateOperationCommand;
import com.adpilot.modules.advertising.operation.OperationResult;
import com.adpilot.modules.advertising.operation.OperationService;
import com.adpilot.modules.advertising.operation.SyncState;
import com.adpilot.modules.advertising.platform.WriteCapabilityService;
import com.adpilot.modules.advertising.service.CampaignService;
import com.adpilot.modules.advertising.support.SafetyBoundaryValidator;
import com.adpilot.modules.advertising.vo.CampaignVo;
import com.adpilot.modules.apisync.connector.PlatformWriteConnector;
import com.adpilot.modules.apisync.entity.PlatformConnectionEntity;
import com.adpilot.modules.apisync.mapper.PlatformConnectionMapper;
import com.adpilot.modules.store.entity.StoreEntity;
import com.adpilot.modules.store.mapper.StoreMapper;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Label;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;
import org.mockito.MockedStatic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Property-based test for the external-write-is-Outbox-only invariant shared by
 * the per-product ad creation path ({@link ProductAdCampaignServiceImpl}) and the
 * independent-site write-back path ({@link IndependentSiteWriteServiceImpl}).
 *
 * <p>Feature: multistore-ai-ads-operations, Property 6: 外部写入只经 Operation+Outbox 异步提交
 * (external platform writes are committed only via Operation + Outbox, asynchronously).
 *
 * <p>Validates: Requirements 1.7, 4.2.
 *
 * <p>Property 6 (transcribed from the design's Correctness Properties section):
 * <em>For any valid submission that triggers an external platform write (single-product ad
 * creation OR independent-site inventory / fulfillment write-back), the system creates the
 * corresponding Operation + Outbox entry and never calls the {@link PlatformWriteConnector}
 * on the request-handling thread — i.e. the count of synchronous external-platform API calls
 * is exactly zero.</em>
 *
 * <p>The two orchestration services are driven against mocked collaborators so each scenario
 * is a valid, write-triggering submission. A single mocked {@link PlatformWriteConnector}
 * stands in for the live platform: after the service call returns on the (test) request thread,
 * the test asserts that none of the connector's write/read methods
 * ({@code submit}, {@code submitBatch}, {@code queryStatus}, {@code requestCancel},
 * {@code verify}) were invoked, while {@link OperationService#createOperation} was — proving the
 * write was enqueued as an Operation + Outbox entry rather than submitted inline. Both
 * representative paths (Req 1.7 product-ad creation, Req 4.2 independent-site inventory and
 * fulfillment) are covered by the same property.
 */
@Tag("pbt")
@Label("Feature: multistore-ai-ads-operations, Property 6: 外部写入只经 Operation+Outbox 异步提交")
class ExternalWriteOutboxOnlyPropertyTest {

    private static final int MIN_ITERATIONS = 100;

    /** The kind of write-triggering submission under test. */
    enum WritePath { PRODUCT_AD, INVENTORY, FULFILLMENT }

    /**
     * Feature: multistore-ai-ads-operations, Property 6: 外部写入只经 Operation+Outbox 异步提交.
     *
     * <p>Validates: Requirements 1.7, 4.2.
     *
     * <p>For any valid write-triggering submission on either path, the request thread enqueues
     * the write via {@link OperationService#createOperation} (Operation + Outbox) and performs
     * <em>zero</em> synchronous calls to the {@link PlatformWriteConnector}.
     */
    @Property(tries = MIN_ITERATIONS)
    @Label("Property 6: a write-triggering submission creates an Operation and never calls the connector inline")
    void externalWriteGoesThroughOperationOutboxAndNeverCallsConnectorInline(
            @ForAll("scenarios") Scenario scenario) {

        // A single connector standing in for the live platform; it must never be touched
        // on the request thread (Req 1.7, 4.2). platform() is read once at construction only.
        PlatformWriteConnector connector = mock(PlatformWriteConnector.class);
        when(connector.platform()).thenReturn("shopify");

        OperationService operationService = mock(OperationService.class);
        when(operationService.createOperation(any(CreateOperationCommand.class)))
                .thenAnswer(inv -> OperationResult.builder()
                        .operationId(UUID.randomUUID())
                        .syncState(SyncState.PENDING)
                        .build());

        DataScopeService dataScopeService = mock(DataScopeService.class);
        StoreMapper storeMapper = mock(StoreMapper.class);

        try (MockedStatic<SecurityUtils> security = org.mockito.Mockito.mockStatic(SecurityUtils.class)) {
            // Keep the data-scope user out of the picture: the family check (storeMapper) still
            // runs and gates the write; the scenario stores are of the path's expected family.
            security.when(SecurityUtils::isAuthenticated).thenReturn(false);

            if (scenario.path == WritePath.PRODUCT_AD) {
                invokeProductAd(scenario, operationService, dataScopeService, storeMapper);
            } else {
                invokeIndependentSite(scenario, connector, operationService, dataScopeService, storeMapper);
            }
        }

        // The write was enqueued as an Operation + Outbox entry (Req 1.7 / 4.2 positive half).
        verify(operationService, atLeastOnce()).createOperation(any(CreateOperationCommand.class));

        // Zero synchronous external-platform calls on the request thread (Req 1.7 / 4.2 core).
        verify(connector, never()).submit(any(), any());
        verify(connector, never()).submitBatch(any(), any());
        verify(connector, never()).queryStatus(any(), any());
        verify(connector, never()).requestCancel(any(), any());
        verify(connector, never()).verify(any(), any(), any());
    }

    // -----------------------------------------------------------------------------------------
    // Path drivers
    // -----------------------------------------------------------------------------------------

    /** Drive the per-product ad creation path (Req 1.7). */
    private void invokeProductAd(Scenario scenario, OperationService operationService,
                                 DataScopeService dataScopeService, StoreMapper storeMapper) {
        // The target store must resolve to the amazon family for the write to proceed.
        when(storeMapper.selectById(scenario.storeId))
                .thenReturn(StoreEntity.builder().id(scenario.storeId).platformFamily("amazon").build());

        CampaignService campaignService = mock(CampaignService.class);
        when(campaignService.createCampaign(any(), any()))
                .thenAnswer(inv -> CampaignVo.builder()
                        .id(UUID.randomUUID().toString())
                        .name("Product Ad")
                        .build());

        ProductAdCampaignServiceImpl service = new ProductAdCampaignServiceImpl(
                new SafetyBoundaryValidator(),
                campaignService,
                mock(CampaignProductLinkMapper.class),
                mock(HostingConfigService.class),
                mock(SafetyBoundaryMapper.class),
                operationService,
                dataScopeService,
                storeMapper);

        ProductAdCampaignRequest request = new ProductAdCampaignRequest();
        request.setStoreId(scenario.storeId.toString());
        request.setParentAsin(scenario.parentAsin);
        request.setBudget(scenario.budget);

        service.createProductAd(request, UUID.randomUUID().toString());
    }

    /** Drive the independent-site inventory / fulfillment write-back path (Req 4.2). */
    private void invokeIndependentSite(Scenario scenario, PlatformWriteConnector connector,
                                       OperationService operationService,
                                       DataScopeService dataScopeService, StoreMapper storeMapper) {
        // The target store must resolve to the independent_site family for the write to proceed.
        when(storeMapper.selectById(scenario.storeId))
                .thenReturn(StoreEntity.builder().id(scenario.storeId).platformFamily("independent_site").build());

        PlatformConnectionMapper platformConnectionMapper = mock(PlatformConnectionMapper.class);
        // A state-connected Shopify connection so the store passes the connection gate (Req 4.1/4.3).
        when(platformConnectionMapper.selectList(any())).thenReturn(List.of(
                PlatformConnectionEntity.builder()
                        .id(UUID.randomUUID())
                        .storeId(scenario.storeId)
                        .platform("shopify")
                        .status("connected")
                        .build()));

        WriteCapabilityService writeCapabilityService = mock(WriteCapabilityService.class);
        when(writeCapabilityService.isWriteCapable(any())).thenReturn(true);

        IndependentSiteWriteServiceImpl service = new IndependentSiteWriteServiceImpl(
                operationService,
                writeCapabilityService,
                platformConnectionMapper,
                dataScopeService,
                storeMapper,
                List.of(connector));

        String userId = UUID.randomUUID().toString();
        if (scenario.path == WritePath.INVENTORY) {
            InventoryUpdateRequest req = new InventoryUpdateRequest();
            req.setStoreId(scenario.storeId.toString());
            req.setQuantity(scenario.quantity);
            service.updateInventory(scenario.entityId.toString(), req, userId);
        } else {
            FulfillmentRequest req = new FulfillmentRequest();
            req.setStoreId(scenario.storeId.toString());
            req.setTrackingNumber(scenario.trackingNumber);
            service.markFulfillment(scenario.entityId.toString(), req, userId);
        }
    }

    // -----------------------------------------------------------------------------------------
    // Scenario + generators
    // -----------------------------------------------------------------------------------------

    /** A valid, write-triggering submission for one of the two external-write paths. */
    static final class Scenario {
        final WritePath path;
        final UUID storeId;
        final UUID entityId;       // product id (inventory) or order id (fulfillment)
        final String parentAsin;   // product-ad path
        final BigDecimal budget;   // product-ad path
        final int quantity;        // inventory path
        final String trackingNumber; // fulfillment path

        Scenario(WritePath path, UUID storeId, UUID entityId, String parentAsin,
                 BigDecimal budget, int quantity, String trackingNumber) {
            this.path = path;
            this.storeId = storeId;
            this.entityId = entityId;
            this.parentAsin = parentAsin;
            this.budget = budget;
            this.quantity = quantity;
            this.trackingNumber = trackingNumber;
        }
    }

    @Provide
    Arbitrary<Scenario> scenarios() {
        Arbitrary<WritePath> paths = Arbitraries.of(WritePath.values());
        Arbitrary<BigDecimal> budgets = Arbitraries.bigDecimals()
                .between(new BigDecimal("0.01"), new BigDecimal("100000"))
                .ofScale(2);
        Arbitrary<Integer> quantities = Arbitraries.integers().between(0, 100000);
        Arbitrary<String> tracking = Arbitraries.strings().alpha().numeric().ofMinLength(0).ofMaxLength(20);
        Arbitrary<String> asins = Arbitraries.strings()
                .withCharRange('A', 'Z').numeric().ofMinLength(10).ofMaxLength(10);

        return Combinators.combine(paths, budgets, quantities, tracking, asins)
                .as((path, budget, quantity, track, asin) -> new Scenario(
                        path, UUID.randomUUID(), UUID.randomUUID(), asin, budget, quantity, track));
    }
}
