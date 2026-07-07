package com.adpilot.modules.advertising.service.impl;

import com.adpilot.common.security.DataScopeService;
import com.adpilot.modules.advertising.dto.ProductAdCampaignRequest;
import com.adpilot.modules.advertising.hosting.ExecutionMode;
import com.adpilot.modules.advertising.hosting.HostingConfigService;
import com.adpilot.modules.advertising.mapper.CampaignProductLinkMapper;
import com.adpilot.modules.advertising.mapper.SafetyBoundaryMapper;
import com.adpilot.modules.advertising.operation.OperationService;
import com.adpilot.modules.advertising.service.CampaignService;
import com.adpilot.modules.advertising.support.SafetyBoundaryValidator;
import com.adpilot.modules.advertising.vo.CampaignVo;
import com.adpilot.modules.advertising.vo.ProductAdCampaignResultVo;
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
import java.util.Arrays;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Property-based test for execution-mode default resolution in
 * {@link ProductAdCampaignServiceImpl}.
 *
 * <p>Feature: multistore-ai-ads-operations, Property 2: 执行模式缺省回落到 observe_only.
 *
 * <p>Validates: Requirements 1.3.
 *
 * <p>For any execution-mode input string — including {@code null}, blank,
 * unrecognized, or a valid enum value (in any case, optionally surrounded by
 * whitespace) — the Execution_Mode the orchestration service resolves and
 * returns satisfies:
 * <ul>
 *   <li>{@code null} / blank / unrecognized → {@code observe_only}
 *       ({@link ExecutionMode#DEFAULT}); and</li>
 *   <li>a valid enum value → exactly that value.</li>
 * </ul>
 *
 * <p>The resolution reuses {@link ExecutionMode#parse(String)} and is exercised
 * through the public {@link ProductAdCampaignServiceImpl#createProductAd} entry
 * point so the test asserts the behaviour the service actually exposes. The
 * request is otherwise valid (positive budget, a product to link, no bounds) so
 * input validation (Req 1.4) always passes and execution-mode resolution is the
 * only variable under test.
 */
class ProductAdCampaignExecutionModePropertyTest {

    private final CampaignService campaignService = mock(CampaignService.class);
    private final CampaignProductLinkMapper campaignProductLinkMapper = mock(CampaignProductLinkMapper.class);
    private final HostingConfigService hostingConfigService = mock(HostingConfigService.class);
    private final SafetyBoundaryMapper safetyBoundaryMapper = mock(SafetyBoundaryMapper.class);
    private final OperationService operationService = mock(OperationService.class);
    private final DataScopeService dataScopeService = mock(DataScopeService.class);
    private final StoreMapper storeMapper = mock(StoreMapper.class);

    /**
     * A valid UUID store id. Task 3.2 added a store-scope + platform-family check to
     * {@code createProductAd}, so the legal end-to-end path now resolves the target store
     * through {@link StoreMapper} and requires it to belong to the {@code amazon} family.
     */
    private static final String STORE_ID = "11111111-1111-1111-1111-111111111111";

    private final ProductAdCampaignServiceImpl service = buildService();

    /**
     * Wire the orchestration service with mocked persistence collaborators. The
     * created campaign is stubbed to return a fresh id so {@code createProductAd}
     * runs end-to-end; the resolved Execution_Mode is the only property asserted.
     *
     * <p>The target store resolves to an {@code amazon}-family {@link StoreEntity} so the
     * task 3.2 store-scope/platform-family check passes. No {@link org.springframework.security.core.context.SecurityContext}
     * is established, so {@code DataScopeService} is never consulted (the unauthenticated
     * unit-test path skips data-scope enforcement) and the family check alone governs the
     * legal path.
     */
    private ProductAdCampaignServiceImpl buildService() {
        when(campaignService.createCampaign(any(), any()))
                .thenAnswer(invocation -> CampaignVo.builder()
                        .id(UUID.randomUUID().toString())
                        .name("Product Ad")
                        .build());
        when(storeMapper.selectById(any())).thenReturn(amazonStore());
        return new ProductAdCampaignServiceImpl(
                new SafetyBoundaryValidator(),
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
     * Feature: multistore-ai-ads-operations, Property 2: 执行模式缺省回落到 observe_only.
     *
     * <p>Validates: Requirements 1.3.
     */
    @Property(tries = 200)
    void executionModeResolvesToObserveOnlyByDefaultOrToTheGivenValue(
            @ForAll("executionModeCases") ModeCase modeCase) {

        ProductAdCampaignRequest request = validRequestWithExecutionMode(modeCase.raw());

        ProductAdCampaignResultVo result = service.createProductAd(request, "user-1");

        assertThat(result.getExecutionMode()).isEqualTo(modeCase.expected().value());
    }

    // --- helpers -----------------------------------------------------------

    /** A request that passes all Req 1.4 validation, varying only the execution mode. */
    private static ProductAdCampaignRequest validRequestWithExecutionMode(String executionMode) {
        ProductAdCampaignRequest request = new ProductAdCampaignRequest();
        request.setStoreId(STORE_ID);
        request.setParentAsin("B000000001");
        request.setBudget(new BigDecimal("10.00"));
        request.setExecutionMode(executionMode);
        return request;
    }

    private static String swapCase(String value) {
        StringBuilder sb = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            sb.append(i % 2 == 0 ? Character.toUpperCase(c) : Character.toLowerCase(c));
        }
        return sb.toString();
    }

    /** The raw input string and the Execution_Mode the service must resolve it to. */
    record ModeCase(String raw, ExecutionMode expected) {
    }

    // --- generators --------------------------------------------------------

    /** A mix of valid enum strings, null, blank, and random invalid strings. */
    @Provide
    Arbitrary<ModeCase> executionModeCases() {
        return Arbitraries.oneOf(
                validInputs(),
                nullInput(),
                blankInputs(),
                invalidInputs());
    }

    /**
     * Valid enum values in any case, optionally padded with leading/trailing
     * whitespace (which {@link ExecutionMode#parse} trims and lower-cases away).
     * Each resolves to exactly its own mode.
     */
    private Arbitrary<ModeCase> validInputs() {
        Arbitrary<ExecutionMode> modes = Arbitraries.of(ExecutionMode.values());
        Arbitrary<Integer> caseChoice = Arbitraries.integers().between(0, 2);
        Arbitrary<String> lead = whitespace();
        Arbitrary<String> trail = whitespace();
        return Combinators.combine(modes, caseChoice, lead, trail).as((mode, cc, l, t) -> {
            String base = mode.value();
            String cased = switch (cc) {
                case 1 -> base.toUpperCase();
                case 2 -> swapCase(base);
                default -> base;
            };
            return new ModeCase(l + cased + t, mode);
        });
    }

    /** A literal null input falls back to the default. */
    private Arbitrary<ModeCase> nullInput() {
        return Arbitraries.just(new ModeCase(null, ExecutionMode.DEFAULT));
    }

    /** Empty or whitespace-only inputs are blank and fall back to the default. */
    private Arbitrary<ModeCase> blankInputs() {
        return whitespace().map(ws -> new ModeCase(ws, ExecutionMode.DEFAULT));
    }

    /**
     * Random non-blank strings that do not (after trimming/lower-casing) match any
     * valid enum value — these are unrecognized and fall back to the default.
     */
    private Arbitrary<ModeCase> invalidInputs() {
        Set<String> validValues = Arrays.stream(ExecutionMode.values())
                .map(ExecutionMode::value)
                .collect(Collectors.toSet());
        return Arbitraries.strings()
                .ascii()
                .ofMinLength(1)
                .ofMaxLength(20)
                .filter(s -> !s.isBlank())
                .filter(s -> !validValues.contains(s.trim().toLowerCase()))
                .map(s -> new ModeCase(s, ExecutionMode.DEFAULT));
    }

    /** Zero to three whitespace characters (spaces and tabs). */
    private Arbitrary<String> whitespace() {
        return Arbitraries.of(' ', '\t')
                .list().ofMinSize(0).ofMaxSize(3)
                .map(chars -> chars.stream()
                        .map(String::valueOf)
                        .collect(Collectors.joining()));
    }
}
