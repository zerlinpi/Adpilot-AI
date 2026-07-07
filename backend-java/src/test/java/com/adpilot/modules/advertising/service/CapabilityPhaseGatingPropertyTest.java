package com.adpilot.modules.advertising.service;

import com.adpilot.modules.advertising.entity.CampaignEntity;
import com.adpilot.modules.advertising.entity.KeywordEntity;
import com.adpilot.modules.advertising.entity.PerformanceDailyEntity;
import com.adpilot.modules.advertising.entity.PersonalityPolicyEntity;
import com.adpilot.modules.advertising.mapper.CampaignMapper;
import com.adpilot.modules.advertising.mapper.KeywordMapper;
import com.adpilot.modules.advertising.mapper.OperationMapper;
import com.adpilot.modules.advertising.mapper.PerformanceDailyMapper;
import com.adpilot.modules.advertising.operation.CreateOperationCommand;
import com.adpilot.modules.advertising.operation.OperationScope;
import com.adpilot.modules.advertising.operation.OperationService;
import com.adpilot.modules.advertising.operation.OperationSource;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Label;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;
import net.jqwik.api.constraints.BigRange;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Property-based test for AI-hosting capability phase gating (task 13.13).
 *
 * <p>Feature: advertising-workspace-rework, Property 55: Capability phase gating.
 *
 * <p>Validates: Requirements 22.1, 22.2, 49.4, 54.1, 54.2, 54.3, 54.4.
 *
 * <p>Property 55 (transcribed from the design's Correctness Properties section): <em>For any active
 * phase (V1/V2/V3) and capability (bid/budget/keyword/negative), the capability is presented as
 * executable and has personality controls applied if and only if it is implemented for that phase
 * (V1=bid; V2=+budget; V3=+keyword+negative).</em>
 *
 * <p>The phased-delivery contract of Requirement 54.1 introduces each capability at a fixed phase
 * (bid at V1, budget at V2, keyword and negative at V3) and every higher phase enables a strictly
 * larger set. This test pins two complementary facets of that contract against the production gate:
 *
 * <ol>
 *   <li><b>The capability gate ({@link HostingPhase#supports}) matches the phased-delivery oracle</b>
 *       — for every (phase, capability) pair the gate returns {@code true} iff the capability's
 *       introduction phase is at or below the active phase, computed by an oracle derived directly
 *       from the Requirement 54.1 phase ladder rather than from the production {@code EnumSet}
 *       definitions (Req 22.1, 54.1/54.2/54.3/54.4).</li>
 *   <li><b>The optimizer emits — and applies personality controls to — exactly the gated capability</b>
 *       — driving the real {@link AiHostingOptimizer} at the active phase over a campaign whose
 *       recent ACoS demands a bid change, the optimizer emits an {@code ai_hosting}
 *       {@code platform_mutation} Operation for the {@code bid} field exactly when the phase supports
 *       {@link HostingAdjustmentType#BID}, never an Operation for a capability the active phase has
 *       not yet implemented, and the emitted Operation carries the personality rule version and AI
 *       decision audit fields (Req 22.2, 49.4, 54.2/54.3).</li>
 * </ol>
 *
 * <p>The oracle is independent of the production enum: capability/phase ladder positions are encoded
 * from the requirement text, so a mistake in {@link HostingPhase}'s capability sets would be caught
 * rather than mirrored.
 */
@Tag("pbt")
@Label("Feature: advertising-workspace-rework, Property 55: Capability phase gating")
class CapabilityPhaseGatingPropertyTest {

    /** Minimum property iterations mandated for this property. */
    private static final int MIN_ITERATIONS = 200;

    private static final BigDecimal TARGET_ACOS = new BigDecimal("25");

    /**
     * Feature: advertising-workspace-rework, Property 55: Capability phase gating.
     *
     * <p>Validates: Requirements 22.1, 22.2, 49.4, 54.1, 54.2, 54.3, 54.4.
     *
     * <p>For any active phase, a capability is gated as executable iff its introduction phase is at
     * or below the active phase, and the optimizer emits + applies personality controls to exactly
     * the gated capability and never one the active phase has not implemented.
     */
    @Property(tries = MIN_ITERATIONS)
    @Label("Property 55: capability gate and optimizer emission are gated exactly by the active phase")
    void capabilityIsExecutableIffImplementedForActivePhase(
            @ForAll("phases") HostingPhase phase,
            @ForAll @BigRange(min = "0.10", max = "50.00") BigDecimal currentBid) {

        // --- 1. The capability gate matches the phased-delivery oracle for EVERY capability. -----
        Set<HostingAdjustmentType> expectedExecutable = expectedExecutable(phase);
        for (HostingAdjustmentType type : HostingAdjustmentType.values()) {
            boolean expected = expectedExecutable.contains(type);
            assertThat(phase.supports(type))
                    .as("phase %s supports %s iff implemented for that phase (Req 54.1)", phase, type)
                    .isEqualTo(expected);
        }

        // --- 2. The optimizer emits exactly the gated capability and nothing un-implemented. -----
        BigDecimal bid = currentBid.setScale(4, java.math.RoundingMode.HALF_UP);
        Harness h = new Harness();
        // Recent ACoS (50%) is above target (25%) so a bid DECREASE is demanded — a real adjustment
        // is available so emission is driven solely by the phase gate, not by an empty decision.
        CampaignEntity campaign = h.hostedCampaign();
        KeywordEntity keyword = h.keyword(bid);
        when(h.performanceDailyMapper.selectList(any())).thenReturn(List.of(h.perf(50, 100)));
        when(h.keywordMapper.selectList(any())).thenReturn(List.of(keyword));

        int created = h.optimizer.optimizeCampaign(campaign, phase);

        boolean bidExecutable = phase.supports(HostingAdjustmentType.BID);
        assertThat(bidExecutable)
                .as("bid is executable from V1 onward, so every supported phase enables it (Req 54.1)")
                .isTrue();

        // The optimizer implements only the BID capability today; it must emit an Operation exactly
        // when the active phase gates BID as executable, and never emit one when it does not.
        assertThat(created)
                .as("optimizer emits a hosting Operation iff the active phase %s implements bid", phase)
                .isEqualTo(bidExecutable ? 1 : 0);

        if (bidExecutable) {
            ArgumentCaptor<CreateOperationCommand> captor =
                    ArgumentCaptor.forClass(CreateOperationCommand.class);
            verify(h.operationService, times(1)).createOperation(captor.capture());
            CreateOperationCommand cmd = captor.getValue();

            // The emitted/executable adjustment is exactly the BID capability — never a capability
            // the active phase has not yet implemented (budget/keyword/negative), which the optimizer
            // would surface through a different entity field.
            assertThat(cmd.getField())
                    .as("the only executable adjustment emitted is the gated bid capability")
                    .isEqualTo("bid");
            assertThat(cmd.getOperationSource()).isEqualTo(OperationSource.AI_HOSTING);
            assertThat(cmd.getOperationScope()).isEqualTo(OperationScope.PLATFORM_MUTATION);

            // Personality controls ARE applied to the gated capability (Req 49.4): the emitted
            // Operation carries the resolved personality rule version and the AI decision audit.
            assertThat(cmd.getPersonalityRuleVersion())
                    .as("personality controls are applied to the executable capability (Req 49.4)")
                    .isEqualTo("balanced-v1");
            assertThat(cmd.getAiDecision())
                    .as("the executable capability records its AI decision audit (Req 49.4)")
                    .isNotNull();
            assertThat(cmd.getAiDecision().getPersonalityAllowedMagnitude())
                    .as("the personality-allowed magnitude (decrease budget) is applied (Req 49.4)")
                    .isEqualByComparingTo(new BigDecimal("0.15"));
            // The clamped bid never breaches the personality decrease band (-15%) of the current bid.
            assertThat((BigDecimal) cmd.getAfterValue())
                    .isBetween(bid.multiply(new BigDecimal("0.85")).setScale(4, java.math.RoundingMode.HALF_UP),
                            bid);
        } else {
            verify(h.operationService, never()).createOperation(any());
        }
    }

    // --- oracle ----------------------------------------------------------------------------------

    /**
     * The set of capabilities that should be executable in {@code phase}, computed from the
     * Requirement 54.1 phase ladder (bid introduced at V1, budget at V2, keyword and negative at V3)
     * independently of the production {@link HostingPhase} capability sets: a capability is executable
     * iff its introduction phase is at or below the active phase.
     */
    private Set<HostingAdjustmentType> expectedExecutable(HostingPhase phase) {
        int active = phaseLevel(phase);
        Set<HostingAdjustmentType> executable = EnumSet.noneOf(HostingAdjustmentType.class);
        for (HostingAdjustmentType type : HostingAdjustmentType.values()) {
            if (introductionLevel(type) <= active) {
                executable.add(type);
            }
        }
        return executable;
    }

    /** The phase ladder position (Req 54.1): V1=1, V2=2, V3=3. */
    private int phaseLevel(HostingPhase phase) {
        switch (phase) {
            case V1:
                return 1;
            case V2:
                return 2;
            case V3:
                return 3;
            default:
                throw new IllegalStateException("unhandled phase " + phase);
        }
    }

    /** The phase at which each capability is introduced (Req 54.1): bid=V1, budget=V2, keyword/negative=V3. */
    private int introductionLevel(HostingAdjustmentType type) {
        switch (type) {
            case BID:
                return 1;
            case BUDGET:
                return 2;
            case KEYWORD:
            case NEGATIVE:
                return 3;
            default:
                throw new IllegalStateException("unhandled capability " + type);
        }
    }

    // --- generators ------------------------------------------------------------------------------

    /** Every active executable-hosting phase (Req 54). */
    @Provide
    Arbitrary<HostingPhase> phases() {
        return Arbitraries.of(HostingPhase.values());
    }

    // --- harness ---------------------------------------------------------------------------------

    /**
     * A fresh wiring of the real {@link AiHostingOptimizer} over mocked mappers/services, configured
     * with a deterministic balanced personality policy so the phase gate is the sole variable driving
     * emission.
     */
    private static final class Harness {

        private final CampaignMapper campaignMapper = Mockito.mock(CampaignMapper.class);
        private final KeywordMapper keywordMapper = Mockito.mock(KeywordMapper.class);
        private final PerformanceDailyMapper performanceDailyMapper = Mockito.mock(PerformanceDailyMapper.class);
        private final OperationService operationService = Mockito.mock(OperationService.class);
        private final PersonalityResolver personalityResolver = Mockito.mock(PersonalityResolver.class);
        private final PersonalityPolicyService personalityPolicyService = Mockito.mock(PersonalityPolicyService.class);
        private final OperationMapper operationMapper = Mockito.mock(OperationMapper.class);

        private final AiHostingOptimizer optimizer;

        Harness() {
            optimizer = new AiHostingOptimizer(campaignMapper, keywordMapper, performanceDailyMapper,
                    operationService, personalityResolver, personalityPolicyService, operationMapper,
                    new com.adpilot.modules.advertising.hosting.ReversibilityClassifier());
            ReflectionTestUtils.setField(optimizer, "lookbackDays", 14);
            ReflectionTestUtils.setField(optimizer, "minBid", new BigDecimal("0.02"));
            ReflectionTestUtils.setField(optimizer, "maxBid", new BigDecimal("1000"));
            ReflectionTestUtils.setField(optimizer, "phaseConfig", "V1");
            ReflectionTestUtils.setField(optimizer, "globalPause", false);

            when(personalityResolver.resolveForCampaign(any(CampaignEntity.class)))
                    .thenReturn(AiPersonality.BALANCED);
            when(personalityPolicyService.resolvePolicy(any())).thenReturn(balancedPolicy());
        }

        private PersonalityPolicyEntity balancedPolicy() {
            return PersonalityPolicyEntity.builder()
                    .scope("system")
                    .personality("balanced")
                    .maxBidIncreaseRatio(new BigDecimal("0.10"))
                    .maxBidDecreaseRatio(new BigDecimal("0.15"))
                    .approvalBidChangeRatio(new BigDecimal("0.07"))
                    .ruleVersion("balanced-v1")
                    .build();
        }

        CampaignEntity hostedCampaign() {
            return CampaignEntity.builder()
                    .id(UUID.randomUUID())
                    .storeId(UUID.randomUUID())
                    .name("Camp")
                    .hostingEnabled(true)
                    .targetAcos(TARGET_ACOS)
                    .build();
        }

        KeywordEntity keyword(BigDecimal bid) {
            return KeywordEntity.builder()
                    .id(UUID.randomUUID())
                    .campaignId(UUID.randomUUID())
                    .adGroupId(UUID.randomUUID())
                    .storeId(UUID.randomUUID())
                    .keywordText("kw")
                    .status("enabled")
                    .bid(bid)
                    .version(0L)
                    .build();
        }

        PerformanceDailyEntity perf(double spend, double sales) {
            return PerformanceDailyEntity.builder()
                    .date(LocalDate.now())
                    .spend(BigDecimal.valueOf(spend))
                    .sales(BigDecimal.valueOf(sales))
                    .build();
        }
    }
}
