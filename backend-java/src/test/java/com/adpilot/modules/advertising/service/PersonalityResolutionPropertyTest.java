package com.adpilot.modules.advertising.service;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property-based test for the precedence rule applied by {@link PersonalityResolver#resolve}.
 *
 * <p>Feature: advertising-workspace-rework, Property 48: AI_Personality resolution precedence.
 *
 * <p>Validates: Requirements 49.2, 49.3.
 *
 * <p>For any combination of (Campaign_Personality override, Goal_Personality_Default,
 * Store_Default_Personality), the resolved effective AI_Personality is the FIRST level that carries
 * a recognized canonical value in the order Campaign override &rarr; Goal default &rarr; Store
 * default, falling back to {@code balanced} ({@link AiPersonality#SYSTEM_FALLBACK}) when no level is
 * defined. A {@code null}, blank, or non-canonical value at a level is treated as "not set" and
 * falls through to the next level.
 *
 * <p>The oracle is independent of {@link AiPersonality#parse}: each generated {@link Level} carries
 * the raw string fed to the resolver alongside the canonical value (if any) deliberately encoded in
 * it, so the expected outcome is computed from the generator's intent rather than from the
 * production parser.
 */
class PersonalityResolutionPropertyTest {

    /** The pure {@code resolve} method does no I/O, so the mappers are irrelevant here. */
    private final PersonalityResolver resolver = new PersonalityResolver(null, null, null);

    /**
     * Feature: advertising-workspace-rework, Property 48: AI_Personality resolution precedence.
     *
     * <p>Validates: Requirements 49.2, 49.3.
     *
     * <p>{@code resolve} returns the first level carrying a canonical value in the order Campaign &gt;
     * Goal &gt; Store, and falls back to {@code balanced} when none is defined.
     */
    @Property(tries = 200)
    void resolvesToFirstDefinedLevelElseBalanced(
            @ForAll("levels") Level campaign,
            @ForAll("levels") Level goal,
            @ForAll("levels") Level store) {

        AiPersonality expected = Optional.ofNullable(campaign.canonical)
                .or(() -> Optional.ofNullable(goal.canonical))
                .or(() -> Optional.ofNullable(store.canonical))
                .orElse(AiPersonality.SYSTEM_FALLBACK);

        AiPersonality actual = resolver.resolve(campaign.raw, goal.raw, store.raw);

        assertThat(actual).isEqualTo(expected);
    }

    // --- model -------------------------------------------------------------

    /**
     * A single precedence level: the {@code raw} string handed to the resolver, plus the
     * {@code canonical} personality deliberately encoded in it ({@code null} when the level is
     * intentionally unset, blank, or non-canonical).
     */
    private record Level(String raw, AiPersonality canonical) {}

    // --- generators --------------------------------------------------------

    /**
     * A level value that is, with roughly equal weight, one of: a canonical value (with random
     * casing and surrounding whitespace, which must still parse), {@code null}, a blank string, or a
     * non-canonical "garbage" string. This exercises every fall-through path of the precedence rule.
     */
    @Provide
    Arbitrary<Level> levels() {
        return Arbitraries.oneOf(canonicalLevels(), unsetLevels(), garbageLevels());
    }

    /** A recognized canonical value, perturbed with casing and surrounding whitespace. */
    private Arbitrary<Level> canonicalLevels() {
        Arbitrary<AiPersonality> personality = Arbitraries.of(AiPersonality.values());
        Arbitrary<String> leftPad = Arbitraries.strings().withChars(' ', '\t').ofMaxLength(3);
        Arbitrary<String> rightPad = Arbitraries.strings().withChars(' ', '\t').ofMaxLength(3);
        Arbitrary<Boolean> upper = Arbitraries.of(true, false);
        return Combinators.combine(personality, leftPad, rightPad, upper)
                .as((p, left, right, up) -> {
                    String value = up ? p.machineValue().toUpperCase() : p.machineValue();
                    return new Level(left + value + right, p);
                });
    }

    /** {@code null} or a blank string: an unset level. */
    private Arbitrary<Level> unsetLevels() {
        Arbitrary<String> blank = Arbitraries.strings().withChars(' ', '\t', '\n').ofMaxLength(4);
        return Arbitraries.oneOf(
                Arbitraries.just(new Level(null, null)),
                blank.map(s -> new Level(s, null)));
    }

    /** A non-blank string that is not one of the three canonical machine values. */
    private Arbitrary<Level> garbageLevels() {
        return Arbitraries.strings()
                .withCharRange('a', 'z')
                .withChars('稳', '健', '型', '_', '-', '0', '9', ' ')
                .ofMinLength(1)
                .ofMaxLength(20)
                .filter(s -> AiPersonality.parse(s).isEmpty())
                .map(s -> new Level(s, null));
    }
}
