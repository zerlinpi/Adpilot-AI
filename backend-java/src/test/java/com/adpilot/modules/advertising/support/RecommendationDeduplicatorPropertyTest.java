package com.adpilot.modules.advertising.support;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property-based test for {@link RecommendationDeduplicator}.
 *
 * <p>Feature: advertising-workspace-rework, Property 42: Recommendation generation
 * is deduplicated and store-scoped.
 *
 * <p>Validates: Requirements 18.5, 18.8, 46.3, 46.4.
 *
 * <p>The pure {@code RecommendationDeduplicator} is the single source of truth for
 * the duplicate-suppression that both the recommendation engine (Requirement 18.5)
 * and smart diagnosis (Requirement 46.3/46.4) share. A "duplicate" is the same
 * change for the same target, keyed by
 * {@code (targetEntityType, targetEntityId, changeType)}. These properties assert:
 * <ul>
 *   <li><b>Dedup</b> — across any sequence of proposed changes, at most one emission
 *       occurs per distinct key; every later proposal of an already-emitted key is
 *       suppressed (Requirement 18.5).</li>
 *   <li><b>Seeded suppression</b> — keys seeded from already-existing, unapplied
 *       Recommendations are never re-emitted by a fresh pass (Requirements 18.5,
 *       46.3, 46.4).</li>
 *   <li><b>Store-scoping</b> — a deduplicator built for one store reads and writes
 *       only its own store's keys; emissions for that store are independent of any
 *       other store's existing keys and emissions (Requirement 18.8).</li>
 * </ul>
 */
class RecommendationDeduplicatorPropertyTest {

    /** A proposed change for a target, as the engine/diagnosis would produce. */
    record Suggestion(String entityType, String entityId, String changeType) {
        String key() {
            return RecommendationDeduplicator.keyOf(entityType, entityId, changeType);
        }
    }

    /**
     * Feature: advertising-workspace-rework, Property 42: Recommendation generation
     * is deduplicated and store-scoped.
     *
     * <p>Validates: Requirements 18.5, 46.3, 46.4.
     *
     * <p>Across any sequence of proposed changes, exactly one emission occurs per
     * distinct {@code (type, id, changeType)} key: the count and the set of emitted
     * keys equal the distinct keys of the input, and re-proposing any key afterwards
     * is always suppressed.
     */
    @Property(tries = 200)
    void atMostOneEmissionPerKey(@ForAll("suggestions") List<Suggestion> suggestions) {
        RecommendationDeduplicator dedup = new RecommendationDeduplicator();

        List<Suggestion> emitted = new ArrayList<>();
        for (Suggestion s : suggestions) {
            if (dedup.tryEmit(s.entityType(), s.entityId(), s.changeType())) {
                emitted.add(s);
            }
        }

        Set<String> distinctKeys =
                suggestions.stream().map(Suggestion::key).collect(Collectors.toSet());
        Set<String> emittedKeys =
                emitted.stream().map(Suggestion::key).collect(Collectors.toSet());

        // Exactly one emission per distinct key — no duplicate change for a target.
        assertThat(emitted).hasSameSizeAs(emittedKeys);
        assertThat(emittedKeys).isEqualTo(distinctKeys);
        assertThat(dedup.size()).isEqualTo(distinctKeys.size());

        // Every input key is now a known duplicate and re-emission is always suppressed.
        for (Suggestion s : suggestions) {
            assertThat(dedup.isDuplicate(s.entityType(), s.entityId(), s.changeType())).isTrue();
            assertThat(dedup.tryEmit(s.entityType(), s.entityId(), s.changeType())).isFalse();
        }
    }

    /**
     * Feature: advertising-workspace-rework, Property 42: Recommendation generation
     * is deduplicated and store-scoped.
     *
     * <p>Validates: Requirements 18.5, 46.3, 46.4.
     *
     * <p>Keys seeded from already-existing unapplied Recommendations are never
     * re-emitted by a fresh pass: a proposal whose key was seeded is suppressed, and
     * the set of newly-emitted keys is exactly the new distinct keys minus the seeded
     * ones.
     */
    @Property(tries = 200)
    void seededExistingKeysAreNeverReEmitted(
            @ForAll("suggestions") List<Suggestion> existing,
            @ForAll("suggestions") List<Suggestion> proposed) {

        RecommendationDeduplicator dedup = new RecommendationDeduplicator();
        Set<String> seededKeys = existing.stream().map(Suggestion::key).collect(Collectors.toSet());
        dedup.seedAll(seededKeys);

        Set<String> emittedKeys = new HashSet<>();
        for (Suggestion s : proposed) {
            boolean justEmitted = dedup.tryEmit(s.entityType(), s.entityId(), s.changeType());
            if (justEmitted) {
                emittedKeys.add(s.key());
            }
            // A proposal whose key was seeded can never be (re-)emitted.
            if (seededKeys.contains(s.key())) {
                assertThat(justEmitted).isFalse();
            }
        }

        Set<String> expectedNewKeys =
                proposed.stream().map(Suggestion::key).collect(Collectors.toSet());
        expectedNewKeys.removeAll(seededKeys);

        // Newly-emitted keys are exactly the new distinct keys not already existing.
        assertThat(emittedKeys).isEqualTo(expectedNewKeys);
        // No emitted key collides with a seeded (existing) Recommendation.
        Set<String> emittedSeedOverlap = new HashSet<>(emittedKeys);
        emittedSeedOverlap.retainAll(seededKeys);
        assertThat(emittedSeedOverlap).isEmpty();
    }

    /**
     * Feature: advertising-workspace-rework, Property 42: Recommendation generation
     * is deduplicated and store-scoped.
     *
     * <p>Validates: Requirement 18.8.
     *
     * <p>A deduplicator constructed for one store reads and writes only that store's
     * keys. Generation for store A is independent of store B: A's emissions depend
     * solely on A's proposals and A's own seeds, never on B's existing keys or B's
     * emissions, and A never registers a key that occurs only in B.
     */
    @Property(tries = 200)
    void generationIsScopedToTheRequestedStore(
            @ForAll("suggestions") List<Suggestion> storeAProposals,
            @ForAll("suggestions") List<Suggestion> storeASeeds,
            @ForAll("suggestions") List<Suggestion> storeBProposals,
            @ForAll("suggestions") List<Suggestion> storeBSeeds) {

        Set<String> seedAKeys = storeASeeds.stream().map(Suggestion::key).collect(Collectors.toSet());

        // Store A's deduplicator only ever sees store A's seeds and proposals.
        RecommendationDeduplicator dedupA = new RecommendationDeduplicator();
        dedupA.seedAll(seedAKeys);
        Set<String> emittedA = new HashSet<>();
        for (Suggestion s : storeAProposals) {
            if (dedupA.tryEmit(s.entityType(), s.entityId(), s.changeType())) {
                emittedA.add(s.key());
            }
        }

        // Store B is generated by its own independent deduplicator in the same run.
        RecommendationDeduplicator dedupB = new RecommendationDeduplicator();
        dedupB.seedAll(storeBSeeds.stream().map(Suggestion::key).collect(Collectors.toSet()));
        for (Suggestion s : storeBProposals) {
            dedupB.tryEmit(s.entityType(), s.entityId(), s.changeType());
        }

        // The store-scoped result: A's emissions are exactly A's distinct proposed
        // keys minus A's own seeds — computed without any reference to store B.
        Set<String> expectedA =
                storeAProposals.stream().map(Suggestion::key).collect(Collectors.toSet());
        expectedA.removeAll(seedAKeys);
        assertThat(emittedA).isEqualTo(expectedA);

        // A never reads or writes a key that belongs only to store B: every key A
        // registered originates from A's own proposals or seeds.
        Set<String> storeAUniverse =
                storeAProposals.stream().map(Suggestion::key).collect(Collectors.toSet());
        storeAUniverse.addAll(seedAKeys);
        Set<String> bOnlyKeys =
                storeBProposals.stream().map(Suggestion::key).collect(Collectors.toSet());
        bOnlyKeys.removeAll(storeAUniverse);
        Set<String> emittedABOverlap = new HashSet<>(emittedA);
        emittedABOverlap.retainAll(bOnlyKeys);
        assertThat(emittedABOverlap).isEmpty();
    }

    // --- generators --------------------------------------------------------

    /**
     * Lists of proposed changes drawn from small pools of entity types, ids, and
     * change types so that key collisions (the interesting dedup case) occur often,
     * while still exercising null/blank/case/whitespace normalization in
     * {@link RecommendationDeduplicator#keyOf(String, Object, String)}.
     */
    @Provide
    Arbitrary<List<Suggestion>> suggestions() {
        return suggestion().list().ofMinSize(0).ofMaxSize(30);
    }

    private Arbitrary<Suggestion> suggestion() {
        Arbitrary<String> entityTypes = Arbitraries.of(
                "keyword", "campaign", "target", "ad_group",
                "KEYWORD", " keyword ", null, "");
        Arbitrary<String> entityIds = Arbitraries.of(
                "1", "2", "3", " 2 ", "ABC-1", "abc-1", null);
        Arbitrary<String> changeTypes = Arbitraries.of(
                "decrease_bid", "increase_bid", "add_negative", "pause",
                "DECREASE_BID", " add_negative ", null, "");
        return Combinators.combine(entityTypes, entityIds, changeTypes).as(Suggestion::new);
    }
}
