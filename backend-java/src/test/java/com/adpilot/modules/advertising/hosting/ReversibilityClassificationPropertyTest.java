package com.adpilot.modules.advertising.hosting;

import com.adpilot.modules.advertising.service.HostingAdjustmentType;
import net.jqwik.api.*;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property-based test for the {@link ReversibilityClassifier} and
 * {@link CandidateDecision#isReversible()} delegation.
 *
 * <p>Feature: amazon-ads-ai-hosting-system, Property 32: Reversibility classification
 *
 * <p><b>Validates: Requirements 5.11, 10.3</b>
 *
 * <p>Properties tested:
 * <ol>
 *   <li>For any bid/budget/state changeType, the classifier always returns reversible=true</li>
 *   <li>For any keyword/negative_keyword changeType, the classifier always returns reversible=false</li>
 *   <li>For any unknown, null, or blank changeType, the classifier defaults to non-reversible (fail-safe)</li>
 *   <li>The classification is deterministic — same input always produces same output</li>
 *   <li>CandidateDecision.isReversible() delegates correctly to the classifier based on its changeType</li>
 * </ol>
 */
@Tag("pbt")
@Label("Feature: amazon-ads-ai-hosting-system, Property 32: Reversibility classification")
class ReversibilityClassificationPropertyTest {

    private final ReversibilityClassifier classifier = new ReversibilityClassifier();

    // ── Property 1: Reversible change types always return true ─────────────────────

    /**
     * Feature: amazon-ads-ai-hosting-system, Property 32: Reversibility classification
     *
     * <p><b>Validates: Requirements 5.11, 10.3</b></p>
     *
     * <p>For any bid, budget, or state changeType the classifier returns {@code true}
     * (these operations can be rolled back to their original value).</p>
     */
    @Property(tries = 200)
    @Label("bid/budget/state changeTypes are always reversible")
    void reversibleChangeTypesAlwaysReturnTrue(
            @ForAll("reversibleChangeTypes") String changeType) {

        assertThat(classifier.isReversible(changeType))
                .as("changeType '%s' should be reversible", changeType)
                .isTrue();
        assertThat(ReversibilityClassifier.classify(changeType))
                .as("static classify('%s') should also be true", changeType)
                .isTrue();
    }

    // ── Property 2: Non-reversible change types always return false ────────────────

    /**
     * Feature: amazon-ads-ai-hosting-system, Property 32: Reversibility classification
     *
     * <p><b>Validates: Requirements 5.11, 10.3</b></p>
     *
     * <p>For any keyword or negative_keyword changeType the classifier returns {@code false}
     * (newly created keywords/negatives cannot be "uncreated").</p>
     */
    @Property(tries = 200)
    @Label("keyword/negative_keyword changeTypes are never reversible")
    void nonReversibleChangeTypesAlwaysReturnFalse(
            @ForAll("nonReversibleChangeTypes") String changeType) {

        assertThat(classifier.isReversible(changeType))
                .as("changeType '%s' should NOT be reversible", changeType)
                .isFalse();
        assertThat(ReversibilityClassifier.classify(changeType))
                .as("static classify('%s') should also be false", changeType)
                .isFalse();
    }

    // ── Property 3: Unknown, null, or blank changeTypes default to non-reversible ──

    /**
     * Feature: amazon-ads-ai-hosting-system, Property 32: Reversibility classification
     *
     * <p><b>Validates: Requirements 5.11, 10.3</b></p>
     *
     * <p>For any unknown, null, or blank changeType, the classifier defaults to
     * non-reversible (fail-safe behavior).</p>
     */
    @Property(tries = 200)
    @Label("unknown/null/blank changeTypes default to non-reversible (fail-safe)")
    void unknownNullOrBlankChangeTypesAreNonReversible(
            @ForAll("unknownChangeTypes") String changeType) {

        assertThat(classifier.isReversible(changeType))
                .as("unknown changeType '%s' should default to non-reversible", changeType)
                .isFalse();
    }

    /**
     * Null input specifically defaults to non-reversible.
     */
    @Example
    @Label("null changeType defaults to non-reversible")
    void nullChangeTypeIsNonReversible() {
        assertThat(classifier.isReversible(null)).isFalse();
        assertThat(ReversibilityClassifier.classify(null)).isFalse();
    }

    // ── Property 4: Classification is deterministic ───────────────────────────────

    /**
     * Feature: amazon-ads-ai-hosting-system, Property 32: Reversibility classification
     *
     * <p><b>Validates: Requirements 5.11, 10.3</b></p>
     *
     * <p>The classification is deterministic: calling the classifier multiple times
     * with the same input always produces the same output.</p>
     */
    @Property(tries = 200)
    @Label("classification is deterministic - same input always produces same output")
    void classificationIsDeterministic(
            @ForAll("allChangeTypes") String changeType) {

        boolean first = classifier.isReversible(changeType);
        boolean second = classifier.isReversible(changeType);
        boolean third = classifier.isReversible(changeType);

        assertThat(first)
                .as("Repeated classification of '%s' must be deterministic", changeType)
                .isEqualTo(second)
                .isEqualTo(third);

        // Also verify static method is deterministic and consistent with instance method
        boolean staticResult = ReversibilityClassifier.classify(changeType);
        assertThat(staticResult)
                .as("Static classify must agree with instance isReversible for '%s'", changeType)
                .isEqualTo(first);
    }

    // ── Property 5: CandidateDecision.isReversible() delegates correctly ──────────

    /**
     * Feature: amazon-ads-ai-hosting-system, Property 32: Reversibility classification
     *
     * <p><b>Validates: Requirements 5.11, 10.3</b></p>
     *
     * <p>CandidateDecision.isReversible() delegates correctly to the
     * ReversibilityClassifier based on its changeType field.</p>
     */
    @Property(tries = 200)
    @Label("CandidateDecision.isReversible() delegates to ReversibilityClassifier.classify(changeType)")
    void candidateDecisionDelegatesToClassifier(
            @ForAll("knownChangeTypes") String changeType) {

        CandidateDecision candidate = buildCandidateWithChangeType(changeType);

        boolean expected = ReversibilityClassifier.classify(changeType);
        boolean actual = candidate.isReversible();

        assertThat(actual)
                .as("CandidateDecision.isReversible() must equal ReversibilityClassifier.classify('%s')", changeType)
                .isEqualTo(expected);
    }

    // ── Generators ────────────────────────────────────────────────────────────────

    /**
     * Generates reversible change types: bid, budget, state.
     */
    @Provide
    Arbitrary<String> reversibleChangeTypes() {
        return Arbitraries.of(
                ReversibilityClassifier.CHANGE_TYPE_BID,
                ReversibilityClassifier.CHANGE_TYPE_BUDGET,
                ReversibilityClassifier.CHANGE_TYPE_STATE
        );
    }

    /**
     * Generates non-reversible change types: keyword, negative_keyword.
     */
    @Provide
    Arbitrary<String> nonReversibleChangeTypes() {
        return Arbitraries.of(
                ReversibilityClassifier.CHANGE_TYPE_KEYWORD,
                ReversibilityClassifier.CHANGE_TYPE_NEGATIVE_KEYWORD
        );
    }

    /**
     * Generates unknown change types that are neither null nor any of the recognized types.
     * These should all default to non-reversible (fail-safe).
     */
    @Provide
    Arbitrary<String> unknownChangeTypes() {
        return Arbitraries.oneOf(
                // blank strings
                Arbitraries.of("", "   ", "\t", "\n"),
                // arbitrary non-matching strings
                Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(20)
                        .filter(s -> !s.equals("bid") && !s.equals("budget")
                                && !s.equals("state") && !s.equals("keyword")
                                && !s.equals("negative_keyword"))
        );
    }

    /**
     * Generates all known change types (reversible + non-reversible).
     */
    @Provide
    Arbitrary<String> knownChangeTypes() {
        return Arbitraries.of(
                ReversibilityClassifier.CHANGE_TYPE_BID,
                ReversibilityClassifier.CHANGE_TYPE_BUDGET,
                ReversibilityClassifier.CHANGE_TYPE_STATE,
                ReversibilityClassifier.CHANGE_TYPE_KEYWORD,
                ReversibilityClassifier.CHANGE_TYPE_NEGATIVE_KEYWORD
        );
    }

    /**
     * Generates all possible changeType inputs including known types, unknowns, and blanks
     * (excludes null since jqwik handles null separately).
     */
    @Provide
    Arbitrary<String> allChangeTypes() {
        return Arbitraries.oneOf(
                knownChangeTypes(),
                unknownChangeTypes()
        );
    }

    // ── Helpers ───────────────────────────────────────────────────────────────────

    private CandidateDecision buildCandidateWithChangeType(String changeType) {
        return new CandidateDecision(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                "keyword",
                UUID.randomUUID(),
                "bid",
                HostingAdjustmentType.BID,
                changeType,
                CandidateDecision.ENGINE_V1_BID,
                BigDecimal.ONE,
                BigDecimal.TEN,
                BigDecimal.valueOf(0.3),
                BigDecimal.valueOf(0.7),
                BigDecimal.valueOf(0.8),
                "{}"
        );
    }
}
