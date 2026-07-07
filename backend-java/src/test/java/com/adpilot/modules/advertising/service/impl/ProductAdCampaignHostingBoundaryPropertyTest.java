package com.adpilot.modules.advertising.service.impl;

import com.adpilot.common.security.DataScopeService;
import com.adpilot.modules.advertising.dto.HostingConfigRequest;
import com.adpilot.modules.advertising.dto.ProductAdCampaignRequest;
import com.adpilot.modules.advertising.entity.SafetyBoundaryEntity;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Property-based test for the conditional hosting-config / safety-boundary
 * persistence of the per-product AI ad creation orchestration served by
 * {@link ProductAdCampaignServiceImpl#createProductAd}.
 *
 * <p>Feature: multistore-ai-ads-operations, Property 4: 托管条件下持久化托管配置与安全边界
 *
 * <p>Validates: Requirements 1.6.
 *
 * <p>Property 4: For any legal per-product ad creation request, <em>iff</em>
 * {@code hosting_enabled} is true the system persists the campaign's
 * HostingConfig (whose {@code execution_mode} equals the resolved Execution_Mode)
 * and the corresponding Safety_Boundary; when {@code hosting_enabled} is false,
 * no hosting config or safety boundary record is written.
 *
 * <p>The orchestration's persistence collaborators are mocked
 * ({@link CampaignService}, {@link HostingConfigService}, {@link OperationService},
 * {@link CampaignProductLinkMapper}, {@link SafetyBoundaryMapper}); the pure,
 * dependency-free {@link SafetyBoundaryValidator} is wired real so a legal
 * request runs end-to-end without an illegal-input rejection. Each property
 * iteration builds a fresh harness so Mockito invocation counts never leak
 * across runs. The two branches are asserted symmetrically: the hosting branch
 * verifies {@code saveCampaignConfig} (capturing the {@code execution_mode}) and
 * {@code SafetyBoundaryMapper.insert} are invoked; the non-hosting branch
 * verifies both are {@code never()} invoked.
 */
class ProductAdCampaignHostingBoundaryPropertyTest {

    private static final String USER_ID = UUID.randomUUID().toString();

    /** Mutable per-iteration harness wiring the service under test to fresh mocks. */
    private record Harness(ProductAdCampaignServiceImpl service,
                           HostingConfigService hostingConfigService,
                           SafetyBoundaryMapper safetyBoundaryMapper) {
    }

    /**
     * Build a fresh service with mocked persistence collaborators and a real
     * {@link SafetyBoundaryValidator}. The campaign and store stubs make a legal
     * Amazon-family request succeed end-to-end.
     */
    private Harness buildHarness() {
        SafetyBoundaryValidator safetyBoundaryValidator = new SafetyBoundaryValidator();
        CampaignService campaignService = mock(CampaignService.class);
        CampaignProductLinkMapper campaignProductLinkMapper = mock(CampaignProductLinkMapper.class);
        HostingConfigService hostingConfigService = mock(HostingConfigService.class);
        SafetyBoundaryMapper safetyBoundaryMapper = mock(SafetyBoundaryMapper.class);
        OperationService operationService = mock(OperationService.class);
        DataScopeService dataScopeService = mock(DataScopeService.class);
        StoreMapper storeMapper = mock(StoreMapper.class);

        // Campaign creation returns a campaign with a valid UUID id so the
        // orchestration can link the product and scope the hosting config.
        when(campaignService.createCampaign(any(), any()))
                .thenAnswer(invocation -> CampaignVo.builder()
                        .id(UUID.randomUUID().toString())
                        .name("Product Ad")
                        .build());

        // The target store belongs to the amazon family, satisfying the
        // platform-family isolation check (Req 1.8) so the flow proceeds.
        when(storeMapper.selectById(any())).thenAnswer(invocation -> StoreEntity.builder()
                .id(invocation.getArgument(0))
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

        return new Harness(service, hostingConfigService, safetyBoundaryMapper);
    }

    /**
     * Feature: multistore-ai-ads-operations, Property 4: 托管条件下持久化托管配置与安全边界
     *
     * <p>Validates: Requirements 1.6.
     *
     * <p>When {@code hosting_enabled} is true the HostingConfig (with the resolved
     * {@code execution_mode}) and the Safety_Boundary rows ARE persisted; when it
     * is false NEITHER is written.
     */
    @Property(tries = 200)
    void hostingConfigAndSafetyBoundaryPersistedIffHostingEnabled(
            @ForAll("legalRequests") LegalRequest legal) {

        Harness h = buildHarness();
        ProductAdCampaignRequest request = legal.request();
        ExecutionMode expectedMode = resolveExpectedMode(request.getExecutionMode());

        ProductAdCampaignResultVo result = h.service().createProductAd(request, USER_ID);

        // The resolved execution mode is reflected on the result regardless of branch.
        assertThat(result.getExecutionMode()).isEqualTo(expectedMode.value());

        if (request.isHostingEnabled()) {
            // Hosting branch: the campaign-scoped HostingConfig is persisted, and its
            // execution_mode equals the resolved Execution_Mode (Req 1.6).
            ArgumentCaptor<HostingConfigRequest> configCaptor =
                    ArgumentCaptor.forClass(HostingConfigRequest.class);
            verify(h.hostingConfigService(), times(1))
                    .saveCampaignConfig(any(), any(), configCaptor.capture(), any());
            assertThat(configCaptor.getValue().getExecutionMode())
                    .as("persisted execution_mode must equal the resolved Execution_Mode")
                    .isEqualTo(expectedMode.value());

            // Hosting branch: the corresponding Safety_Boundary rows are persisted.
            // Every legal request carries all four bid/budget bounds, so four rows
            // are inserted (one per supplied limit).
            verify(h.safetyBoundaryMapper(), times(4)).insert(any(SafetyBoundaryEntity.class));
        } else {
            // Non-hosting branch: neither a HostingConfig nor any Safety_Boundary is written.
            verify(h.hostingConfigService(), never())
                    .saveCampaignConfig(any(), any(), any(), any());
            verify(h.safetyBoundaryMapper(), never()).insert(any(SafetyBoundaryEntity.class));
        }
    }

    /** Mirror the service's Execution_Mode resolution (parse, else observe_only). */
    private static ExecutionMode resolveExpectedMode(String raw) {
        ExecutionMode parsed = ExecutionMode.parse(raw);
        return parsed != null ? parsed : ExecutionMode.DEFAULT;
    }

    // --- generators --------------------------------------------------------

    /** A legal request plus the hosting flag and execution-mode input it carries. */
    record LegalRequest(ProductAdCampaignRequest request) {
    }

    /**
     * Generate fully-legal requests (positive budget, ordered positive bid/budget
     * bounds) that vary in {@code hosting_enabled} and in the execution-mode input
     * (null, blank, unrecognised, or a valid enum value), so both property branches
     * and every Execution_Mode resolution outcome are exercised.
     */
    @Provide
    Arbitrary<LegalRequest> legalRequests() {
        Arbitrary<Boolean> hosting = Arbitraries.of(true, false);
        Arbitrary<String> executionModes = executionModeInputs();
        Arbitrary<Boolean> withTargetAcos = Arbitraries.of(true, false);
        return Combinators.combine(hosting, executionModes, positive(), positive(), positive(), withTargetAcos)
                .as((hostingEnabled, executionMode, budget, bidSpan, budgetSpan, hasAcos) -> {
                    ProductAdCampaignRequest r = new ProductAdCampaignRequest();
                    r.setStoreId(UUID.randomUUID().toString());
                    r.setProductId(UUID.randomUUID().toString());
                    r.setBudget(budget);
                    r.setHostingEnabled(hostingEnabled);
                    r.setExecutionMode(executionMode);
                    // Ordered, strictly-positive bounds so all four Safety_Boundary rows
                    // are supplied and validation passes.
                    r.setBidMin(bidSpan);
                    r.setBidMax(bidSpan.add(BigDecimal.ONE));
                    r.setBudgetMin(budgetSpan);
                    r.setBudgetMax(budgetSpan.add(BigDecimal.ONE));
                    if (hasAcos) {
                        r.setTargetAcos(new BigDecimal("25.00"));
                    }
                    return new LegalRequest(r);
                });
    }

    /**
     * Execution-mode inputs spanning the resolution space: {@code null}, blank,
     * an unrecognised token, and each valid enum wire value.
     */
    private Arbitrary<String> executionModeInputs() {
        return Arbitraries.of(
                null,
                "",
                "   ",
                "not_a_mode",
                ExecutionMode.OBSERVE_ONLY.value(),
                ExecutionMode.RECOMMEND_ONLY.value(),
                ExecutionMode.APPROVAL_REQUIRED.value(),
                ExecutionMode.AUTO_EXECUTE.value());
    }

    /** Strictly positive amounts within a realistic monetary range. */
    private Arbitrary<BigDecimal> positive() {
        return Arbitraries.bigDecimals()
                .between(new BigDecimal("0.01"), new BigDecimal("100000"))
                .ofScale(2);
    }
}
