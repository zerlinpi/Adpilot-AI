package com.adpilot.modules.advertising.service.impl;

import com.adpilot.common.exception.BusinessException;
import com.adpilot.common.security.CurrentUser;
import com.adpilot.common.security.DataScopeService;
import com.adpilot.common.utils.SecurityUtils;
import com.adpilot.modules.advertising.dto.InventoryUpdateRequest;
import com.adpilot.modules.advertising.dto.ProductAdCampaignRequest;
import com.adpilot.modules.advertising.hosting.HostingConfigService;
import com.adpilot.modules.advertising.mapper.CampaignProductLinkMapper;
import com.adpilot.modules.advertising.mapper.SafetyBoundaryMapper;
import com.adpilot.modules.advertising.operation.OperationService;
import com.adpilot.modules.advertising.platform.WriteCapabilityService;
import com.adpilot.modules.advertising.service.CampaignService;
import com.adpilot.modules.advertising.support.BoundaryValidationResult;
import com.adpilot.modules.advertising.support.SafetyBoundaryValidator;
import com.adpilot.modules.apisync.connector.PlatformWriteConnector;
import com.adpilot.modules.apisync.mapper.PlatformConnectionMapper;
import com.adpilot.modules.store.entity.StoreEntity;
import com.adpilot.modules.store.mapper.StoreMapper;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;
import org.mockito.Mockito;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Property-based test for write-side Store_Group_Scope and Platform_Family
 * isolation across the per-product ad creation and independent-site write paths.
 *
 * <p>Feature: multistore-ai-ads-operations, Property 7: 写入操作的店铺范围与平台族隔离
 *
 * <p>Validates: Requirements 1.8, 4.6, 6.5.
 *
 * <p>For any write operation (per-product keyword ad creation, independent-site
 * inventory / fulfillment write-back, or any cross-family operation), if the
 * target store is outside the account's {@code Store_Group_Scope} OR the store's
 * {@code platform_family} differs from the account's declared family, the system
 * must reject with HTTP 403 and persist nothing.
 *
 * <p>The two refusal causes are exercised independently against both write
 * services:
 * <ul>
 *   <li><b>out-of-scope</b> — {@link DataScopeService#assertCanWrite} throws a 403
 *       for a store outside the caller's scope; and</li>
 *   <li><b>cross-family</b> — the target store's {@code platform_family} differs
 *       from the family the endpoint is confined to ({@code amazon} for the
 *       product-ad path, {@code independent_site} for the independent-site path).</li>
 * </ul>
 * In every case the test asserts a 403 {@link BusinessException} is thrown and
 * that no persistence collaborator (campaign creation, product link / safety
 * boundary inserts, or Operation/Outbox creation) is ever invoked — i.e. nothing
 * is persisted.
 */
class WriteScopeFamilyIsolationPropertyTest {

    // ───────────────────────── Product-ad creation path (Req 1.8) ─────────────────────────

    /**
     * Feature: multistore-ai-ads-operations, Property 7: 写入操作的店铺范围与平台族隔离
     *
     * <p>Validates: Requirements 1.8, 4.6, 6.5.
     *
     * <p>A per-product ad creation whose target store is outside the caller's
     * Store_Group_Scope is rejected with HTTP 403 and persists nothing.
     */
    @Property(tries = 100)
    void productAdCreationOutOfScopeIsRejected403AndPersistsNothing(
            @ForAll("storeIds") UUID storeId,
            @ForAll @IntRange(min = 1, max = 100_000) int budget) {

        ProductAdFixture f = new ProductAdFixture();

        // Out-of-scope: the data-scope guard refuses this store with a 403.
        doThrow(new BusinessException(403, "DATA_SCOPE_DENIED", "store out of scope"))
                .when(f.dataScopeService).assertCanWrite(any(), any());

        ProductAdCampaignRequest request = validProductAdRequest(storeId, budget);

        try (var securityUtils = Mockito.mockStatic(SecurityUtils.class)) {
            securityUtils.when(SecurityUtils::isAuthenticated).thenReturn(true);
            securityUtils.when(SecurityUtils::getCurrentUser).thenReturn(actingUser());

            assertThatThrownBy(() -> f.service.createProductAd(request, UUID.randomUUID().toString()))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getStatus()).isEqualTo(403));
        }

        f.assertNothingPersisted();
    }

    /**
     * Feature: multistore-ai-ads-operations, Property 7: 写入操作的店铺范围与平台族隔离
     *
     * <p>Validates: Requirements 1.8, 4.6, 6.5.
     *
     * <p>A per-product ad creation against a store whose {@code platform_family}
     * is not {@code amazon} (a cross-family operation) is rejected with HTTP 403
     * and persists nothing — even when the store is within the caller's scope.
     */
    @Property(tries = 100)
    void productAdCreationCrossFamilyIsRejected403AndPersistsNothing(
            @ForAll("storeIds") UUID storeId,
            @ForAll @IntRange(min = 1, max = 100_000) int budget,
            @ForAll("nonAmazonFamilies") String storeFamily) {

        ProductAdFixture f = new ProductAdFixture();

        // In scope (assertCanWrite does nothing) but the store belongs to another family.
        when(f.storeMapper.selectById(storeId)).thenReturn(storeWithFamily(storeId, storeFamily));

        ProductAdCampaignRequest request = validProductAdRequest(storeId, budget);

        try (var securityUtils = Mockito.mockStatic(SecurityUtils.class)) {
            securityUtils.when(SecurityUtils::isAuthenticated).thenReturn(true);
            securityUtils.when(SecurityUtils::getCurrentUser).thenReturn(actingUser());

            assertThatThrownBy(() -> f.service.createProductAd(request, UUID.randomUUID().toString()))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getStatus()).isEqualTo(403));
        }

        f.assertNothingPersisted();
    }

    // ──────────────────── Independent-site inventory write path (Req 4.6) ────────────────────

    /**
     * Feature: multistore-ai-ads-operations, Property 7: 写入操作的店铺范围与平台族隔离
     *
     * <p>Validates: Requirements 1.8, 4.6, 6.5.
     *
     * <p>An independent-site inventory write whose target store is outside the
     * caller's Store_Group_Scope is rejected with HTTP 403 and creates no
     * Operation/Outbox.
     */
    @Property(tries = 100)
    void independentSiteWriteOutOfScopeIsRejected403AndPersistsNothing(
            @ForAll("storeIds") UUID storeId,
            @ForAll("storeIds") UUID productId,
            @ForAll @IntRange(min = 0, max = 100_000) int quantity) {

        IndependentSiteFixture f = new IndependentSiteFixture();

        doThrow(new BusinessException(403, "DATA_SCOPE_DENIED", "store out of scope"))
                .when(f.dataScopeService).assertCanWrite(any(), any());

        InventoryUpdateRequest request = inventoryRequest(storeId, quantity);

        try (var securityUtils = Mockito.mockStatic(SecurityUtils.class)) {
            securityUtils.when(SecurityUtils::isAuthenticated).thenReturn(true);
            securityUtils.when(SecurityUtils::getCurrentUser).thenReturn(actingUser());

            assertThatThrownBy(() ->
                    f.service.updateInventory(productId.toString(), request, UUID.randomUUID().toString()))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getStatus()).isEqualTo(403));
        }

        f.assertNoOperationCreated();
    }

    /**
     * Feature: multistore-ai-ads-operations, Property 7: 写入操作的店铺范围与平台族隔离
     *
     * <p>Validates: Requirements 1.8, 4.6, 6.5.
     *
     * <p>An independent-site inventory write against a store whose
     * {@code platform_family} is not {@code independent_site} (a cross-family
     * operation) is rejected with HTTP 403 and creates no Operation/Outbox.
     */
    @Property(tries = 100)
    void independentSiteWriteCrossFamilyIsRejected403AndPersistsNothing(
            @ForAll("storeIds") UUID storeId,
            @ForAll("storeIds") UUID productId,
            @ForAll @IntRange(min = 0, max = 100_000) int quantity,
            @ForAll("nonIndependentSiteFamilies") String storeFamily) {

        IndependentSiteFixture f = new IndependentSiteFixture();

        when(f.storeMapper.selectById(storeId)).thenReturn(storeWithFamily(storeId, storeFamily));

        InventoryUpdateRequest request = inventoryRequest(storeId, quantity);

        try (var securityUtils = Mockito.mockStatic(SecurityUtils.class)) {
            securityUtils.when(SecurityUtils::isAuthenticated).thenReturn(true);
            securityUtils.when(SecurityUtils::getCurrentUser).thenReturn(actingUser());

            assertThatThrownBy(() ->
                    f.service.updateInventory(productId.toString(), request, UUID.randomUUID().toString()))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getStatus()).isEqualTo(403));
        }

        f.assertNoOperationCreated();
    }

    // ───────────────────────────────── fixtures ─────────────────────────────────

    /** Wires {@link ProductAdCampaignServiceImpl} with fresh mocks and exposes a no-persistence assertion. */
    private static final class ProductAdFixture {
        final SafetyBoundaryValidator safetyBoundaryValidator = Mockito.mock(SafetyBoundaryValidator.class);
        final CampaignService campaignService = Mockito.mock(CampaignService.class);
        final CampaignProductLinkMapper campaignProductLinkMapper = Mockito.mock(CampaignProductLinkMapper.class);
        final HostingConfigService hostingConfigService = Mockito.mock(HostingConfigService.class);
        final SafetyBoundaryMapper safetyBoundaryMapper = Mockito.mock(SafetyBoundaryMapper.class);
        final OperationService operationService = Mockito.mock(OperationService.class);
        final DataScopeService dataScopeService = Mockito.mock(DataScopeService.class);
        final StoreMapper storeMapper = Mockito.mock(StoreMapper.class);

        final ProductAdCampaignServiceImpl service = new ProductAdCampaignServiceImpl(
                safetyBoundaryValidator, campaignService, campaignProductLinkMapper, hostingConfigService,
                safetyBoundaryMapper, operationService, dataScopeService, storeMapper);

        ProductAdFixture() {
            // Input validation runs before the scope/family guard; let the cross-field
            // boundary check pass so the guard is the only thing that can reject the request.
            when(safetyBoundaryValidator.validateCrossFieldConstraints(any()))
                    .thenReturn(BoundaryValidationResult.success());
        }

        void assertNothingPersisted() {
            verify(campaignService, never()).createCampaign(any(), any());
            verify(campaignProductLinkMapper, never()).insert(any());
            verify(safetyBoundaryMapper, never()).insert(any());
            verify(operationService, never()).createOperation(any());
        }
    }

    /** Wires {@link IndependentSiteWriteServiceImpl} with fresh mocks and exposes a no-Operation assertion. */
    private static final class IndependentSiteFixture {
        final OperationService operationService = Mockito.mock(OperationService.class);
        final WriteCapabilityService writeCapabilityService = Mockito.mock(WriteCapabilityService.class);
        final PlatformConnectionMapper platformConnectionMapper = Mockito.mock(PlatformConnectionMapper.class);
        final DataScopeService dataScopeService = Mockito.mock(DataScopeService.class);
        final StoreMapper storeMapper = Mockito.mock(StoreMapper.class);

        final IndependentSiteWriteServiceImpl service = new IndependentSiteWriteServiceImpl(
                operationService, writeCapabilityService, platformConnectionMapper, dataScopeService,
                storeMapper, List.<PlatformWriteConnector>of());

        void assertNoOperationCreated() {
            verify(operationService, never()).createOperation(any());
        }
    }

    // ───────────────────────────────── helpers ─────────────────────────────────

    private static CurrentUser actingUser() {
        return CurrentUser.builder()
                .userId(UUID.randomUUID().toString())
                .email("acting@example.com")
                .build();
    }

    private static StoreEntity storeWithFamily(UUID storeId, String platformFamily) {
        return StoreEntity.builder()
                .id(storeId)
                .name("store")
                .platformFamily(platformFamily)
                .build();
    }

    private static ProductAdCampaignRequest validProductAdRequest(UUID storeId, int budget) {
        // A request that passes input validation so the scope/family guard is the
        // only thing that can reject it (parentAsin present, positive budget, no bounds).
        ProductAdCampaignRequest request = new ProductAdCampaignRequest();
        request.setStoreId(storeId.toString());
        request.setParentAsin("B0" + String.format("%08d", Math.abs(budget % 100_000_000)));
        request.setBudget(BigDecimal.valueOf(budget));
        request.setHostingEnabled(false);
        return request;
    }

    private static InventoryUpdateRequest inventoryRequest(UUID storeId, int quantity) {
        InventoryUpdateRequest request = new InventoryUpdateRequest();
        request.setStoreId(storeId.toString());
        request.setQuantity(quantity);
        return request;
    }

    // ─────────────────────────────── generators ───────────────────────────────

    @Provide
    Arbitrary<UUID> storeIds() {
        return Arbitraries.randomValue(r -> UUID.randomUUID());
    }

    /** Platform families that are NOT {@code amazon} — every value is a cross-family target for the product-ad path. */
    @Provide
    Arbitrary<String> nonAmazonFamilies() {
        return Arbitraries.of("independent_site", "tiktok", "logistics", "finance");
    }

    /** Platform families that are NOT {@code independent_site} — cross-family targets for the independent-site path. */
    @Provide
    Arbitrary<String> nonIndependentSiteFamilies() {
        return Arbitraries.of("amazon", "tiktok", "logistics", "finance");
    }
}
