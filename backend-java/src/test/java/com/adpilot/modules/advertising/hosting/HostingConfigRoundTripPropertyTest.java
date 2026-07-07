package com.adpilot.modules.advertising.hosting;

import com.adpilot.common.exception.BusinessException;
import com.adpilot.modules.advertising.dto.HostingConfigRequest;
import com.adpilot.modules.advertising.entity.SafetyBoundaryEntity;
import com.adpilot.modules.advertising.mapper.SafetyBoundaryMapper;
import com.adpilot.modules.advertising.service.AiPersonality;
import com.adpilot.modules.advertising.support.SafetyBoundaryValidator;
import com.adpilot.modules.advertising.vo.HostingConfigVo;
import com.adpilot.modules.audit.service.AuditLogService;
import com.adpilot.modules.store.entity.StoreEntity;
import com.adpilot.modules.store.mapper.StoreMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.mockito.Mockito;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Property-based test for hosting configuration save/load round-trip and backend validation
 * served by {@link HostingConfigServiceImpl}.
 *
 * <p>Feature: amazon-ads-ai-hosting-system, Property 41: Settings save/load round-trip and
 * validation.
 *
 * <p>Validates: Requirements 12.2, 12.6, 21.3, 21.4.
 *
 * <p>The {@link HostingConfigMapper} is modelled as an in-memory single-row store so that a
 * {@code saveStoreConfig} followed by {@code getConfig} reads back exactly what was persisted
 * (the snake_case JSON payload). The properties assert that:
 * <ul>
 *   <li>any VALID config (valid personality, valid execution mode, threshold in [0,1], and a
 *       boundary override that only tightens) round-trips: the loaded config equals the saved
 *       config (Req 12.2, 21.4);</li>
 *   <li>any config with an invalid personality enum is rejected (Req 12.6);</li>
 *   <li>any config with an invalid execution-mode enum is rejected (Req 12.6);</li>
 *   <li>any config with {@code auto_execute_threshold} outside [0,1] is rejected (Req 21.3); and</li>
 *   <li>any store boundary override looser than the resolved higher-level boundary
 *       (only-tighten violation) is rejected (Req 21.4).</li>
 * </ul>
 */
class HostingConfigRoundTripPropertyTest {

    private static final BigDecimal ZERO = BigDecimal.ZERO;
    private static final BigDecimal ONE = BigDecimal.ONE;

    /**
     * A freshly wired service whose mapper is backed by an in-memory single-row store,
     * together with the store / org ids and the backing reference for assertions.
     */
    private record Fixture(HostingConfigServiceImpl service,
                           HostingConfigMapper hostingConfigMapper,
                           SafetyBoundaryMapper safetyBoundaryMapper,
                           UUID storeId,
                           UUID orgId,
                           AtomicReference<HostingConfigEntity> stored) {
    }

    /**
     * Build a service whose {@link HostingConfigMapper} behaves like a single-row in-memory
     * store: {@code selectOne} returns whatever was last inserted/updated, so save-then-load
     * round-trips through the persisted snake_case JSON. By default there are no higher-level
     * boundaries, so boundary overrides are unconstrained.
     */
    private static Fixture newFixture() {
        HostingConfigMapper hostingConfigMapper = Mockito.mock(HostingConfigMapper.class);
        StoreMapper storeMapper = Mockito.mock(StoreMapper.class);
        SafetyBoundaryMapper safetyBoundaryMapper = Mockito.mock(SafetyBoundaryMapper.class);
        AuditLogService auditLogService = Mockito.mock(AuditLogService.class);

        HostingConfigServiceImpl service = new HostingConfigServiceImpl(
                hostingConfigMapper, storeMapper, safetyBoundaryMapper,
                new SafetyBoundaryValidator(), auditLogService, new ObjectMapper());

        UUID storeId = UUID.randomUUID();
        UUID orgId = UUID.randomUUID();

        AtomicReference<HostingConfigEntity> stored = new AtomicReference<>(null);
        when(hostingConfigMapper.selectOne(any(LambdaQueryWrapper.class)))
                .thenAnswer(inv -> stored.get());
        when(hostingConfigMapper.insert(any(HostingConfigEntity.class))).thenAnswer(inv -> {
            stored.set(inv.getArgument(0));
            return 1;
        });
        when(hostingConfigMapper.updateById(any(HostingConfigEntity.class))).thenAnswer(inv -> {
            stored.set(inv.getArgument(0));
            return 1;
        });

        when(storeMapper.selectById(storeId)).thenReturn(
                StoreEntity.builder().id(storeId).orgId(orgId).build());
        when(safetyBoundaryMapper.findByScopeAndScopeId(eq("organization"), eq(orgId)))
                .thenReturn(List.of());
        when(safetyBoundaryMapper.findSystemBoundaries()).thenReturn(List.of());

        return new Fixture(service, hostingConfigMapper, safetyBoundaryMapper, storeId, orgId, stored);
    }

    // ────────────────────────────────────────────────────────────────────────────
    // Property 41 (1): valid config round-trips through save/load (Req 12.2, 21.4)
    // ────────────────────────────────────────────────────────────────────────────

    /**
     * Feature: amazon-ads-ai-hosting-system, Property 41: Settings save/load round-trip and
     * validation. Validates: Requirements 12.2, 21.4.
     */
    @Property(tries = 300)
    void validConfigRoundTripsThroughSaveAndLoad(
            @ForAll("phases") String phase,
            @ForAll("personalities") String personality,
            @ForAll("executionModes") String executionMode,
            @ForAll("validThresholds") BigDecimal threshold,
            @ForAll boolean shadowMode,
            @ForAll boolean emergencyAutoAction,
            @ForAll("maxBidOverrides") BigDecimal maxBid) {

        Fixture fx = newFixture();

        HostingConfigRequest req = HostingConfigRequest.builder()
                .activePhase(phase)
                .defaultPersonality(personality)
                .executionMode(executionMode)
                .autoExecuteThreshold(threshold)
                .shadowMode(shadowMode)
                .emergencyAutoActionEnabled(emergencyAutoAction)
                .boundaryOverrides(Map.of("MAX_BID", maxBid))
                .build();

        // Save (valid input must not be rejected).
        HostingConfigVo saved = fx.service().saveStoreConfig(fx.storeId(), req, null);

        // Load the persisted JSON back through getConfig.
        HostingConfigVo loaded = fx.service().getConfig(fx.storeId(), "store", fx.storeId());

        // The loaded config equals what was saved (after the documented normalization).
        String expectedPhase = phase.trim().toUpperCase(Locale.ROOT);
        String expectedPersonality = personality.trim().toLowerCase(Locale.ROOT);
        String expectedMode = executionMode.trim().toLowerCase(Locale.ROOT);

        assertThat(saved.getActivePhase()).isEqualTo(expectedPhase);
        assertThat(loaded.getActivePhase()).isEqualTo(expectedPhase);

        assertThat(saved.getDefaultPersonality()).isEqualTo(expectedPersonality);
        assertThat(loaded.getDefaultPersonality()).isEqualTo(expectedPersonality);

        assertThat(saved.getExecutionMode()).isEqualTo(expectedMode);
        assertThat(loaded.getExecutionMode()).isEqualTo(expectedMode);

        assertThat(loaded.getAutoExecuteThreshold()).isEqualByComparingTo(threshold);
        assertThat(loaded.getShadowMode()).isEqualTo(shadowMode);
        assertThat(loaded.getEmergencyAutoActionEnabled()).isEqualTo(emergencyAutoAction);

        assertThat(loaded.getBoundaryOverrides()).containsKey("MAX_BID");
        assertThat(loaded.getBoundaryOverrides().get("MAX_BID")).isEqualByComparingTo(maxBid);

        // The two reads are consistent with each other across the JSON round-trip.
        assertThat(loaded.getActivePhase()).isEqualTo(saved.getActivePhase());
        assertThat(loaded.getDefaultPersonality()).isEqualTo(saved.getDefaultPersonality());
        assertThat(loaded.getExecutionMode()).isEqualTo(saved.getExecutionMode());
    }

    // ────────────────────────────────────────────────────────────────────────────
    // Property 41 (2): invalid personality enum is rejected (Req 12.6)
    // ────────────────────────────────────────────────────────────────────────────

    /**
     * Feature: amazon-ads-ai-hosting-system, Property 41: Settings save/load round-trip and
     * validation. Validates: Requirements 12.6.
     */
    @Property(tries = 200)
    void invalidPersonalityIsRejected(@ForAll("invalidPersonalities") String personality) {
        Fixture fx = newFixture();
        HostingConfigRequest req = HostingConfigRequest.builder()
                .defaultPersonality(personality)
                .build();

        assertThatThrownBy(() -> fx.service().saveStoreConfig(fx.storeId(), req, null))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", "HOSTING_INVALID_PERSONALITY");
    }

    // ────────────────────────────────────────────────────────────────────────────
    // Property 41 (3): invalid execution-mode enum is rejected (Req 12.6)
    // ────────────────────────────────────────────────────────────────────────────

    /**
     * Feature: amazon-ads-ai-hosting-system, Property 41: Settings save/load round-trip and
     * validation. Validates: Requirements 12.6.
     */
    @Property(tries = 200)
    void invalidExecutionModeIsRejected(@ForAll("invalidExecutionModes") String executionMode) {
        Fixture fx = newFixture();
        HostingConfigRequest req = HostingConfigRequest.builder()
                .executionMode(executionMode)
                .build();

        assertThatThrownBy(() -> fx.service().saveStoreConfig(fx.storeId(), req, null))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", "HOSTING_INVALID_EXECUTION_MODE");
    }

    // ────────────────────────────────────────────────────────────────────────────
    // Property 41 (4): threshold outside [0,1] is rejected (Req 21.3)
    // ────────────────────────────────────────────────────────────────────────────

    /**
     * Feature: amazon-ads-ai-hosting-system, Property 41: Settings save/load round-trip and
     * validation. Validates: Requirements 21.3.
     */
    @Property(tries = 200)
    void thresholdOutsideUnitIntervalIsRejected(@ForAll("invalidThresholds") BigDecimal threshold) {
        Fixture fx = newFixture();
        HostingConfigRequest req = HostingConfigRequest.builder()
                .autoExecuteThreshold(threshold)
                .build();

        assertThatThrownBy(() -> fx.service().saveStoreConfig(fx.storeId(), req, null))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", "HOSTING_INVALID_THRESHOLD");
    }

    // ────────────────────────────────────────────────────────────────────────────
    // Property 41 (5): a boundary override looser than the higher-level is rejected (Req 21.4)
    // ────────────────────────────────────────────────────────────────────────────

    /**
     * Feature: amazon-ads-ai-hosting-system, Property 41: Settings save/load round-trip and
     * validation. Validates: Requirements 21.4.
     *
     * <p>{@code MAX_BID} is an upper-bound limit, so a store override LARGER than the
     * organization cap loosens it and must be rejected by the only-tighten rule.
     */
    @Property(tries = 200)
    void looseningBoundaryOverrideIsRejected(
            @ForAll("boundaryCaps") BigDecimal orgCap,
            @ForAll("positiveDeltas") BigDecimal delta) {

        Fixture fx = newFixture();
        // Organization caps MAX_BID at orgCap.
        when(fx.safetyBoundaryMapper().findByScopeAndScopeId(eq("organization"), eq(fx.orgId())))
                .thenReturn(List.of(boundaryRow("MAX_BID", "upper_bound", orgCap)));

        BigDecimal looser = orgCap.add(delta); // strictly larger ⇒ looser for an upper bound
        HostingConfigRequest req = HostingConfigRequest.builder()
                .boundaryOverrides(Map.of("MAX_BID", looser))
                .build();

        assertThatThrownBy(() -> fx.service().saveStoreConfig(fx.storeId(), req, null))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", "HOSTING_BOUNDARY_VIOLATION");
    }

    // ────────────────────────────────────────────────────────────────────────────
    // Helpers
    // ────────────────────────────────────────────────────────────────────────────

    private static SafetyBoundaryEntity boundaryRow(String limitType, String semantics, BigDecimal amount) {
        return SafetyBoundaryEntity.builder()
                .id(UUID.randomUUID())
                .scope("organization")
                .limitType(limitType)
                .valueType("amount")
                .valueAmount(amount)
                .comparisonSemantics(semantics)
                .build();
    }

    // ────────────────────────────────────────────────────────────────────────────
    // Generators
    // ────────────────────────────────────────────────────────────────────────────

    /** Valid active phases, with arbitrary casing/whitespace (the service uppercases/trims). */
    @Provide
    Arbitrary<String> phases() {
        return Arbitraries.of("V1", "V2", "V3", "v1", "v2", "v3", " V2 ");
    }

    /** Valid personalities, with arbitrary casing/whitespace (the service lowercases/trims). */
    @Provide
    Arbitrary<String> personalities() {
        return Arbitraries.of("conservative", "balanced", "aggressive",
                "Conservative", "Balanced", "AGGRESSIVE", " balanced ");
    }

    /** Valid execution modes, with arbitrary casing/whitespace. */
    @Provide
    Arbitrary<String> executionModes() {
        return Arbitraries.of("observe_only", "recommend_only", "approval_required", "auto_execute",
                "Observe_Only", "AUTO_EXECUTE", " approval_required ");
    }

    /** Thresholds within the inclusive unit interval [0,1], at a precision that round-trips. */
    @Provide
    Arbitrary<BigDecimal> validThresholds() {
        return Arbitraries.bigDecimals()
                .between(ZERO, ONE)
                .ofScale(2);
    }

    /** Positive bid ceilings for a store override (no higher-level cap ⇒ unconstrained). */
    @Provide
    Arbitrary<BigDecimal> maxBidOverrides() {
        return Arbitraries.bigDecimals()
                .between(new BigDecimal("0.01"), new BigDecimal("100.00"))
                .ofScale(2);
    }

    /** Non-blank strings that are not one of the three canonical personalities. */
    @Provide
    Arbitrary<String> invalidPersonalities() {
        return Arbitraries.strings()
                .alpha()
                .ofMinLength(1)
                .ofMaxLength(16)
                .filter(s -> AiPersonality.parse(s).isEmpty());
    }

    /** Non-blank strings that are not one of the four canonical execution modes. */
    @Provide
    Arbitrary<String> invalidExecutionModes() {
        return Arbitraries.strings()
                .alpha()
                .ofMinLength(1)
                .ofMaxLength(16)
                .filter(s -> ExecutionMode.parse(s) == null);
    }

    /** Thresholds strictly outside the inclusive unit interval [0,1]. */
    @Provide
    Arbitrary<BigDecimal> invalidThresholds() {
        Arbitrary<BigDecimal> negative = Arbitraries.bigDecimals()
                .between(new BigDecimal("-1000"), new BigDecimal("-0.0001"))
                .ofScale(4);
        Arbitrary<BigDecimal> aboveOne = Arbitraries.bigDecimals()
                .between(new BigDecimal("1.0001"), new BigDecimal("1000"))
                .ofScale(4);
        return Arbitraries.oneOf(negative, aboveOne)
                .filter(v -> v.compareTo(ZERO) < 0 || v.compareTo(ONE) > 0);
    }

    /** Organization MAX_BID caps. */
    @Provide
    Arbitrary<BigDecimal> boundaryCaps() {
        return Arbitraries.bigDecimals()
                .between(new BigDecimal("0.50"), new BigDecimal("50.00"))
                .ofScale(2);
    }

    /** Strictly positive deltas used to loosen an upper-bound limit. */
    @Provide
    Arbitrary<BigDecimal> positiveDeltas() {
        return Arbitraries.bigDecimals()
                .between(new BigDecimal("0.01"), new BigDecimal("50.00"))
                .ofScale(2);
    }
}
