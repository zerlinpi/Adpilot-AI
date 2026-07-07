package com.adpilot.modules.advertising.support;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Label;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;

import java.util.Locale;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property-based test for the Object_Status vocabulary normalization performed by
 * {@link ObjectStatusVocabulary#normalize}.
 *
 * <p>Feature: advertising-workspace-rework, Property 38: Object_Status vocabulary normalization with
 * exception list.
 *
 * <p>Validates: Requirements 16.1, 16.5, 16.6, 16.7.
 *
 * <p><em>For any</em> stored Object_Status value, a known legacy value normalizes per the fixed
 * mapping ({@code active→enabled}, {@code paused→paused}, {@code archived→archived}) and an unknown
 * value is flagged for the migration exception list and never auto-mapped to a canonical value.
 *
 * <p>The oracle is independent of the production classifier: the generators carry the intent (which
 * canonical form a known value should map to, or that a value is deliberately outside the recognized
 * vocabulary), so the expected outcome is computed from that intent rather than by re-running the
 * normalize logic.
 */
@Tag("pbt")
@Label("Feature: advertising-workspace-rework, Property 38: Object_Status vocabulary normalization with exception list")
class ObjectStatusVocabularyNormalizationPropertyTest {

    private static final Set<String> CANONICAL = Set.of("enabled", "paused", "archived");

    /**
     * Feature: advertising-workspace-rework, Property 38: Object_Status vocabulary normalization with
     * exception list.
     *
     * <p>Validates: Requirements 16.1, 16.7.
     *
     * <p>A value already stored in its exact canonical form ({@code enabled|paused|archived}) is kept
     * UNCHANGED — never rewritten and never flagged — so the canonical vocabulary is a fixed point of
     * normalization.
     */
    @Property(tries = 200)
    void exactCanonicalValuesAreKeptUnchanged(@ForAll("canonicalValues") String canonical) {
        ObjectStatusMigrationOutcome outcome = ObjectStatusVocabulary.normalize(canonical);

        assertThat(outcome.kind()).isEqualTo(ObjectStatusMigrationOutcome.Kind.UNCHANGED);
        assertThat(outcome.value()).isEqualTo(canonical);
        assertThat(outcome.isFlagged()).isFalse();
        assertThat(ObjectStatusVocabulary.toCanonical(canonical)).contains(canonical);
    }

    /**
     * Feature: advertising-workspace-rework, Property 38: Object_Status vocabulary normalization with
     * exception list.
     *
     * <p>Validates: Requirements 16.5, 16.7.
     *
     * <p>Any recognized legacy value, in any casing and with surrounding whitespace, normalizes to its
     * canonical form via the fixed mapping {@code active→enabled}, {@code paused→paused},
     * {@code archived→archived}. The resulting value is always one of the three canonical values and
     * is never flagged.
     */
    @Property(tries = 200)
    void knownLegacyValuesNormalizeToCanonicalForm(@ForAll("legacyInputs") LegacyInput input) {
        ObjectStatusMigrationOutcome outcome = ObjectStatusVocabulary.normalize(input.raw());

        assertThat(outcome.isFlagged()).isFalse();
        assertThat(outcome.value()).isEqualTo(input.expectedCanonical());
        assertThat(CANONICAL).contains(outcome.value());
        assertThat(ObjectStatusVocabulary.toCanonical(input.raw())).contains(input.expectedCanonical());

        // active is the only legacy value whose canonical form differs from its stored form, so any
        // non-exact-canonical legacy input is a write (NORMALIZED); an exact canonical input is kept.
        if (ObjectStatusVocabulary.isCanonical(input.raw())) {
            assertThat(outcome.kind()).isEqualTo(ObjectStatusMigrationOutcome.Kind.UNCHANGED);
        } else {
            assertThat(outcome.kind()).isEqualTo(ObjectStatusMigrationOutcome.Kind.NORMALIZED);
            assertThat(outcome.isWrite()).isTrue();
        }
    }

    /**
     * Feature: advertising-workspace-rework, Property 38: Object_Status vocabulary normalization with
     * exception list.
     *
     * <p>Validates: Requirements 16.6, 16.7.
     *
     * <p>An unknown or unrecognized value (including a blank string) is FLAGGED for the migration
     * exception list and is NEVER auto-mapped to {@code enabled} or any other canonical value: the
     * outcome carries no value and a non-empty reason, and {@link ObjectStatusVocabulary#toCanonical}
     * returns empty.
     */
    @Property(tries = 200)
    void unknownValuesAreFlaggedAndNeverAutoMapped(@ForAll("unknownValues") String unknown) {
        ObjectStatusMigrationOutcome outcome = ObjectStatusVocabulary.normalize(unknown);

        assertThat(outcome.isFlagged()).isTrue();
        assertThat(outcome.kind()).isEqualTo(ObjectStatusMigrationOutcome.Kind.FLAGGED);
        assertThat(outcome.value()).isNull();
        assertThat(outcome.reason()).isNotBlank();
        assertThat(outcome.isWrite()).isFalse();
        assertThat(ObjectStatusVocabulary.toCanonical(unknown)).isEmpty();
    }

    // --- Generators ---------------------------------------------------------------------------

    @Provide
    Arbitrary<String> canonicalValues() {
        return Arbitraries.of("enabled", "paused", "archived");
    }

    /**
     * A recognized legacy value paired with the canonical form it must normalize to. The raw form
     * randomly varies casing and adds surrounding whitespace to exercise the trim/lowercase matching
     * without changing which legacy key it resolves to.
     */
    @Provide
    Arbitrary<LegacyInput> legacyInputs() {
        Arbitrary<String> base = Arbitraries.of("active", "enabled", "paused", "archived");
        Arbitrary<String> leadPad = Arbitraries.of("", " ", "  ", "\t");
        Arbitrary<String> trailPad = Arbitraries.of("", " ", "  ", "\t");
        Arbitrary<Integer> casing = Arbitraries.integers().between(0, 2);
        return Combinators.combine(base, leadPad, trailPad, casing).as((value, lead, trail, c) -> {
            String cased = switch (c) {
                case 0 -> value.toLowerCase(Locale.ROOT);
                case 1 -> value.toUpperCase(Locale.ROOT);
                default -> Character.toUpperCase(value.charAt(0)) + value.substring(1);
            };
            String canonical = value.equals("active") ? "enabled" : value;
            return new LegacyInput(lead + cased + trail, canonical);
        });
    }

    /**
     * Values that are NOT in the canonical vocabulary nor the legacy mapping after trim+lowercase,
     * including blank strings. Generated strings are filtered to guarantee they are genuinely unknown.
     */
    @Provide
    Arbitrary<String> unknownValues() {
        Arbitrary<String> blanks = Arbitraries.of("", " ", "   ", "\t", "\n");
        Arbitrary<String> arbitrary = Arbitraries.strings()
                .withCharRange('\u0020', '\u007E')
                .ofMinLength(1)
                .ofMaxLength(24)
                .filter(s -> {
                    String key = s.trim().toLowerCase(Locale.ROOT);
                    return !key.isEmpty()
                            && !ObjectStatusVocabulary.LEGACY_MAPPING.containsKey(key)
                            && !CANONICAL.contains(key);
                });
        return Arbitraries.oneOf(blanks, arbitrary);
    }

    /** A recognized legacy raw input paired with the canonical value it must normalize to. */
    record LegacyInput(String raw, String expectedCanonical) {
    }
}
