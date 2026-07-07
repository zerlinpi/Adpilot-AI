package com.adpilot.modules.advertising.hosting;

import com.adpilot.modules.advertising.service.HostingAdjustmentType;
import com.adpilot.modules.advertising.service.HostingPhase;
import net.jqwik.api.*;

import java.util.EnumSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Property-based test for {@link PhaseConfigurationServiceImpl#validateTransition} and
 * {@link PhaseConfigurationServiceImpl#computeDisabledCapabilities}.
 *
 * <p>Feature: amazon-ads-ai-hosting-system, Property 42: Phase transition validity and downgrade cleanup
 *
 * <p><b>Validates: Requirements 17.5, 17.6</b>
 *
 * <p>Properties tested:
 * <ol>
 *   <li>{@code validateTransition} returns {@code true} ONLY for adjacent phase pairs
 *       (V1↔V2, V2↔V3); {@code false} for non-adjacent (V1↔V3, V3↔V1) and same-phase.</li>
 *   <li>For any downgrade, {@code computeDisabledCapabilities} returns exactly the capabilities
 *       present in the current phase but absent in the target phase.</li>
 *   <li>For any upgrade, {@code computeDisabledCapabilities} returns an empty set.</li>
 *   <li>The transition-validity relation is symmetric for adjacency: if A↔B is valid in one
 *       direction it is valid in the other.</li>
 * </ol>
 *
 * <p>{@code validateTransition} and {@code computeDisabledCapabilities} are pure functions that do
 * not touch the injected collaborators, so the dependencies are mocked solely to construct the
 * service (matching the mocking pattern in {@code PhaseConfigurationServiceTest}).</p>
 */
@Tag("pbt")
@Label("Feature: amazon-ads-ai-hosting-system, Property 42: Phase transition validity and downgrade cleanup")
class PhaseTransitionValidityPropertyTest {

    private final PhaseConfigurationServiceImpl service = new PhaseConfigurationServiceImpl(
            mock(HostingConfigMapper.class),
            mock(com.adpilot.modules.advertising.mapper.OperationMapper.class),
            mock(com.adpilot.modules.advertising.mapper.OperationOutboxMapper.class),
            mock(com.adpilot.modules.advertising.operation.OperationStateMachine.class),
            mock(HostingApprovalService.class),
            mock(com.adpilot.modules.audit.service.AuditLogService.class)
    );

    /** The only legal (adjacent) transitions, modeled independently from the implementation. */
    private static boolean isAdjacent(HostingPhase a, HostingPhase b) {
        return Math.abs(PhaseConfigurationServiceImpl.phaseOrdinal(a)
                - PhaseConfigurationServiceImpl.phaseOrdinal(b)) == 1;
    }

    // ── Property 1: validateTransition is true ONLY for adjacent, distinct phases ──

    /**
     * Feature: amazon-ads-ai-hosting-system, Property 42: Phase transition validity and downgrade cleanup
     *
     * <p><b>Validates: Requirements 17.5, 17.6</b></p>
     *
     * <p>For every ordered pair of phases, {@code validateTransition} returns {@code true} exactly
     * when the phases are adjacent (ordinal distance 1) — i.e. V1↔V2 and V2↔V3 — and {@code false}
     * for non-adjacent pairs (V1↔V3, V3↔V1) and same-phase pairs.</p>
     */
    @Property
    @Label("validateTransition is true iff phases are adjacent and distinct")
    void validateTransitionTrueOnlyForAdjacentPairs(
            @ForAll HostingPhase current,
            @ForAll HostingPhase target) {

        boolean expected = isAdjacent(current, target);

        assertThat(service.validateTransition(current, target))
                .as("validateTransition(%s, %s) should be %s", current, target, expected)
                .isEqualTo(expected);
    }

    /**
     * Same-phase transitions are always invalid.
     */
    @Property
    @Label("same-phase transitions are always invalid")
    void samePhaseTransitionsAreInvalid(@ForAll HostingPhase phase) {
        assertThat(service.validateTransition(phase, phase))
                .as("validateTransition(%s, %s) (same phase) must be false", phase, phase)
                .isFalse();
    }

    /**
     * Non-adjacent jumps (V1↔V3) are always invalid.
     */
    @Example
    @Label("non-adjacent jumps V1<->V3 are invalid")
    void nonAdjacentJumpsAreInvalid() {
        assertThat(service.validateTransition(HostingPhase.V1, HostingPhase.V3)).isFalse();
        assertThat(service.validateTransition(HostingPhase.V3, HostingPhase.V1)).isFalse();
    }

    // ── Property 2: downgrade disables exactly current-minus-target capabilities ──

    /**
     * Feature: amazon-ads-ai-hosting-system, Property 42: Phase transition validity and downgrade cleanup
     *
     * <p><b>Validates: Requirements 17.5, 17.6</b></p>
     *
     * <p>For any downgrade (target ordinal strictly below current), the disabled capability set is
     * exactly the set-difference {@code current.capabilities() \ target.capabilities()}.</p>
     */
    @Property
    @Label("downgrade disables exactly the capabilities present in current but absent in target")
    void downgradeDisablesExactlyTheLostCapabilities(
            @ForAll("downgradePairs") Tuple.Tuple2<HostingPhase, HostingPhase> pair) {

        HostingPhase current = pair.get1();
        HostingPhase target = pair.get2();

        Set<HostingAdjustmentType> expected = EnumSet.copyOf(current.capabilities());
        expected.removeAll(target.capabilities());

        Set<HostingAdjustmentType> actual = service.computeDisabledCapabilities(current, target);

        assertThat(actual)
                .as("downgrade %s -> %s should disable exactly current\\target", current, target)
                .isEqualTo(expected);
        // A downgrade must always disable at least one capability.
        assertThat(actual)
                .as("a real downgrade %s -> %s must disable something", current, target)
                .isNotEmpty();
    }

    // ── Property 3: upgrade disables nothing ──────────────────────────────────────

    /**
     * Feature: amazon-ads-ai-hosting-system, Property 42: Phase transition validity and downgrade cleanup
     *
     * <p><b>Validates: Requirements 17.5, 17.6</b></p>
     *
     * <p>For any upgrade (target ordinal at or above current), {@code computeDisabledCapabilities}
     * returns an empty set — no capability is ever lost when moving up.</p>
     */
    @Property
    @Label("upgrade (or same/higher phase) disables no capabilities")
    void upgradeDisablesNothing(
            @ForAll HostingPhase current,
            @ForAll HostingPhase target) {

        Assume.that(PhaseConfigurationServiceImpl.phaseOrdinal(target)
                >= PhaseConfigurationServiceImpl.phaseOrdinal(current));

        assertThat(service.computeDisabledCapabilities(current, target))
                .as("upgrade %s -> %s should disable nothing", current, target)
                .isEmpty();
    }

    // ── Property 4: adjacency validity is symmetric ───────────────────────────────

    /**
     * Feature: amazon-ads-ai-hosting-system, Property 42: Phase transition validity and downgrade cleanup
     *
     * <p><b>Validates: Requirements 17.5, 17.6</b></p>
     *
     * <p>The transition-validity relation is symmetric: {@code validateTransition(a, b)} equals
     * {@code validateTransition(b, a)} for all phase pairs. In particular both directions of an
     * adjacent pair are valid, and both directions of a non-adjacent pair are invalid.</p>
     */
    @Property
    @Label("transition validity is symmetric across both directions")
    void transitionValidityIsSymmetric(
            @ForAll HostingPhase a,
            @ForAll HostingPhase b) {

        assertThat(service.validateTransition(a, b))
                .as("validateTransition must be symmetric for (%s, %s)", a, b)
                .isEqualTo(service.validateTransition(b, a));
    }

    // ── Generators ────────────────────────────────────────────────────────────────

    /**
     * Generates ordered (current, target) phase pairs that represent a real downgrade:
     * target ordinal strictly below current ordinal. Yields (V2,V1), (V3,V2), (V3,V1).
     */
    @Provide
    Arbitrary<Tuple.Tuple2<HostingPhase, HostingPhase>> downgradePairs() {
        return Arbitraries.of(HostingPhase.values())
                .flatMap(current -> Arbitraries.of(HostingPhase.values())
                        .filter(target -> PhaseConfigurationServiceImpl.phaseOrdinal(target)
                                < PhaseConfigurationServiceImpl.phaseOrdinal(current))
                        .map(target -> Tuple.of(current, target)));
    }
}
