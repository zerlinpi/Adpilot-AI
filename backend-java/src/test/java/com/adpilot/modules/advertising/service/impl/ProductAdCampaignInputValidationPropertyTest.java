package com.adpilot.modules.advertising.service.impl;

import com.adpilot.common.exception.BusinessException;
import com.adpilot.common.security.DataScopeService;
import com.adpilot.modules.advertising.dto.ProductAdCampaignRequest;
import com.adpilot.modules.advertising.hosting.HostingConfigService;
import com.adpilot.modules.advertising.mapper.CampaignProductLinkMapper;
import com.adpilot.modules.advertising.mapper.SafetyBoundaryMapper;
import com.adpilot.modules.advertising.operation.OperationService;
import com.adpilot.modules.advertising.service.CampaignService;
import com.adpilot.modules.advertising.support.SafetyBoundaryValidator;
import com.adpilot.modules.advertising.vo.CampaignVo;
import com.adpilot.modules.rbac.PlatformFamily;
import com.adpilot.modules.store.entity.StoreEntity;
import com.adpilot.modules.store.mapper.StoreMapper;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Property-based test for the per-product AI ad creation input validation served
 * by {@link ProductAdCampaignServiceImpl}.
 *
 * <p>Feature: multistore-ai-ads-operations, Property 1: 输入校验拒绝非法预算/边界
 *
 * <p>Validates: Requirements 1.4.
 *
 * <p>Property 1: For any 单产品广告创建请求, if budget / targetAcos / bid bounds /
 * budget bounds contain a non-positive value, or any upper bound is smaller than
 * its corresponding lower bound, the orchestration service MUST reject the
 * submission with a validation error (HTTP 400, naming the offending field) and
 * create NO campaign, product link, hosting config, or safety boundary.
 *
 * <p>In this orchestration slice (task 2.2) the input validation runs first,
 * before any persistence collaborator is touched: {@link ProductAdCampaignServiceImpl}
 * throws a field-naming {@link BusinessException} (status 400) on the first
 * violation and returns nothing. Because the rejection short-circuits the method
 * before any campaign / link / hosting config / safety boundary write can occur,
 * the "creates nothing" guarantee is established by the absence of any returned
 * result on the rejection path. The bid/budget bound ordering check reuses the
 * production {@link SafetyBoundaryValidator}, so a real (dependency-free) instance
 * is wired here rather than a mock.
 */
class ProductAdCampaignInputValidationPropertyTest {

    /** Real validator: it is a pure, dependency-free component used for bound ordering. */
    private final SafetyBoundaryValidator safetyBoundaryValidator = new SafetyBoundaryValidator();

    private final CampaignService campaignService = mock(CampaignService.class);
    private final CampaignProductLinkMapper campaignProductLinkMapper = mock(CampaignProductLinkMapper.class);
    private final HostingConfigService hostingConfigService = mock(HostingConfigService.class);
    private final SafetyBoundaryMapper safetyBoundaryMapper = mock(SafetyBoundaryMapper.class);
    private final OperationService operationService = mock(OperationService.class);
    private final DataScopeService dataScopeService = mock(DataScopeService.class);
    private final StoreMapper storeMapper = mock(StoreMapper.class);

    /**
     * A valid UUID store id for the legal-path requests. Task 3.2 added a store-scope +
     * platform-family check to {@code createProductAd}: the legal request now resolves the
     * target store through {@link StoreMapper} and requires it to belong to the {@code amazon}
     * family. Illegal-input requests are rejected by validation before that check, so they
     * need no store stub.
     */
    private static final String STORE_ID = "11111111-1111-1111-1111-111111111111";

    private final ProductAdCampaignServiceImpl service = buildService();
    /**
     * Wire the orchestration service with the real validator (under test) and
     * mocked persistence collaborators so a legal request can run end-to-end
     * without an illegal-input rejection.
     *
     * <p>The target store resolves to an {@code amazon}-family {@link StoreEntity} so the
     * task 3.2 store-scope/platform-family check passes on the legal path. No
     * {@link org.springframework.security.core.context.SecurityContext} is established, so
     * {@code DataScopeService} is never consulted (the unauthenticated unit-test path skips
     * data-scope enforcement) and the family check alone governs the legal path.
     */
    private ProductAdCampaignServiceImpl buildService() {
        when(campaignService.createCampaign(any(), any()))
                .thenAnswer(invocation -> CampaignVo.builder()
                        .id(UUID.randomUUID().toString())
                        .name("Product Ad")
                        .build());
        when(storeMapper.selectById(any())).thenReturn(amazonStore());
        return new ProductAdCampaignServiceImpl(
                safetyBoundaryValidator,
                campaignService,
                campaignProductLinkMapper,
                hostingConfigService,
                safetyBoundaryMapper,
                operationService,
                dataScopeService,
                storeMapper);
    }

    /** An {@code amazon}-family store so the task 3.2 platform-family check passes. */
    private static StoreEntity amazonStore() {
        StoreEntity store = new StoreEntity();
        store.setId(UUID.fromString(STORE_ID));
        store.setPlatformFamily(PlatformFamily.AMAZON.getCode());
        return store;
    }

    /**
     * Feature: multistore-ai-ads-operations, Property 1: 输入校验拒绝非法预算/边界
     *
     * <p>Validates: Requirements 1.4.
     *
     * <p>Any request carrying a non-positive amount or an inverted bound is
     * rejected with HTTP 400 naming the offending field, and no result is
     * produced (no campaign / link / hosting config / safety boundary is created).
     */
    @Property(tries = 200)
    void illegalBudgetOrBoundsAreRejectedWithFieldNamed400AndNothingCreated(
            @ForAll("illegalRequests") IllegalRequest illegal) {

        BusinessException ex = catchThrowableOfType(
                () -> service.createProductAd(illegal.request(), "user-1"),
                BusinessException.class);

        // Rejected (not silently accepted): a BusinessException was thrown, so the
        // method short-circuited before producing any ProductAdCampaignResultVo —
        // i.e. no campaign, product link, hosting config or safety boundary record.
        assertThat(ex)
                .as("illegal field '%s' must be rejected", illegal.expectedField())
                .isNotNull();

        // HTTP 400 validation error.
        assertThat(ex.getStatus()).isEqualTo(400);

        // The error names the specific offending field (Req 1.4).
        assertThat(ex.getMessage())
                .as("rejection must name the offending field")
                .contains("[field=" + illegal.expectedField() + "]");
    }

    /**
     * Sanity anchor: a fully-legal request (positive amounts, ordered bounds) is
     * NOT rejected by validation, ensuring the property above fails on illegal
     * input rather than rejecting everything indiscriminately.
     */
    @Property(tries = 100)
    void legalRequestPassesValidation(@ForAll("legalRequests") ProductAdCampaignRequest request) {
        assertThatThrownBy(() -> {
            // Should NOT throw a validation BusinessException for legal input.
            service.createProductAd(request, "user-1");
            throw new NoValidationError();
        }).isInstanceOf(NoValidationError.class);
    }

    /** Marker used to assert the legal path did not throw a validation error. */
    private static final class NoValidationError extends RuntimeException {
    }

    // --- generators --------------------------------------------------------

    /** A request that violates exactly one budget/bound rule, plus the expected field name. */
    record IllegalRequest(ProductAdCampaignRequest request, String expectedField) {
    }

    /**
     * Generate requests that each carry exactly one illegal value: a non-positive
     * amount (zero or negative) in one of budget/targetAcos/bid bounds/budget
     * bounds, or an inverted upper-vs-lower bound. Every other field is legal so
     * the violation is unambiguous and the named field is deterministic.
     */
    @Provide
    Arbitrary<IllegalRequest> illegalRequests() {
        Arbitrary<ViolationKind> kinds = Arbitraries.of(ViolationKind.values());
        return Combinators.combine(kinds, nonPositive(), positive(), positive())
                .as(this::buildIllegal);
    }

    private IllegalRequest buildIllegal(ViolationKind kind,
                                        BigDecimal badAmount,
                                        BigDecimal low,
                                        BigDecimal high) {
        ProductAdCampaignRequest r = legalBase();
        // Ensure low < high for a clean "ordered" baseline, then invert where needed.
        BigDecimal lower = low.min(high);
        BigDecimal upper = low.max(high).add(BigDecimal.ONE); // strictly greater

        return switch (kind) {
            case BUDGET_NON_POSITIVE -> {
                r.setBudget(badAmount);
                yield new IllegalRequest(r, "budget");
            }
            case TARGET_ACOS_NON_POSITIVE -> {
                r.setTargetAcos(badAmount);
                yield new IllegalRequest(r, "targetAcos");
            }
            case BID_MIN_NON_POSITIVE -> {
                r.setBidMin(badAmount);
                yield new IllegalRequest(r, "bidMin");
            }
            case BID_MAX_NON_POSITIVE -> {
                r.setBidMax(badAmount);
                yield new IllegalRequest(r, "bidMax");
            }
            case BUDGET_MIN_NON_POSITIVE -> {
                r.setBudgetMin(badAmount);
                yield new IllegalRequest(r, "budgetMin");
            }
            case BUDGET_MAX_NON_POSITIVE -> {
                r.setBudgetMax(badAmount);
                yield new IllegalRequest(r, "budgetMax");
            }
            case BID_MAX_BELOW_MIN -> {
                // Both positive, but max < min — ordering violation named on bidMax.
                r.setBidMin(upper);
                r.setBidMax(lower);
                yield new IllegalRequest(r, "bidMax");
            }
            case BUDGET_MAX_BELOW_MIN -> {
                r.setBudgetMin(upper);
                r.setBudgetMax(lower);
                yield new IllegalRequest(r, "budgetMax");
            }
        };
    }

    /** A legal request with all amounts positive and bounds correctly ordered. */
    @Provide
    Arbitrary<ProductAdCampaignRequest> legalRequests() {
        return Combinators.combine(positive(), positive(), positive(), positive(), positive())
                .as((budget, acos, bidA, bidB, budgetSpan) -> {
                    ProductAdCampaignRequest r = legalBase();
                    r.setBudget(budget);
                    r.setTargetAcos(acos);
                    BigDecimal bidLow = bidA.min(bidB);
                    BigDecimal bidHigh = bidA.max(bidB);
                    r.setBidMin(bidLow);
                    r.setBidMax(bidHigh);
                    r.setBudgetMin(budgetSpan);
                    r.setBudgetMax(budgetSpan.add(budgetSpan)); // >= min
                    return r;
                });
    }

    /** Base request whose product link and budget are valid, leaving room to inject one fault. */
    private static ProductAdCampaignRequest legalBase() {
        ProductAdCampaignRequest r = new ProductAdCampaignRequest();
        r.setStoreId(STORE_ID);
        r.setProductId("product-1");
        r.setBudget(new BigDecimal("10.00"));
        return r;
    }

    /** Non-positive amounts: zero and negatives within a realistic monetary range. */
    private Arbitrary<BigDecimal> nonPositive() {
        return Arbitraries.bigDecimals()
                .between(new BigDecimal("-100000"), BigDecimal.ZERO)
                .ofScale(2);
    }

    /** Strictly positive amounts within a realistic monetary range. */
    private Arbitrary<BigDecimal> positive() {
        return Arbitraries.bigDecimals()
                .between(new BigDecimal("0.01"), new BigDecimal("100000"))
                .ofScale(2);
    }

    private enum ViolationKind {
        BUDGET_NON_POSITIVE,
        TARGET_ACOS_NON_POSITIVE,
        BID_MIN_NON_POSITIVE,
        BID_MAX_NON_POSITIVE,
        BUDGET_MIN_NON_POSITIVE,
        BUDGET_MAX_NON_POSITIVE,
        BID_MAX_BELOW_MIN,
        BUDGET_MAX_BELOW_MIN
    }
}
