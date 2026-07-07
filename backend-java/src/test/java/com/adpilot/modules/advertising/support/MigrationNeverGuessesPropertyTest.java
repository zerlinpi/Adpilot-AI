package com.adpilot.modules.advertising.support;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Label;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property-based test for the migration-never-guessing guarantee shared by the three pure migration
 * classifiers {@link AcosScale#migrate}, {@link ObjectStatusVocabulary#normalize}, and
 * {@link OptimizationGoalVocabulary#normalize}.
 *
 * <p>Feature: advertising-workspace-rework, Property 40: Migration never guesses unknown or ambiguous
 * values.
 *
 * <p>Validates: Requirements 17.5, 17.6, 16.6, 57.1, 57.2, 57.3.
 *
 * <p><em>For any</em> historical value the migration cannot safely classify — an ACoS value whose
 * column scale is unknown (or whose value contradicts the declared scale), an Object_Status value
 * outside the canonical vocabulary and legacy mapping, or an Optimization_Goal value with no
 * unambiguous correspondence to one of the four enums — the classifier FLAGS it for the migration
 * exception list and NEVER auto-maps it to a canonical value: the outcome carries a non-blank reason,
 * no resulting value, and is not a write.
 *
 * <p>The oracle is independent of the production classifiers: each generator carries the intent that
 * a value is deliberately unknown/ambiguous (constructed or filtered to lie outside every recognized
 * vocabulary/scale rule), so the expected "flag, never guess" outcome is computed from that intent
 * rather than by re-running the classifier under test.
 *
 * <p>These exercise the pure helpers directly — no Spring context is needed because every classifier
 * deterministically maps its input to an immutable outcome value object.
 */
@Tag("pbt")
@Label("Feature: advertising-workspace-rework, Property 40: Migration never guesses unknown or ambiguous values")
class MigrationNeverGuessesPropertyTest {

    private static final Set<String> OBJECT_STATUS_CANONICAL = Set.of("enabled", "paused", "archived");
    private static final Set<String> OPTIMIZATION_GOAL_CANONICAL =
            Set.of("profit_first", "sales_growth", "rank", "clearance");

    // --- ACoS migration (Req 17.5, 17.6) ----------------------------------------------------------

    /**
     * Feature: advertising-workspace-rework, Property 40: Migration never guesses unknown or ambiguous
     * values.
     *
     * <p>Validates: Requirements 17.5, 17.6.
     *
     * <p>An ACoS value the per-column classifier cannot safely convert — a non-zero value under an
     * UNKNOWN column scale, or a value that contradicts its declared scale (negative, or implausibly
     * large) — is flagged AMBIGUOUS for manual review and is NEVER guessed onto the decimal-ratio
     * scale: the outcome carries no value, a non-blank reason, and is not a write.
     */
    @Property(tries = 200)
    void acosAmbiguousValuesAreFlaggedAndNeverGuessed(@ForAll("acosAmbiguousInputs") AcosAmbiguousInput input) {
        AcosMigrationOutcome outcome = AcosScale.migrate(input.raw(), input.scale());

        assertThat(outcome.isAmbiguous()).isTrue();
        assertThat(outcome.kind()).isEqualTo(AcosMigrationOutcome.Kind.AMBIGUOUS);
        assertThat(outcome.value()).isNull();
        assertThat(outcome.reason()).isNotBlank();
        assertThat(outcome.isWrite()).isFalse();
    }

    // --- Object_Status migration (Req 16.6) -------------------------------------------------------

    /**
     * Feature: advertising-workspace-rework, Property 40: Migration never guesses unknown or ambiguous
     * values.
     *
     * <p>Validates: Requirements 16.6.
     *
     * <p>An Object_Status value outside the canonical vocabulary and the fixed legacy mapping
     * (including a blank string) is FLAGGED for the migration exception list and is NEVER auto-mapped
     * to {@code enabled} or any other canonical value: the outcome carries no value, a non-blank
     * reason, is not a write, and {@link ObjectStatusVocabulary#toCanonical} returns empty.
     */
    @Property(tries = 200)
    void unknownObjectStatusValuesAreFlaggedAndNeverGuessed(@ForAll("unknownObjectStatusValues") String unknown) {
        ObjectStatusMigrationOutcome outcome = ObjectStatusVocabulary.normalize(unknown);

        assertThat(outcome.isFlagged()).isTrue();
        assertThat(outcome.kind()).isEqualTo(ObjectStatusMigrationOutcome.Kind.FLAGGED);
        assertThat(outcome.value()).isNull();
        assertThat(outcome.reason()).isNotBlank();
        assertThat(outcome.isWrite()).isFalse();
        assertThat(ObjectStatusVocabulary.toCanonical(unknown)).isEmpty();
    }

    // --- Optimization_Goal migration (Req 57.1, 57.2, 57.3) ---------------------------------------

    /**
     * Feature: advertising-workspace-rework, Property 40: Migration never guesses unknown or ambiguous
     * values.
     *
     * <p>Validates: Requirements 57.1, 57.2, 57.3.
     *
     * <p>An Optimization_Goal value with no unambiguous correspondence to one of the four canonical
     * enums — including the legacy targeting strategies {@code brand_defense}, {@code competitor}, and
     * {@code category} that are deliberately absent from the fixed mapping — is FLAGGED for the
     * migration exception list and is NEVER auto-mapped to an arbitrary enum: the outcome carries no
     * value, a non-blank reason, is not a write, and {@link OptimizationGoalVocabulary#toCanonical}
     * returns empty.
     */
    @Property(tries = 200)
    void unmappableOptimizationGoalValuesAreFlaggedAndNeverGuessed(
            @ForAll("unmappableOptimizationGoalValues") String unmappable) {
        OptimizationGoalMigrationOutcome outcome = OptimizationGoalVocabulary.normalize(unmappable);

        assertThat(outcome.isFlagged()).isTrue();
        assertThat(outcome.kind()).isEqualTo(OptimizationGoalMigrationOutcome.Kind.FLAGGED);
        assertThat(outcome.value()).isNull();
        assertThat(outcome.reason()).isNotBlank();
        assertThat(outcome.isWrite()).isFalse();
        assertThat(OptimizationGoalVocabulary.toCanonical(unmappable)).isEmpty();
    }

    // --- Worked examples from the requirements ----------------------------------------------------

    /** A non-zero value under an UNKNOWN ACoS column scale is flagged, never guessed (Req 17.6). */
    @Example
    void acosUnknownScaleNonZeroIsFlagged() {
        AcosMigrationOutcome outcome = AcosScale.migrate(new BigDecimal("1.5"), AcosColumnScale.UNKNOWN);

        assertThat(outcome.isAmbiguous()).isTrue();
        assertThat(outcome.value()).isNull();
    }

    /** The legacy targeting strategies are deliberately unmappable and are flagged (Req 57.3). */
    @Example
    void optimizationGoalTargetingStrategiesAreFlagged() {
        for (String strategy : new String[] {"brand_defense", "competitor", "category"}) {
            OptimizationGoalMigrationOutcome outcome = OptimizationGoalVocabulary.normalize(strategy);

            assertThat(outcome.isFlagged()).as("'%s' must be flagged", strategy).isTrue();
            assertThat(outcome.value()).isNull();
            assertThat(OptimizationGoalVocabulary.toCanonical(strategy)).isEmpty();
        }
    }

    /** An unknown Object_Status value is flagged, never mapped to enabled (Req 16.6). */
    @Example
    void objectStatusUnknownIsFlagged() {
        ObjectStatusMigrationOutcome outcome = ObjectStatusVocabulary.normalize("suspended");

        assertThat(outcome.isFlagged()).isTrue();
        assertThat(outcome.value()).isNull();
    }

    // --- Generators -------------------------------------------------------------------------------

    /**
     * ACoS inputs the per-column classifier must treat as ambiguous: a non-zero value under an UNKNOWN
     * scale, a negative or implausibly large value under DECIMAL_RATIO, or a negative value (or one
     * whose ÷100 ratio exceeds the plausible bound) under PERCENT. Each is constructed to violate the
     * declared scale's safe-conversion rule, so the classifier must flag rather than guess.
     */
    @Provide
    Arbitrary<AcosAmbiguousInput> acosAmbiguousInputs() {
        // UNKNOWN scale: any non-zero value is ambiguous (could be a ratio or a percentage).
        Arbitrary<AcosAmbiguousInput> unknownScale = Arbitraries.longs()
                .between(-100_000_000L, 100_000_000L)
                .filter(micros -> micros != 0L)
                .map(micros -> new AcosAmbiguousInput(BigDecimal.valueOf(micros, 6), AcosColumnScale.UNKNOWN));

        // DECIMAL_RATIO: negative values contradict the ratio semantics.
        Arbitrary<AcosAmbiguousInput> negativeRatio = Arbitraries.longs()
                .between(1L, 100_000_000L)
                .map(micros -> new AcosAmbiguousInput(BigDecimal.valueOf(-micros, 6), AcosColumnScale.DECIMAL_RATIO));

        // DECIMAL_RATIO: values above the plausible bound (100) are flagged as likely percentage-scaled.
        Arbitrary<AcosAmbiguousInput> oversizedRatio = Arbitraries.longs()
                .between(101L, 1_000_000L)
                .map(whole -> new AcosAmbiguousInput(new BigDecimal(whole), AcosColumnScale.DECIMAL_RATIO));

        // PERCENT: negative values contradict the percentage semantics.
        Arbitrary<AcosAmbiguousInput> negativePercent = Arbitraries.longs()
                .between(1L, 1_000_000L)
                .map(hundredths -> new AcosAmbiguousInput(BigDecimal.valueOf(-hundredths, 2), AcosColumnScale.PERCENT));

        // PERCENT: a value whose ÷100 ratio exceeds the plausible bound (raw percent > 10,000).
        Arbitrary<AcosAmbiguousInput> oversizedPercent = Arbitraries.longs()
                .between(10_001L, 100_000_000L)
                .map(whole -> new AcosAmbiguousInput(new BigDecimal(whole), AcosColumnScale.PERCENT));

        return Arbitraries.oneOf(
                unknownScale, negativeRatio, oversizedRatio, negativePercent, oversizedPercent);
    }

    /**
     * Object_Status values that are NOT canonical and NOT in the legacy mapping after trim+lowercase,
     * including blank strings. Generated strings are filtered to guarantee they are genuinely unknown.
     */
    @Provide
    Arbitrary<String> unknownObjectStatusValues() {
        Arbitrary<String> blanks = Arbitraries.of("", " ", "   ", "\t", "\n");
        Arbitrary<String> arbitrary = Arbitraries.strings()
                .withCharRange('\u0020', '\u007E')
                .ofMinLength(1)
                .ofMaxLength(24)
                .filter(s -> {
                    String key = s.trim().toLowerCase(Locale.ROOT);
                    return !key.isEmpty()
                            && !ObjectStatusVocabulary.LEGACY_MAPPING.containsKey(key)
                            && !OBJECT_STATUS_CANONICAL.contains(key);
                });
        return Arbitraries.oneOf(blanks, arbitrary);
    }

    /**
     * Optimization_Goal values that are NOT canonical and NOT in the legacy mapping after
     * trim+lowercase, including blank strings and the deliberately-unmapped legacy targeting
     * strategies. Generated strings are filtered to guarantee they are genuinely unmappable.
     */
    @Provide
    Arbitrary<String> unmappableOptimizationGoalValues() {
        Arbitrary<String> targetingStrategies = Arbitraries.of("brand_defense", "competitor", "category");
        Arbitrary<String> blanks = Arbitraries.of("", " ", "   ", "\t", "\n");
        Arbitrary<String> arbitrary = Arbitraries.strings()
                .withCharRange('\u0020', '\u007E')
                .ofMinLength(1)
                .ofMaxLength(24)
                .filter(s -> {
                    String key = s.trim().toLowerCase(Locale.ROOT);
                    return !key.isEmpty()
                            && !OptimizationGoalVocabulary.LEGACY_MAPPING.containsKey(key)
                            && !OPTIMIZATION_GOAL_CANONICAL.contains(key);
                });
        return Arbitraries.oneOf(targetingStrategies, blanks, arbitrary);
    }

    /** An ACoS raw value paired with the column scale under which it is ambiguous. */
    record AcosAmbiguousInput(BigDecimal raw, AcosColumnScale scale) {
    }
}
