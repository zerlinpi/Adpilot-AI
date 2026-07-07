package com.adpilot.modules.advertising.operation;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

/**
 * The single, authoritative encoding of the legal {@link SyncState} transitions for an
 * {@code Operation}, exactly as enumerated in Requirement 4.1, extended with the transient
 * {@code SUBMITTING} state (Requirement 16.7) and the corrected approval edge
 * (Requirements 7.7, 24.2).
 *
 * <p>This is a <strong>pure</strong> component: it is stateless, side-effect free, and free of
 * persistence, JSON, or scheduling concerns, so it can be unit- and property-tested in isolation
 * (Property 4, task 3.3). {@code OperationService} MUST consult this class before mutating an
 * Operation's {@code sync_state}; it never hard-codes a transition itself.</p>
 *
 * <p>It is registered as a Spring {@link Component} purely so it can be injected as the one shared
 * authority — it holds no mutable state and its behavior does not depend on any Spring context.</p>
 *
 * <p>The legal transitions are:</p>
 * <ul>
 *   <li>{@code pending} → {@code awaiting_approval} | {@code submitting} | {@code cancelled} | {@code superseded}</li>
 *   <li>{@code awaiting_approval} → {@code pending} (approve) | {@code cancelled} | {@code superseded}</li>
 *   <li>{@code submitting} → {@code submitted} (accepted) | {@code pending} (retryable) | {@code failed} (permanent reject)</li>
 *   <li>{@code submitted} → {@code amazon-processing} | {@code effective} | {@code failed} | {@code expired} | {@code cancel_requested}</li>
 *   <li>{@code amazon-processing} → {@code effective} | {@code failed} | {@code expired} | {@code cancel_requested}</li>
 *   <li>{@code cancel_requested} → {@code cancelled} | {@code effective} | {@code reconciliation_required}</li>
 *   <li>{@code reconciliation_required} → {@code effective} | {@code failed} | {@code cancelled}</li>
 *   <li>{@code expired} → {@code effective} | {@code failed} | {@code reconciliation_required}</li>
 *   <li>{@code failed} → {@code pending} (manual retry only)</li>
 *   <li>{@code local-only} is a terminal local state with no outgoing transitions.</li>
 * </ul>
 *
 * <p>The terminal settled states {@code effective}, {@code cancelled}, and {@code superseded} also
 * have no outgoing transitions.</p>
 *
 * <p>Validates: Requirements 4.1, 3.1, 7.7, 7.8, 16.7, 24.2.</p>
 */
@Component
public class OperationStateMachine {

    /**
     * The authoritative transition table: {@code from -> (event -> to)}. This is the single source of
     * truth. {@link #canTransition(SyncState, SyncState)} is derived from the set of target states
     * reachable from each source, so the two query methods can never disagree about what is legal.
     */
    private static final Map<SyncState, Map<TransitionEvent, SyncState>> TRANSITIONS = buildTransitions();

    private static Map<SyncState, Map<TransitionEvent, SyncState>> buildTransitions() {
        EnumMap<SyncState, Map<TransitionEvent, SyncState>> table = new EnumMap<>(SyncState.class);

        // pending → awaiting_approval | submitting | cancelled | superseded
        EnumMap<TransitionEvent, SyncState> pending = new EnumMap<>(TransitionEvent.class);
        pending.put(TransitionEvent.REQUEST_APPROVAL, SyncState.AWAITING_APPROVAL);
        pending.put(TransitionEvent.SUBMIT, SyncState.SUBMITTING);
        pending.put(TransitionEvent.CANCEL, SyncState.CANCELLED);
        pending.put(TransitionEvent.SUPERSEDE, SyncState.SUPERSEDED);
        table.put(SyncState.PENDING, Collections.unmodifiableMap(pending));

        // awaiting_approval → pending (approve, Req 7.7/24.2) | cancelled (rejected or operator cancel) | superseded
        EnumMap<TransitionEvent, SyncState> awaitingApproval = new EnumMap<>(TransitionEvent.class);
        awaitingApproval.put(TransitionEvent.APPROVE, SyncState.PENDING);
        awaitingApproval.put(TransitionEvent.REJECT, SyncState.CANCELLED);
        awaitingApproval.put(TransitionEvent.CANCEL, SyncState.CANCELLED);
        awaitingApproval.put(TransitionEvent.SUPERSEDE, SyncState.SUPERSEDED);
        table.put(SyncState.AWAITING_APPROVAL, Collections.unmodifiableMap(awaitingApproval));

        // submitting → submitted (accepted) | pending (retryable) | failed (permanent reject)
        // Requirement 16.7: transient state representing "platform call in progress"
        EnumMap<TransitionEvent, SyncState> submitting = new EnumMap<>(TransitionEvent.class);
        submitting.put(TransitionEvent.PLATFORM_ACCEPTED, SyncState.SUBMITTED);
        submitting.put(TransitionEvent.RETRYABLE_REJECT, SyncState.PENDING);
        submitting.put(TransitionEvent.PERMANENT_REJECT, SyncState.FAILED);
        table.put(SyncState.SUBMITTING, Collections.unmodifiableMap(submitting));

        // submitted → amazon-processing | effective | failed | expired | cancel_requested
        EnumMap<TransitionEvent, SyncState> submitted = new EnumMap<>(TransitionEvent.class);
        submitted.put(TransitionEvent.PLATFORM_PROCESSING, SyncState.AMAZON_PROCESSING);
        submitted.put(TransitionEvent.PLATFORM_EFFECTIVE, SyncState.EFFECTIVE);
        submitted.put(TransitionEvent.PLATFORM_FAILED, SyncState.FAILED);
        submitted.put(TransitionEvent.TIMEOUT, SyncState.EXPIRED);
        submitted.put(TransitionEvent.REQUEST_CANCEL, SyncState.CANCEL_REQUESTED);
        table.put(SyncState.SUBMITTED, Collections.unmodifiableMap(submitted));

        // amazon-processing → effective | failed | expired | cancel_requested
        EnumMap<TransitionEvent, SyncState> amazonProcessing = new EnumMap<>(TransitionEvent.class);
        amazonProcessing.put(TransitionEvent.PLATFORM_EFFECTIVE, SyncState.EFFECTIVE);
        amazonProcessing.put(TransitionEvent.PLATFORM_FAILED, SyncState.FAILED);
        amazonProcessing.put(TransitionEvent.TIMEOUT, SyncState.EXPIRED);
        amazonProcessing.put(TransitionEvent.REQUEST_CANCEL, SyncState.CANCEL_REQUESTED);
        table.put(SyncState.AMAZON_PROCESSING, Collections.unmodifiableMap(amazonProcessing));

        // cancel_requested → cancelled | effective | reconciliation_required
        EnumMap<TransitionEvent, SyncState> cancelRequested = new EnumMap<>(TransitionEvent.class);
        cancelRequested.put(TransitionEvent.CANCEL_CONFIRMED, SyncState.CANCELLED);
        cancelRequested.put(TransitionEvent.PLATFORM_EFFECTIVE, SyncState.EFFECTIVE);
        cancelRequested.put(TransitionEvent.RECONCILE, SyncState.RECONCILIATION_REQUIRED);
        table.put(SyncState.CANCEL_REQUESTED, Collections.unmodifiableMap(cancelRequested));

        // reconciliation_required → effective | failed | cancelled
        EnumMap<TransitionEvent, SyncState> reconciliationRequired = new EnumMap<>(TransitionEvent.class);
        reconciliationRequired.put(TransitionEvent.PLATFORM_EFFECTIVE, SyncState.EFFECTIVE);
        reconciliationRequired.put(TransitionEvent.PLATFORM_FAILED, SyncState.FAILED);
        reconciliationRequired.put(TransitionEvent.CANCEL_CONFIRMED, SyncState.CANCELLED);
        table.put(SyncState.RECONCILIATION_REQUIRED, Collections.unmodifiableMap(reconciliationRequired));

        // expired → effective | failed | reconciliation_required
        EnumMap<TransitionEvent, SyncState> expired = new EnumMap<>(TransitionEvent.class);
        expired.put(TransitionEvent.PLATFORM_EFFECTIVE, SyncState.EFFECTIVE);
        expired.put(TransitionEvent.PLATFORM_FAILED, SyncState.FAILED);
        expired.put(TransitionEvent.RECONCILE, SyncState.RECONCILIATION_REQUIRED);
        table.put(SyncState.EXPIRED, Collections.unmodifiableMap(expired));

        // failed → pending (manual retry only, Req 16.7)
        EnumMap<TransitionEvent, SyncState> failed = new EnumMap<>(TransitionEvent.class);
        failed.put(TransitionEvent.RETRY, SyncState.PENDING);
        table.put(SyncState.FAILED, Collections.unmodifiableMap(failed));

        // Terminal states with no outgoing transitions: local-only, effective, cancelled, superseded.
        table.put(SyncState.LOCAL_ONLY, Collections.emptyMap());
        table.put(SyncState.EFFECTIVE, Collections.emptyMap());
        table.put(SyncState.CANCELLED, Collections.emptyMap());
        table.put(SyncState.SUPERSEDED, Collections.emptyMap());

        return Collections.unmodifiableMap(table);
    }

    /**
     * Reports whether moving directly from {@code from} to {@code to} is one of the transitions
     * enumerated in Requirement 4.1.
     *
     * <p>Returns {@code true} if and only if {@code (from, to)} is a legal edge; every other pair —
     * including any transition out of a terminal state ({@code local-only}, {@code effective},
     * {@code cancelled}, {@code superseded}) and any self-transition — returns {@code false}.</p>
     *
     * @param from the current Sync_State; must not be {@code null}
     * @param to   the candidate next Sync_State; must not be {@code null}
     * @return {@code true} iff the transition is legal per Requirement 4.1
     * @throws IllegalArgumentException if either argument is {@code null}
     */
    public boolean canTransition(SyncState from, SyncState to) {
        if (from == null || to == null) {
            throw new IllegalArgumentException("from and to states must not be null");
        }
        return TRANSITIONS.get(from).containsValue(to);
    }

    /**
     * Applies {@code event} to {@code from} and returns the resulting Sync_State, enforcing exactly
     * the legal transitions of Requirement 4.1.
     *
     * @param from  the current Sync_State; must not be {@code null}
     * @param event the event driving the transition; must not be {@code null}
     * @return the next Sync_State for the legal {@code (from, event)} pair
     * @throws IllegalArgumentException if either argument is {@code null}
     * @throws IllegalStateException    if {@code event} is not legal from {@code from} (including any
     *                                  event applied to a terminal state)
     */
    public SyncState transition(SyncState from, TransitionEvent event) {
        if (from == null || event == null) {
            throw new IllegalArgumentException("from state and event must not be null");
        }
        SyncState to = TRANSITIONS.get(from).get(event);
        if (to == null) {
            throw new IllegalStateException(
                    "Illegal Sync_State transition: event " + event + " is not permitted from state " + from);
        }
        return to;
    }

    /**
     * @param from the source Sync_State; must not be {@code null}
     * @return the immutable set of Sync_States reachable from {@code from} in a single legal
     *         transition (empty for a terminal state). Useful for diagnostics, UI affordances, and
     *         property-test oracles.
     * @throws IllegalArgumentException if {@code from} is {@code null}
     */
    public Set<SyncState> legalTargets(SyncState from) {
        if (from == null) {
            throw new IllegalArgumentException("from state must not be null");
        }
        return Set.copyOf(TRANSITIONS.get(from).values());
    }
}
