package com.adpilot.modules.advertising.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.adpilot.modules.advertising.support.AiNotificationStateMachine.Resolution;
import com.adpilot.modules.advertising.support.AiNotificationStateMachine.State;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Label;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * Property 8: AI notification dedup uniqueness.
 *
 * <p>At most one <em>pending</em> notification may exist per
 * {@code (store, category, subject)} open key at any time. This mirrors the
 * {@code ai_notifications.open_dedup_key} generated column plus its
 * {@code uk_open_ai_notification} unique index: a pending row carries a non-null
 * composite key while closed rows carry {@code NULL} and drop out of the
 * constraint.
 *
 * <p>The test drives {@link AiNotificationDedup} the way the
 * {@code AiNotificationController}/service will: before raising a new pending
 * notification it consults {@link AiNotificationDedup#canOpen} against the set of
 * open keys held by currently-pending notifications, and only inserts when the
 * pre-check permits it. Closing a notification uses the
 * {@link AiNotificationStateMachine} so a closed row's open key becomes
 * {@code null} (via {@link AiNotificationDedup#openDedupKey}) and frees the key
 * for a future pending notification.
 *
 * <p>Across an arbitrary interleaving of raise/close events over a small pool of
 * colliding identities, the invariant is asserted after every step: grouped by
 * open key, the number of pending notifications is never more than one.
 *
 * <p>Tag: {@code Feature: app-functionality-completion, Property 8: AI
 * notification dedup uniqueness}
 *
 * <p>Validates: Requirements 23.1, 23.3
 */
@Label("Feature: app-functionality-completion, Property 8: AI notification dedup uniqueness")
class AiNotificationDedupUniquenessPropertyTest {

    private static final int MIN_ITERATIONS = 200;

    /** A single notification in the simulated store. */
    static final class Notification {
        final String storeId;
        final String category;
        final String subjectId;
        AiNotificationStateMachine.Status status;

        Notification(String storeId, String category, String subjectId) {
            this.storeId = storeId;
            this.category = category;
            this.subjectId = subjectId;
            this.status = AiNotificationStateMachine.pending();
        }

        /** The open dedup key as the database would derive it for this row's state. */
        String openKey() {
            return AiNotificationDedup.openDedupKey(status.state(), storeId, category, subjectId);
        }

        boolean isPending() {
            return status.isPending();
        }
    }

    /** One event in a generated history: raise or close a (store, category, subject) identity. */
    record Step(Op op, String storeId, String category, String subjectId) {
        enum Op { RAISE, CLOSE }
    }

    // ----- Main invariant: at most one pending notification per open key, always -----

    @Property(tries = MIN_ITERATIONS)
    @Label("at most one pending notification per (store, category, subject) open key at any time")
    void atMostOnePendingPerOpenKey(@ForAll("histories") List<Step> history) {
        List<Notification> all = new ArrayList<>();

        for (Step step : history) {
            // Open keys of currently-pending notifications — exactly what the unique index covers.
            Set<String> openKeys = pendingOpenKeys(all);

            switch (step.op()) {
                case RAISE -> {
                    boolean allowed =
                            AiNotificationDedup.canOpen(openKeys, step.storeId(), step.category(), step.subjectId());

                    // The pre-check must agree with the constraint it emulates.
                    String key = AiNotificationDedup.dedupKeyFor(step.storeId(), step.category(), step.subjectId());
                    assertThat(allowed)
                            .as("canOpen permits iff no pending notification already holds the key %s", key)
                            .isEqualTo(!openKeys.contains(key));

                    // Honour the pre-check: only insert a pending row when permitted.
                    if (allowed) {
                        all.add(new Notification(step.storeId(), step.category(), step.subjectId()));
                    }
                }
                case CLOSE -> closeOnePending(all, step);
            }

            // ----- Invariant after every step -----

            // Group pending notifications by their open key; none may collide.
            Map<String, Long> pendingPerKey = all.stream()
                    .filter(Notification::isPending)
                    .collect(Collectors.groupingBy(Notification::openKey, Collectors.counting()));
            assertThat(pendingPerKey.values())
                    .as("at most one pending notification per open key after step %s", step)
                    .allMatch(count -> count <= 1L);

            // Closed rows carry a null open key (they drop out of the constraint, unbounded history).
            assertThat(all.stream().filter(n -> !n.isPending()))
                    .as("every closed notification has a null open key")
                    .allMatch(n -> n.openKey() == null);

            // Pending rows always carry a non-null open key.
            assertThat(all.stream().filter(Notification::isPending))
                    .as("every pending notification has a non-null open key")
                    .allMatch(n -> n.openKey() != null);
        }
    }

    // ----- Supporting property: open keys collide exactly on identity (while pending) -----

    @Property(tries = MIN_ITERATIONS)
    @Label("two pending notifications share an open key iff they share (store, category, subject)")
    void openKeyCollidesExactlyOnIdentity(
            @ForAll("identities") Identity a,
            @ForAll("identities") Identity b) {
        String keyA = AiNotificationDedup.dedupKeyFor(a.storeId(), a.category(), a.subjectId());
        String keyB = AiNotificationDedup.dedupKeyFor(b.storeId(), b.category(), b.subjectId());

        boolean sameIdentity = a.storeId().equals(b.storeId())
                && a.category().equals(b.category())
                && norm(a.subjectId()).equals(norm(b.subjectId()));

        assertThat(keyA.equals(keyB))
                .as("open keys are equal iff identities match (subject null == empty): %s vs %s", a, b)
                .isEqualTo(sameIdentity);
    }

    // ----- Supporting property: a closed row's key is null and frees the identity to reopen -----

    @Property(tries = MIN_ITERATIONS)
    @Label("closing a pending notification nulls its key and lets the same identity be raised again")
    void closingFreesTheOpenKey(@ForAll("identities") Identity id, @ForAll Resolution resolution) {
        Notification n = new Notification(id.storeId(), id.category(), id.subjectId());
        // While pending it occupies the open key, so a second raise is blocked.
        Set<String> openKeys = pendingOpenKeys(List.of(n));
        assertThat(AiNotificationDedup.canOpen(openKeys, id.storeId(), id.category(), id.subjectId()))
                .as("a pending notification blocks reopening the same identity")
                .isFalse();
        assertThat(n.openKey()).as("pending key is non-null").isNotNull();

        // Once closed, the key is null and the identity may be raised again.
        n.status = AiNotificationStateMachine.close(n.status, resolution);
        assertThat(n.openKey()).as("closed key is null").isNull();
        Set<String> afterClose = pendingOpenKeys(List.of(n));
        assertThat(AiNotificationDedup.canOpen(afterClose, id.storeId(), id.category(), id.subjectId()))
                .as("a closed notification no longer blocks reopening the same identity")
                .isTrue();
    }

    // ----- Helpers -----

    private static Set<String> pendingOpenKeys(List<Notification> all) {
        Set<String> keys = new HashSet<>();
        for (Notification n : all) {
            if (n.isPending()) {
                keys.add(n.openKey());
            }
        }
        return keys;
    }

    /** Close one pending notification matching the step's identity, if any exists. */
    private static void closeOnePending(List<Notification> all, Step step) {
        String subject = norm(step.subjectId());
        for (Notification n : all) {
            if (n.isPending()
                    && n.storeId.equals(step.storeId())
                    && n.category.equals(step.category())
                    && norm(n.subjectId).equals(subject)) {
                n.status = AiNotificationStateMachine.close(n.status, Resolution.APPLIED);
                return;
            }
        }
    }

    private static String norm(String subjectId) {
        return subjectId == null ? "" : subjectId;
    }

    // ----- Generators -----

    /** A reusable (store, category, subject) identity drawn from colliding pools. */
    record Identity(String storeId, String category, String subjectId) {
    }

    // Small pools so generated steps frequently collide on the dedup key.
    private static final List<String> STORES = List.of("store-A", "store-B", "store-C");
    private static final List<String> CATEGORIES = List.of("bid", "budget", "target", "keyword");
    // Include null so the COALESCE(subject_id,'') branch is exercised alongside real subjects.
    private static final List<String> SUBJECTS = new ArrayList<>(List.of("subj-1", "subj-2", "subj-3"));

    @Provide
    Arbitrary<Identity> identities() {
        Arbitrary<String> store = Arbitraries.of(STORES);
        Arbitrary<String> category = Arbitraries.of(CATEGORIES);
        Arbitrary<String> subject = subjects();
        return Combinators.combine(store, category, subject).as(Identity::new);
    }

    @Provide
    Arbitrary<List<Step>> histories() {
        Arbitrary<Step.Op> op = Arbitraries.of(Step.Op.class);
        Arbitrary<String> store = Arbitraries.of(STORES);
        Arbitrary<String> category = Arbitraries.of(CATEGORIES);
        Arbitrary<String> subject = subjects();
        Arbitrary<Step> step = Combinators.combine(op, store, category, subject).as(Step::new);
        return step.list().ofMinSize(1).ofMaxSize(40);
    }

    private Arbitrary<String> subjects() {
        Arbitrary<String> present = Arbitraries.of(SUBJECTS);
        return Arbitraries.frequencyOf(
                net.jqwik.api.Tuple.of(4, present),
                net.jqwik.api.Tuple.of(1, Arbitraries.just((String) null)));
    }
}
