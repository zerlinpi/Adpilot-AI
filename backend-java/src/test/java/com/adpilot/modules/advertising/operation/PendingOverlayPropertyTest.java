package com.adpilot.modules.advertising.operation;

import com.adpilot.modules.advertising.support.PendingOverlayRow;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Label;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property-based test for the Pending_Overlay (task 8.2).
 *
 * <p>Feature: advertising-workspace-rework, Property 21: Pending_Overlay surfaces confirmed and
 * pending for every Unsettled_State.
 *
 * <p>Validates: Requirements 7.3, 7.6, 7.7.
 *
 * <p>Property 21 (transcribed from the design's Correctness Properties section): <em>For any
 * writable field with an Operation in any Unsettled_State, the Pending_Overlay exposes both the
 * entity's Amazon-confirmed value and the Operation's pending value; when no Operation is in
 * progress, only the confirmed value is exposed.</em>
 *
 * <p>The property drives {@link PendingOverlayServiceImpl#composeOverlay(Map, List)} — the pure
 * heart of the overlay — directly. A single entity row is modelled as a set of writable fields, each
 * carrying one Amazon-confirmed value and zero or more candidate Operations spread across the full
 * Sync_State space (settled and unsettled alike). The candidate pending-change rows are shuffled
 * before composition so the result cannot depend on input order. For every generated entity the
 * property asserts the overlay's exact shape per field:
 *
 * <ul>
 *   <li>the confirmed value is passed through untouched in every case (Req 7.3, the confirmed value
 *       lives once on the entity and is never disturbed by a pending change);</li>
 *   <li>when at least one Unsettled_State Operation targets the field, the overlay surfaces the
 *       <em>latest</em> such Operation's pending value <em>and</em> its Sync_State alongside the
 *       confirmed value (Req 7.6) — even when a later settled Operation exists, the pending view is
 *       driven by the latest Unsettled_State Operation;</li>
 *   <li>when no Unsettled_State Operation targets the field — there is none, or every candidate is in
 *       a settled state — only the confirmed value is exposed, with no pending value and no pending
 *       Sync_State (Req 7.7).</li>
 * </ul>
 */
@Label("Feature: advertising-workspace-rework, Property 21: Pending_Overlay surfaces confirmed and pending for every Unsettled_State")
class PendingOverlayPropertyTest {

    /** Minimum property iterations mandated by the design's Correctness Properties section. */
    private static final int MIN_ITERATIONS = 200;

    /** Stable base instant; each candidate Operation for a field is created one second later. */
    private static final LocalDateTime BASE_CREATED_AT = LocalDateTime.of(2024, 1, 1, 0, 0, 0);

    /** A single candidate Operation against a field: its Sync_State and requested pending value. */
    record OpSpec(SyncState state, String afterValue) {}

    /** One writable field of the entity: its confirmed value and its candidate Operations, oldest first. */
    record FieldSpec(String field, String confirmedValue, List<OpSpec> operations) {}

    /**
     * Feature: advertising-workspace-rework, Property 21: Pending_Overlay surfaces confirmed and
     * pending for every Unsettled_State.
     *
     * <p>Validates: Requirements 7.3, 7.6, 7.7.
     */
    @Property(tries = MIN_ITERATIONS)
    @Label("Property 21: the overlay surfaces confirmed+pending for an Unsettled_State field and confirmed-only otherwise")
    void overlaySurfacesConfirmedAndPendingForEveryUnsettledState(@ForAll("entities") List<FieldSpec> entity) {
        OperationJsonCodec codec = new OperationJsonCodec(new ObjectMapper());
        // composeOverlay never touches the mapper, so it is not needed here.
        PendingOverlayServiceImpl service = new PendingOverlayServiceImpl(null, codec);

        // Confirmed values: the value stored ONCE on the entity per writable field (Req 7.1).
        Map<String, Object> confirmedValues = new LinkedHashMap<>();
        for (FieldSpec spec : entity) {
            confirmedValues.put(spec.field(), spec.confirmedValue());
        }

        // Candidate pending-change rows for every Operation across every field, created oldest-first.
        List<PendingOverlayRow> rows = new ArrayList<>();
        for (FieldSpec spec : entity) {
            List<OpSpec> ops = spec.operations();
            for (int i = 0; i < ops.size(); i++) {
                OpSpec op = ops.get(i);
                PendingOverlayRow row = new PendingOverlayRow();
                row.setField(spec.field());
                row.setAfterValue(codec.toJson(op.afterValue()));
                row.setSyncState(OperationMachineValues.toValue(op.state()));
                row.setCreatedAt(BASE_CREATED_AT.plusSeconds(i));
                rows.add(row);
            }
        }
        // The overlay must not depend on row order; the latest Operation is chosen by created_at.
        Collections.shuffle(rows);

        Map<String, OverlayField> overlay = service.composeOverlay(confirmedValues, rows);

        // The overlay covers exactly the entity's writable fields — no more, no fewer.
        assertThat(overlay.keySet()).isEqualTo(confirmedValues.keySet());

        for (FieldSpec spec : entity) {
            OverlayField field = overlay.get(spec.field());

            // (1) The Amazon-confirmed value is always present and passed through untouched (Req 7.3).
            assertThat(field.getConfirmedValue())
                    .as("confirmed value for field '%s' must be passed through untouched", spec.field())
                    .isEqualTo(spec.confirmedValue());

            Optional<OpSpec> latestUnsettled = latestUnsettled(spec.operations());

            if (latestUnsettled.isPresent()) {
                OpSpec expected = latestUnsettled.get();
                // (2) An Unsettled_State Operation surfaces its pending value AND Sync_State (Req 7.6).
                assertThat(field.hasPending())
                        .as("field '%s' has an Unsettled_State Operation, so a pending value must surface", spec.field())
                        .isTrue();
                assertThat(field.getPendingValue())
                        .as("pending value for field '%s' must be the latest Unsettled_State Operation's after value", spec.field())
                        .contains(expected.afterValue());
                assertThat(field.getPendingSyncState())
                        .as("pending Sync_State for field '%s' must be the latest Unsettled_State Operation's state", spec.field())
                        .contains(expected.state());
            } else {
                // (3) No Unsettled_State Operation: only the confirmed value is exposed (Req 7.7).
                assertThat(field.hasPending())
                        .as("field '%s' has no Unsettled_State Operation, so no pending value may surface", spec.field())
                        .isFalse();
                assertThat(field.getPendingValue())
                        .as("field '%s' must expose no pending value", spec.field())
                        .isEmpty();
                assertThat(field.getPendingSyncState())
                        .as("field '%s' must expose no pending Sync_State", spec.field())
                        .isEmpty();
            }
        }
    }

    /**
     * The latest Operation in an Unsettled_State, mirroring the overlay's selection: candidates are
     * ordered oldest-first, so the last unsettled one is the newest. A later settled Operation does
     * not displace it.
     */
    private static Optional<OpSpec> latestUnsettled(List<OpSpec> operations) {
        OpSpec latest = null;
        for (OpSpec op : operations) {
            if (op.state().isUnsettled()) {
                latest = op;
            }
        }
        return Optional.ofNullable(latest);
    }

    // --- generators --------------------------------------------------------

    /**
     * An entity row: 1-6 distinct writable fields, each with a confirmed value and 0-5 candidate
     * Operations spread across the full Sync_State space so both the unsettled and the settled-only
     * branches are exercised.
     */
    @Provide
    Arbitrary<List<FieldSpec>> entities() {
        Arbitrary<String> fieldNames = Arbitraries.of(
                "bid", "budget", "dailyBudget", "state", "matchType", "targetAcos");
        Arbitrary<String> values = Arbitraries.strings()
                .withCharRange('a', 'z').numeric().ofMinLength(1).ofMaxLength(12);
        Arbitrary<SyncState> states = Arbitraries.of(SyncState.values());

        Arbitrary<OpSpec> operations = Combinators.combine(states, values).as(OpSpec::new);
        Arbitrary<List<OpSpec>> operationLists = operations.list().ofMinSize(0).ofMaxSize(5);

        Arbitrary<FieldSpec> fieldSpecs =
                Combinators.combine(fieldNames, values, operationLists).as(FieldSpec::new);

        return fieldSpecs.list().ofMinSize(1).ofMaxSize(6).uniqueElements(FieldSpec::field);
    }
}
