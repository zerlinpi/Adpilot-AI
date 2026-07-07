package com.adpilot.modules.advertising.service.impl;

import com.adpilot.common.security.DataScopeService;
import com.adpilot.common.utils.SecurityUtils;
import com.adpilot.modules.advertising.dto.ProductAdCampaignRequest;
import com.adpilot.modules.advertising.hosting.HostingConfigService;
import com.adpilot.modules.advertising.mapper.CampaignProductLinkMapper;
import com.adpilot.modules.advertising.mapper.SafetyBoundaryMapper;
import com.adpilot.modules.advertising.operation.CreateOperationCommand;
import com.adpilot.modules.advertising.operation.OperationService;
import com.adpilot.modules.advertising.service.CampaignService;
import com.adpilot.modules.advertising.support.SafetyBoundaryValidator;
import com.adpilot.modules.advertising.vo.CampaignVo;
import com.adpilot.modules.store.entity.StoreEntity;
import com.adpilot.modules.store.mapper.StoreMapper;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.mockito.Mockito;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Property-based test for the all-or-nothing atomicity of the per-product AI ad
 * creation orchestration served by {@link ProductAdCampaignServiceImpl}.
 *
 * <p>Feature: multistore-ai-ads-operations, Property 5: 提交原子性（全有或全无）
 *
 * <p>Validates: Requirements 1.9.
 *
 * <p>Property 5: For any 单产品广告创建请求, if any of the four persistence steps —
 * campaign create, product link, hosting config, safety boundary — fails, the
 * system retains none of the partial writes: the submission is all-or-nothing.
 *
 * <p>{@link ProductAdCampaignServiceImpl#createProductAd} is annotated
 * {@code @Transactional}, so the way it guarantees atomicity is by letting a
 * failure in any step propagate out of the method — the surrounding Spring
 * transaction then rolls back every write made before the failure. This test
 * therefore verifies the two observable facets of that contract, for whichever
 * of the four steps is made to fail:
 * <ol>
 *   <li>the thrown exception is <em>propagated</em> out of {@code createProductAd}
 *       (it is never swallowed), which is precisely what triggers the
 *       {@code @Transactional} rollback; and</li>
 *   <li>no later persistence step runs after the failing one, and in particular
 *       the external-write enqueue ({@link OperationService#createOperation}) — the
 *       step that would publish an irreversible Operation/Outbox entry — is never
 *       reached, so no partial submission escapes the aborted transaction.</li>
 * </ol>
 *
 * <p>The four steps run in this order inside the single transaction:
 * {@code campaignService.createCampaign} → {@code campaignProductLinkMapper.insert}
 * → {@code hostingConfigService.saveCampaignConfig} → {@code safetyBoundaryMapper.insert},
 * followed by {@code operationService.createOperation}. The bound-ordering check
 * reuses the production {@link SafetyBoundaryValidator}, so a real
 * (dependency-free) instance is wired here rather than a mock.
 */
class ProductAdCampaignAtomicityPropertyTest {

    /** The four persistence steps whose failure must abort the whole submission. */
    private enum FailingStep {
        CAMPAIGN_CREATE,
        PRODUCT_LINK,
        HOSTING_CONFIG,
        SAFETY_BOUNDARY
    }

    /**
     * Feature: multistore-ai-ads-operations, Property 5: 提交原子性（全有或全无）
     *
     * <p>Validates: Requirements 1.9.
     *
     * <p>For any single failing persistence step, {@code createProductAd} propagates
     * the failure (so {@code @Transactional} rolls back) and never reaches any later
     * step — crucially never enqueuing the external-write Operation/Outbox entry — so
     * no partial write survives.
     */
    @Property(tries = 200)
    void anyFailingPersistenceStepAbortsTheWholeSubmission(@ForAll("failingSteps") FailingStep failingStep) {
        SafetyBoundaryValidator validator = new SafetyBoundaryValidator();
        CampaignService campaignService = mock(CampaignService.class);
        CampaignProductLinkMapper campaignProductLinkMapper = mock(CampaignProductLinkMapper.class);
        HostingConfigService hostingConfigService = mock(HostingConfigService.class);
        SafetyBoundaryMapper safetyBoundaryMapper = mock(SafetyBoundaryMapper.class);
        OperationService operationService = mock(OperationService.class);
        DataScopeService dataScopeService = mock(DataScopeService.class);
        StoreMapper storeMapper = mock(StoreMapper.class);

        // The target store passes the platform-family check (amazon) so the request
        // reaches the orchestration steps where the injected failure occurs.
        when(storeMapper.selectById(any())).thenReturn(amazonStore());

        RuntimeException boom = new RuntimeException("injected failure at " + failingStep);

        // Campaign create succeeds unless it is the step chosen to fail.
        if (failingStep == FailingStep.CAMPAIGN_CREATE) {
            when(campaignService.createCampaign(any(), any())).thenThrow(boom);
        } else {
            when(campaignService.createCampaign(any(), any())).thenReturn(
                    CampaignVo.builder().id(UUID.randomUUID().toString()).name("Product Ad").build());
        }
        switch (failingStep) {
            case PRODUCT_LINK -> when(campaignProductLinkMapper.insert(any())).thenThrow(boom);
            case HOSTING_CONFIG -> when(hostingConfigService.saveCampaignConfig(any(), any(), any(), any()))
                    .thenThrow(boom);
            case SAFETY_BOUNDARY -> when(safetyBoundaryMapper.insert(any())).thenThrow(boom);
            default -> { /* CAMPAIGN_CREATE already stubbed above */ }
        }

        ProductAdCampaignServiceImpl service = new ProductAdCampaignServiceImpl(
                validator, campaignService, campaignProductLinkMapper, hostingConfigService,
                safetyBoundaryMapper, operationService, dataScopeService, storeMapper);

        Throwable thrown;
        try (var securityUtils = Mockito.mockStatic(SecurityUtils.class)) {
            // No interactive user: skip the data-scope assertion and exercise the
            // orchestration directly (the family check still runs against the store).
            securityUtils.when(SecurityUtils::isAuthenticated).thenReturn(false);
            thrown = catchThrowable(() -> service.createProductAd(legalHostedRequest(), "user-1"));
        }

        // (1) The failure is propagated, not swallowed — this is what triggers the
        //     @Transactional rollback that makes the submission all-or-nothing.
        assertThat(thrown)
                .as("failure in step %s must propagate so the transaction rolls back", failingStep)
                .isSameAs(boom);

        // (2) The irreversible external-write enqueue is never reached when any
        //     persistence step fails — no partial submission escapes the rollback.
        verify(operationService, never()).createOperation(any(CreateOperationCommand.class));

        // (2b) No persistence step that comes AFTER the failing one is ever executed.
        switch (failingStep) {
            case CAMPAIGN_CREATE -> {
                verify(campaignProductLinkMapper, never()).insert(any());
                verify(hostingConfigService, never()).saveCampaignConfig(any(), any(), any(), any());
                verify(safetyBoundaryMapper, never()).insert(any());
            }
            case PRODUCT_LINK -> {
                verify(hostingConfigService, never()).saveCampaignConfig(any(), any(), any(), any());
                verify(safetyBoundaryMapper, never()).insert(any());
            }
            case HOSTING_CONFIG -> verify(safetyBoundaryMapper, never()).insert(any());
            case SAFETY_BOUNDARY -> { /* nothing persists after the safety boundary except the enqueue, already verified */ }
        }
    }

    /**
     * Sanity anchor: with NO injected failure, the legal hosted request runs all
     * four persistence steps AND enqueues the external write. This ensures the
     * property above fails on a genuine abort rather than passing vacuously
     * (e.g. if the orchestration never reached these steps at all).
     */
    @Property(tries = 50)
    void happyPathReachesAllStepsAndEnqueuesTheExternalWrite(@ForAll("anySeed") long seed) {
        SafetyBoundaryValidator validator = new SafetyBoundaryValidator();
        CampaignService campaignService = mock(CampaignService.class);
        CampaignProductLinkMapper campaignProductLinkMapper = mock(CampaignProductLinkMapper.class);
        HostingConfigService hostingConfigService = mock(HostingConfigService.class);
        SafetyBoundaryMapper safetyBoundaryMapper = mock(SafetyBoundaryMapper.class);
        OperationService operationService = mock(OperationService.class);
        DataScopeService dataScopeService = mock(DataScopeService.class);
        StoreMapper storeMapper = mock(StoreMapper.class);

        when(storeMapper.selectById(any())).thenReturn(amazonStore());
        when(campaignService.createCampaign(any(), any())).thenReturn(
                CampaignVo.builder().id(UUID.randomUUID().toString()).name("Product Ad").build());

        ProductAdCampaignServiceImpl service = new ProductAdCampaignServiceImpl(
                validator, campaignService, campaignProductLinkMapper, hostingConfigService,
                safetyBoundaryMapper, operationService, dataScopeService, storeMapper);

        try (var securityUtils = Mockito.mockStatic(SecurityUtils.class)) {
            securityUtils.when(SecurityUtils::isAuthenticated).thenReturn(false);
            service.createProductAd(legalHostedRequest(), "user-1");
        }

        verify(campaignService).createCampaign(any(), any());
        verify(campaignProductLinkMapper).insert(any());
        verify(hostingConfigService).saveCampaignConfig(any(), any(), any(), any());
        verify(safetyBoundaryMapper, Mockito.atLeastOnce()).insert(any());
        verify(operationService).createOperation(any(CreateOperationCommand.class));
    }

    // --- fixtures ----------------------------------------------------------

    /** A store whose platform_family is amazon so the cross-family guard passes. */
    private static StoreEntity amazonStore() {
        return StoreEntity.builder()
                .id(UUID.randomUUID())
                .platformFamily("amazon")
                .build();
    }

    /**
     * A fully-legal, hosting-enabled request with all four bid/budget bounds set
     * (so the safety-boundary inserts run) and {@code targetAcos} left null (so the
     * optional {@code assignHosting} branch is skipped, keeping the flow to the four
     * named persistence steps). Uses real UUIDs so the store/family resolution path
     * is exercised exactly as in production.
     */
    private static ProductAdCampaignRequest legalHostedRequest() {
        ProductAdCampaignRequest r = new ProductAdCampaignRequest();
        r.setStoreId(UUID.randomUUID().toString());
        r.setProductId(UUID.randomUUID().toString());
        r.setBudget(new BigDecimal("25.00"));
        r.setHostingEnabled(true);
        r.setBidMin(new BigDecimal("1.00"));
        r.setBidMax(new BigDecimal("3.00"));
        r.setBudgetMin(new BigDecimal("10.00"));
        r.setBudgetMax(new BigDecimal("50.00"));
        return r;
    }

    // --- generators --------------------------------------------------------

    @Provide
    Arbitrary<FailingStep> failingSteps() {
        return Arbitraries.of(FailingStep.values());
    }

    @Provide
    Arbitrary<Long> anySeed() {
        return Arbitraries.longs();
    }
}
