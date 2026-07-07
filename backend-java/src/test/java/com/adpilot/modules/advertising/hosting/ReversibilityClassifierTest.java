package com.adpilot.modules.advertising.hosting;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link ReversibilityClassifier}.
 *
 * <p>Validates: Requirements 5.11, 10.3.</p>
 */
@DisplayName("ReversibilityClassifier")
class ReversibilityClassifierTest {

    private final ReversibilityClassifier classifier = new ReversibilityClassifier();

    // --- Reversible operations (can be rolled back) ---

    @Test
    @DisplayName("keyword bid changes are reversible")
    void bidChangesAreReversible() {
        assertThat(classifier.isReversible("bid")).isTrue();
    }

    @Test
    @DisplayName("campaign budget changes are reversible")
    void budgetChangesAreReversible() {
        assertThat(classifier.isReversible("budget")).isTrue();
    }

    @Test
    @DisplayName("campaign state changes are reversible")
    void stateChangesAreReversible() {
        assertThat(classifier.isReversible("state")).isTrue();
    }

    // --- Non-reversible operations (cannot be undone) ---

    @Test
    @DisplayName("keyword additions are NOT reversible")
    void keywordAdditionsAreNotReversible() {
        assertThat(classifier.isReversible("keyword")).isFalse();
    }

    @Test
    @DisplayName("negative keyword additions are NOT reversible")
    void negativeKeywordAdditionsAreNotReversible() {
        assertThat(classifier.isReversible("negative_keyword")).isFalse();
    }

    // --- Edge cases: unknown and null/blank change types default to non-reversible ---

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "unknown", "delete", "BUDGET", "BID"})
    @DisplayName("null, blank, or unknown changeType defaults to non-reversible (fail-safe)")
    void unknownChangeTypesAreNotReversible(String changeType) {
        assertThat(classifier.isReversible(changeType)).isFalse();
    }

    // --- Static classify method (same behavior) ---

    @Test
    @DisplayName("static classify method returns same result as instance method")
    void staticClassifyMatchesInstanceMethod() {
        assertThat(ReversibilityClassifier.classify("bid")).isTrue();
        assertThat(ReversibilityClassifier.classify("budget")).isTrue();
        assertThat(ReversibilityClassifier.classify("state")).isTrue();
        assertThat(ReversibilityClassifier.classify("keyword")).isFalse();
        assertThat(ReversibilityClassifier.classify("negative_keyword")).isFalse();
        assertThat(ReversibilityClassifier.classify(null)).isFalse();
    }

    // --- CandidateDecision.isReversible() integration ---

    @Test
    @DisplayName("CandidateDecision.isReversible() returns true for bid changeType")
    void candidateDecisionBidIsReversible() {
        CandidateDecision candidate = buildCandidateWithChangeType("bid");
        assertThat(candidate.isReversible()).isTrue();
    }

    @Test
    @DisplayName("CandidateDecision.isReversible() returns true for budget changeType")
    void candidateDecisionBudgetIsReversible() {
        CandidateDecision candidate = buildCandidateWithChangeType("budget");
        assertThat(candidate.isReversible()).isTrue();
    }

    @Test
    @DisplayName("CandidateDecision.isReversible() returns false for keyword changeType")
    void candidateDecisionKeywordIsNotReversible() {
        CandidateDecision candidate = buildCandidateWithChangeType("keyword");
        assertThat(candidate.isReversible()).isFalse();
    }

    @Test
    @DisplayName("CandidateDecision.isReversible() returns false for negative_keyword changeType")
    void candidateDecisionNegativeKeywordIsNotReversible() {
        CandidateDecision candidate = buildCandidateWithChangeType("negative_keyword");
        assertThat(candidate.isReversible()).isFalse();
    }

    private CandidateDecision buildCandidateWithChangeType(String changeType) {
        return new CandidateDecision(
                java.util.UUID.randomUUID(),
                java.util.UUID.randomUUID(),
                java.util.UUID.randomUUID(),
                "keyword",
                java.util.UUID.randomUUID(),
                "bid",
                com.adpilot.modules.advertising.service.HostingAdjustmentType.BID,
                changeType,
                CandidateDecision.ENGINE_V1_BID,
                java.math.BigDecimal.ONE,
                java.math.BigDecimal.TEN,
                java.math.BigDecimal.valueOf(0.3),
                java.math.BigDecimal.valueOf(0.7),
                java.math.BigDecimal.valueOf(0.8),
                "{}"
        );
    }
}
