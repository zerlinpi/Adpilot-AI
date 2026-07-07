package com.adpilot.modules.advertising.service.impl;

import com.adpilot.common.security.DataScopeService;
import com.adpilot.modules.advertising.dto.ProductAdCampaignRequest;
import com.adpilot.modules.advertising.entity.CampaignProductLinkEntity;
import com.adpilot.modules.advertising.hosting.ExecutionMode;
import com.adpilot.modules.advertising.hosting.HostingConfigService;
import com.adpilot.modules.advertising.mapper.CampaignProductLinkMapper;
import com.adpilot.modules.advertising.mapper.SafetyBoundaryMapper;
import com.adpilot.modules.advertising.operation.OperationService;
import com.adpilot.modules.advertising.service.CampaignService;
import com.adpilot.modules.advertising.support.SafetyBoundaryValidator;
import com.adpilot.modules.advertising.vo.CampaignVo;
import com.adpilot.modules.advertising.vo.ProductAdCampaignResultVo;
import com.adpilot.modules.store.entity.StoreEntity;
import com.adpilot.modules.store.mapper.StoreMapper;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Property-based test for the per-product AI ad creation orchestration served by
 * {@link ProductAdCampaignServiceImpl}.
 *
 * <p>Feature: multistore-ai-ads-operations, Property 3: 合法提交产生一致的编排结果
 *
 * <p>Validates: Requirements 1.5, 1.10.
 *
 * <p>Property 3: For any legal per-product ad creation request, after a
 * successful submit the system MUST create a keyword ad campaign, establish a
 * {@link CampaignProductLinkEntity} pointing at the request's product
 * (ASIN / local product id), and the returned {@link ProductAdCampaignResultVo}
 * MUST carry a {@code campaignId} matching the created campaign, a
 * {@code productId}/{@code parentAsin} matching the linked product, and an
 * {@code executionMode} equal to the mode resolved by Property 2 (the canonical
 * {@code observe_only} fallback for null/blank/unrecognised inputs and the
 * matching enum value otherwise).
 *
 * <p>This is the orchestration slice completed in task 3.1. The store-scope and
 * platform-family isolation (task 3.2) is satisfied here by mocking the target
 * store as an {@code amazon} store and running without a security context (so
 * the data-scope guard is a no-op); the focus of this property is the
 * <em>consistency</em> of the orchestrated records and the returned result, not
 * the isolation behaviour (covered by Property 7). The bid/budget bound ordering
 * reuses the production {@link SafetyBoundaryValidator}, so a real
 * (dependency-free) instance is wired here rather than a mock; every other
 * collaborator is a Mockito mock per the {@code TableViewIsolationPropertyTest}
 * pattern.
 */
class ProductAdCampaignOrchestrationConsistencyPropertyTest {

    /** Real validator: a pure, dependency-free component used for bound ordering. */
    private final SafetyBoundaryValidator safetyBoundaryValidator = new SafetyBoundaryValidator();

    /**
     * Feature: multistore-ai-ads-operations, Property 3: 合法提交产生一致的编排结果
     *
     * <p>Validates: Requirements 1.5, 1.10.
     *
     * <p>A legal submission creates exactly one keyword campaign and one product
     * link pointing at the request's product, and returns a result whose
     * campaign id, product identifiers and execution mode are all consistent with
     * the records that were created.
     */
    @Property(tries = 200)
    void legalSubmitProducesConsistentCampaignLinkAndResult(
            @ForAll("legalRequests") ProductAdCampaignRequest request) {

        // Fresh collaborators per try so captured arguments and verify() counts
        // reflect exactly one orchestrated submission.
        CampaignService campaignService = mock(CampaignService.class);
        CampaignProductLinkMapper campaignProductLinkMapper = mock(CampaignProductLinkMapper.class);
        HostingConfigService hostingConfigService = mock(HostingConfigService.class);
        SafetyBoundaryMapper safetyBoundaryMapper = mock(SafetyBoundaryMapper.class);
        OperationService operationService = mock(OperationService.class);
        DataScopeService dataScopeService = mock(DataScopeService.class);
        StoreMapper storeMapper = mock(StoreMapper.class);

        // The campaign id the reused CampaignService hands back after creation.
        String createdCampaignId = UUID.randomUUID().toString();
        when(campaignService.createCampaign(any(), any()))
                .thenReturn(CampaignVo.builder()
                        .id(createdCampaignId)
                        .storeId(request.getStoreId())
                        .name("Product Ad")
                        .build());

        // The target store is an in-scope amazon store, so isolation passes and the
        // orchestration proceeds (isolation itself is exercised by Property 7).
        UUID storeUuid = UUID.fromString(request.getStoreId());
        when(storeMapper.selectById(storeUuid))
                .thenReturn(StoreEntity.builder()
                        .id(storeUuid)
                        .name("Amazon Store")
                        .platformFamily("amazon")
                        .build());

        ProductAdCampaignServiceImpl service = new ProductAdCampaignServiceImpl(
                safetyBoundaryValidator,
                campaignService,
                campaignProductLinkMapper,
                hostingConfigService,
                safetyBoundaryMapper,
                operationService,
                dataScopeService,
                storeMapper);

        ProductAdCampaignResultVo result =
                service.createProductAd(request, UUID.randomUUID().toString());

        // A keyword ad campaign was created through the reused CampaignService (Req 1.5).
        verify(campaignService).createCampaign(any(), any());

        // A CampaignProductLink pointing at the request's product was established (Req 1.5).
        ArgumentCaptor<CampaignProductLinkEntity> linkCaptor =
                ArgumentCaptor.forClass(CampaignProductLinkEntity.class);
        verify(campaignProductLinkMapper).insert(linkCaptor.capture());
        CampaignProductLinkEntity link = linkCaptor.getValue();

        // The link points back at the created campaign and the target store.
        assertThat(link.getCampaignId()).isEqualTo(UUID.fromString(createdCampaignId));
        assertThat(link.getStoreId()).isEqualTo(storeUuid);
        // The link points at the request's product (by ASIN and/or local product id).
        assertThat(link.getParentAsin()).isEqualTo(request.getParentAsin());
        assertThat(link.getProductId())
                .isEqualTo(parseProductId(request.getProductId()));

        // The returned result is consistent with the created records (Req 1.10).
        assertThat(result).isNotNull();
        assertThat(result.getCampaignId()).isEqualTo(createdCampaignId);
        assertThat(result.getProductId()).isEqualTo(request.getProductId());
        assertThat(result.getParentAsin()).isEqualTo(request.getParentAsin());

        // executionMode equals the mode resolved per Property 2.
        assertThat(result.getExecutionMode())
                .isEqualTo(expectedExecutionMode(request.getExecutionMode()));
    }

    // --- helpers -----------------------------------------------------------

    /** Mirror of the service's product-id parsing (blank/non-UUID → null). */
    private static UUID parseProductId(String productId) {
        if (productId == null || productId.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(productId);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Property 2's canonical resolution: the matching enum value for a recognised
     * input, otherwise the {@code observe_only} default.
     */
    private static String expectedExecutionMode(String raw) {
        ExecutionMode parsed = ExecutionMode.parse(raw);
        return parsed != null ? parsed.value() : ExecutionMode.DEFAULT.value();
    }

    // --- generators --------------------------------------------------------

    /** How the request points at its product: by ASIN, by local id, or both. */
    private enum ProductLinkKind {
        ASIN_ONLY,
        PRODUCT_ID_ONLY,
        BOTH
    }

    /**
     * Generate fully-legal requests: a parseable store id, at least one product
     * reference, a positive budget, correctly-ordered positive bid/budget bounds,
     * a randomly-toggled hosting flag with an optional positive target ACoS, and
     * an execution-mode input spanning recognised, blank, null and unrecognised
     * values so the resolved-mode invariant is exercised across the input space.
     */
    @Provide
    Arbitrary<ProductAdCampaignRequest> legalRequests() {
        return Combinators.combine(
                productLinks(),
                positive(),               // budget
                boundPair(),              // bid bounds {min, max}
                boundPair(),              // budget bounds {min, max}
                executionModeInputs(),
                Arbitraries.of(true, false),   // hostingEnabled
                positive())               // candidate target ACoS
                .as((product, budget, bidBounds, budgetBounds, execMode, hosting, acos) -> {
                    ProductAdCampaignRequest r = new ProductAdCampaignRequest();
                    r.setStoreId(UUID.randomUUID().toString());
                    r.setProductId(product[0]);
                    r.setParentAsin(product[1]);
                    r.setBudget(budget);
                    r.setBidMin(bidBounds[0]);
                    r.setBidMax(bidBounds[1]);
                    r.setBudgetMin(budgetBounds[0]);
                    r.setBudgetMax(budgetBounds[1]);
                    r.setExecutionMode(execMode);
                    r.setHostingEnabled(hosting);
                    // Only attach a target ACoS sometimes; when hosting + acos are
                    // present the orchestration also records the hosting goal.
                    r.setTargetAcos(hosting ? acos : null);
                    return r;
                });
    }

    /** A product reference that is always linkable (ASIN, local id, or both present). */
    @Provide
    Arbitrary<String[]> productLinks() {
        Arbitrary<String> asin = Arbitraries.strings()
                .withCharRange('A', 'Z').numeric()
                .ofMinLength(10).ofMaxLength(10)
                .map(s -> "B" + s.substring(1));
        Arbitrary<String> productId = Arbitraries.randomValue(r -> UUID.randomUUID().toString());
        return Combinators.combine(Arbitraries.of(ProductLinkKind.values()), asin, productId)
                .as((kind, a, p) -> switch (kind) {
                    case ASIN_ONLY -> new String[]{null, a};
                    case PRODUCT_ID_ONLY -> new String[]{p, null};
                    case BOTH -> new String[]{p, a};
                });
    }

    /** Execution-mode inputs spanning recognised enum values, null, blank and noise. */
    @Provide
    Arbitrary<String> executionModeInputs() {
        return Arbitraries.of(
                "observe_only",
                "recommend_only",
                "approval_required",
                "auto_execute",
                "AUTO_EXECUTE",     // case-insensitive recognised
                "  observe_only  ", // padded recognised
                null,
                "",
                "   ",
                "not_a_mode",
                "bogus");
    }

    /** A correctly-ordered pair of strictly-positive bounds {lower, upper}. */
    @Provide
    Arbitrary<BigDecimal[]> boundPair() {
        return Combinators.combine(positive(), positive())
                .as((a, b) -> new BigDecimal[]{a.min(b), a.max(b).add(BigDecimal.ONE)});
    }

    /** Strictly positive amounts within a realistic monetary range. */
    private Arbitrary<BigDecimal> positive() {
        return Arbitraries.bigDecimals()
                .between(new BigDecimal("0.01"), new BigDecimal("100000"))
                .ofScale(2);
    }
}
