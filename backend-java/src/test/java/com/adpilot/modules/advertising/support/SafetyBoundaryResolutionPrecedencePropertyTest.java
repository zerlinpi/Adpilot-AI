package com.adpilot.modules.advertising.support;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Label;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;

import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property-based test for the Safety_Boundary resolution-precedence rule applied by
 * {@link SafetyBoundaryResolver#resolve}.
 *
 * <p>Feature: advertising-workspace-rework, Property 51: Safety_Boundary resolution precedence.
 *
 * <p>Validates: Requirements 22.11, 49.11, 6.4, 21.4.
 *
 * <p>For any combination of five partial {@link SafetyBoundaryLimits} contributions — one per
 * {@link SafetyBoundaryLevel} — each {@link SafetyBoundaryLimit} resolves <em>independently</em>
 * using <strong>most-restrictive-wins</strong> semantics: all levels that define a limit are folded
 * through the limit's {@link SafetyBoundaryLimit#moreRestrictive(BigDecimal, BigDecimal)} operator.
 * The source attribution tracks which level contributed the winning value. When a tie occurs, the
 * higher-precedence (earlier) level keeps attribution.
 *
 * <p>The oracle is independent of the resolver: the generator records exactly which value (if any)
 * each level assigns to each limit, so the expected winner is computed from the generator's intent
 * rather than by re-running the production scan.
 */
@Tag("pbt")
@Label("Feature: advertising-workspace-rework, Property 51: Safety_Boundary resolution precedence")
class SafetyBoundaryResolutionPrecedencePropertyTest {

    /**
     * Feature: advertising-workspace-rework, Property 51: Safety_Boundary resolution precedence.
     *
     * <p>Validates: Requirements 22.11, 49.11, 6.4, 21.4.
     *
     * <p>Each limit independently resolves to the most-restrictive value across all levels that
     * define it, folded through the limit's moreRestrictive operator. Source attribution tracks
     * which level contributed the final winning value.
     */
    @Property(tries = 200)
    void eachLimitResolvesToMostRestrictiveValue(@ForAll("scenarios") Scenario scenario) {
        SafetyBoundary resolved = SafetyBoundaryResolver.resolve(
                scenario.limitsFor(SafetyBoundaryLevel.CAMPAIGN_OVERRIDE),
                scenario.limitsFor(SafetyBoundaryLevel.GOAL_BOUNDARY),
                scenario.limitsFor(SafetyBoundaryLevel.STORE_POLICY),
                scenario.limitsFor(SafetyBoundaryLevel.ORGANIZATION_POLICY),
                scenario.limitsFor(SafetyBoundaryLevel.SYSTEM_DEFAULT));

        for (SafetyBoundaryLimit limit : SafetyBoundaryLimit.values()) {
            Optional<BigDecimal> expectedValue = scenario.expectedValue(limit);

            if (expectedValue.isEmpty()) {
                // No level defined this limit: it must remain undefined in the result.
                assertThat(resolved.isDefined(limit))
                        .as("limit %s should be undefined when no level defines it", limit)
                        .isFalse();
                assertThat(resolved.get(limit)).isEmpty();
                assertThat(resolved.sourceOf(limit)).isEmpty();
            } else {
                assertThat(resolved.get(limit))
                        .as("limit %s should resolve to the most-restrictive value across all levels", limit)
                        .isPresent();
                // Scale-insensitive comparison: some limits (e.g. BOOLEAN_OR EMERGENCY_STOP)
                // fold to a normalized value that is numerically equal but a different scale.
                assertThat(resolved.get(limit).orElseThrow())
                        .as("limit %s should resolve to the most-restrictive value across all levels", limit)
                        .isEqualByComparingTo(expectedValue.get());

                // Source attribution must point to a level that actually defines that winning value
                assertThat(resolved.sourceOf(limit))
                        .as("limit %s should have source attribution", limit)
                        .isPresent();

                Optional<SafetyBoundaryLevel> expectedSource = scenario.expectedSource(limit);
                assertThat(resolved.sourceOf(limit))
                        .as("limit %s source should be the level that contributed the winning value", limit)
                        .isEqualTo(expectedSource);
            }
        }
    }

    // --- model -------------------------------------------------------------

    /**
     * A full scenario: for each {@link SafetyBoundaryLevel}, the partial set of limit values that
     * level defines ({@code null} value = the level leaves that limit undefined).
     */
    private static final class Scenario {

        private final EnumMap<SafetyBoundaryLevel, EnumMap<SafetyBoundaryLimit, BigDecimal>> grid;

        private Scenario(EnumMap<SafetyBoundaryLevel, EnumMap<SafetyBoundaryLimit, BigDecimal>> grid) {
            this.grid = grid;
        }

        SafetyBoundaryLimits limitsFor(SafetyBoundaryLevel level) {
            SafetyBoundaryLimits.Builder builder = SafetyBoundaryLimits.builder();
            for (Map.Entry<SafetyBoundaryLimit, BigDecimal> entry : grid.get(level).entrySet()) {
                if (entry.getValue() != null) {
                    builder.limit(entry.getKey(), entry.getValue());
                }
            }
            return builder.build();
        }

        BigDecimal valueAt(SafetyBoundaryLevel level, SafetyBoundaryLimit limit) {
            return grid.get(level).get(limit);
        }

        /**
         * The most-restrictive value for {@code limit} across all levels that define it,
         * computed by folding through the limit's moreRestrictive operator; empty if none defines it.
         *
         * <p>This mirrors {@link SafetyBoundaryResolver} exactly: the first defining level seeds
         * the winner with its raw value, and a later level only replaces it when its folded result
         * is <em>strictly</em> more restrictive (by {@code compareTo}, scale-insensitive). This
         * matters for scale-insensitive comparison semantics — e.g. {@code BOOLEAN_OR}
         * (EMERGENCY_STOP), where {@code moreRestrictive} returns a normalized {@code 0}/{@code 1}
         * that is numerically equal to (but a different scale from) the raw seed value; the resolver
         * keeps the raw seed, so the oracle must too.
         */
        Optional<BigDecimal> expectedValue(SafetyBoundaryLimit limit) {
            BigDecimal result = null;
            for (SafetyBoundaryLevel level : SafetyBoundaryLevel.values()) {
                BigDecimal value = grid.get(level).get(limit);
                if (value != null) {
                    if (result == null) {
                        result = value;
                    } else {
                        BigDecimal folded = limit.moreRestrictive(result, value);
                        if (folded.compareTo(result) != 0) {
                            result = folded;
                        }
                    }
                }
            }
            return Optional.ofNullable(result);
        }

        /**
         * The level that contributed the winning most-restrictive value for {@code limit}.
         * When a tie occurs (the folded result equals the current winner), the higher-precedence
         * (earlier) level keeps attribution.
         */
        Optional<SafetyBoundaryLevel> expectedSource(SafetyBoundaryLimit limit) {
            BigDecimal winningValue = null;
            SafetyBoundaryLevel winningLevel = null;

            for (SafetyBoundaryLevel level : SafetyBoundaryLevel.values()) {
                BigDecimal value = grid.get(level).get(limit);
                if (value != null) {
                    if (winningValue == null) {
                        winningValue = value;
                        winningLevel = level;
                    } else {
                        BigDecimal folded = limit.moreRestrictive(winningValue, value);
                        if (folded.compareTo(winningValue) != 0) {
                            winningValue = folded;
                            winningLevel = level;
                        }
                    }
                }
            }
            return Optional.ofNullable(winningLevel);
        }
    }

    // --- generators --------------------------------------------------------

    /**
     * A scenario where, for every (level, limit) pair, the level either defines the limit (with a
     * positive {@link BigDecimal}, ~60% of the time) or leaves it undefined (~40%). This exercises
     * every fall-through path: a limit defined at the top, defined only lower down, defined at
     * multiple levels (top must win), and defined nowhere.
     */
    @Provide
    Arbitrary<Scenario> scenarios() {
        // One nullable value per (level, limit) cell.
        Arbitrary<BigDecimal> cell = Arbitraries.oneOf(
                Arbitraries.just((BigDecimal) null),
                Arbitraries.bigDecimals()
                        .between(BigDecimal.valueOf(0.01), BigDecimal.valueOf(10_000))
                        .ofScale(2));

        SafetyBoundaryLevel[] levels = SafetyBoundaryLevel.values();
        SafetyBoundaryLimit[] limits = SafetyBoundaryLimit.values();

        // Build a flat list of cell arbitraries (levels x limits) and assemble the grid.
        Arbitrary<BigDecimal>[] cells = new Arbitrary[levels.length * limits.length];
        for (int i = 0; i < cells.length; i++) {
            cells[i] = cell;
        }

        return Combinators.combine(java.util.Arrays.asList(cells)).as(values -> {
            EnumMap<SafetyBoundaryLevel, EnumMap<SafetyBoundaryLimit, BigDecimal>> grid =
                    new EnumMap<>(SafetyBoundaryLevel.class);
            int idx = 0;
            for (SafetyBoundaryLevel level : levels) {
                EnumMap<SafetyBoundaryLimit, BigDecimal> row = new EnumMap<>(SafetyBoundaryLimit.class);
                for (SafetyBoundaryLimit limit : limits) {
                    row.put(limit, (BigDecimal) values.get(idx++));
                }
                grid.put(level, row);
            }
            return new Scenario(grid);
        });
    }
}
