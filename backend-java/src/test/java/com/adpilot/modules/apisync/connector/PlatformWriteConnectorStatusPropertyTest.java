package com.adpilot.modules.apisync.connector;

import com.adpilot.modules.advertising.operation.SyncState;
import com.adpilot.modules.apisync.model.ConnectionContext;
import com.adpilot.modules.apisync.model.PlatformChange;
import com.adpilot.modules.apisync.model.PlatformStatusResult;
import com.adpilot.modules.apisync.model.PlatformWriteResult;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Label;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Property-based test for the {@code Req 55} status extension of
 * {@link PlatformWriteConnector}: {@link PlatformWriteConnector#queryStatus} and
 * {@link PlatformWriteConnector#mapPlatformStatus}.
 *
 * <p>Feature: advertising-workspace-rework, Property 76: queryStatus is
 * idempotent and non-mutating; status mapping is total.
 *
 * <p>Validates: Requirements 55.2, 55.4, 55.6.
 *
 * <p>For any platform reference and platform-reported status string, repeated
 * {@code queryStatus} calls return the same result for an unchanged platform
 * state and never mutate platform state (Req 55.2, 55.6), and
 * {@code mapPlatformStatus} is <em>total</em>: it returns exactly one
 * {@link SyncState} for every possible input string — including {@code null},
 * empty, and arbitrary unicode — and never throws (Req 55.4).
 */
@Label("Feature: advertising-workspace-rework, Property 76: queryStatus is idempotent and non-mutating; status mapping is total")
class PlatformWriteConnectorStatusPropertyTest {

    /**
     * Feature: advertising-workspace-rework, Property 76: queryStatus is
     * idempotent and non-mutating; status mapping is total.
     *
     * <p>Validates: Requirements 55.2, 55.4, 55.6.
     */
    @Property(tries = 200)
    void queryStatusIsIdempotentAndNonMutatingAndStatusMappingIsTotal(
            @ForAll("platformReferences") String platformReference,
            @ForAll("platformStatuses") String platformStatus) {

        // A minimal connector implementing only platform() + submit(), so it relies
        // on the DEFAULT queryStatus / mapPlatformStatus contract under test. It
        // carries a snapshot of "platform state" so non-mutation can be observed.
        RecordingWriteConnector connector = new RecordingWriteConnector();
        ConnectionContext ctx = new ConnectionContext(
                UUID.randomUUID(), UUID.randomUUID(), connector.platform(),
                Map.of("token", "secret"));

        // --- mapPlatformStatus is TOTAL: one SyncState for EVERY string, never throws ---
        SyncState[] mapped = new SyncState[1];
        assertThatCode(() -> mapped[0] = connector.mapPlatformStatus(platformStatus))
                .doesNotThrowAnyException();
        assertThat(mapped[0])
                .as("mapPlatformStatus must return exactly one non-null SyncState for any input")
                .isNotNull();
        // Mapping is deterministic: same input always yields the same state.
        assertThat(connector.mapPlatformStatus(platformStatus)).isEqualTo(mapped[0]);

        // --- queryStatus is idempotent and non-mutating ---
        Map<String, String> stateBefore = connector.platformStateSnapshot();

        PlatformStatusResult first = connector.queryStatus(ctx, platformReference);
        PlatformStatusResult second = connector.queryStatus(ctx, platformReference);

        assertThat(first)
                .as("repeated queryStatus calls return the same result for unchanged platform state")
                .isEqualTo(second);

        assertThat(connector.platformStateSnapshot())
                .as("queryStatus must never mutate platform state")
                .isEqualTo(stateBefore);
        assertThat(connector.submitCount())
                .as("queryStatus must not trigger any write to the platform")
                .isZero();

        // The status carried by a found result must itself map totally to a SyncState.
        if (first.found()) {
            assertThat(connector.mapPlatformStatus(first.platformStatus())).isNotNull();
        }
    }

    /**
     * A minimal {@link PlatformWriteConnector} implementing only the required
     * {@code platform()} and {@code submit()} members, exercising the default
     * {@code queryStatus} / {@code mapPlatformStatus} behaviour. It records any
     * write through {@code submit} and exposes an immutable snapshot of its
     * (deliberately read-only) platform state so a test can assert non-mutation.
     */
    private static final class RecordingWriteConnector implements PlatformWriteConnector {
        private final Map<String, String> platformState = new HashMap<>();
        private int submits = 0;

        @Override
        public String platform() {
            return "test_platform";
        }

        @Override
        public PlatformWriteResult submit(ConnectionContext ctx, PlatformChange change) {
            submits++;
            return PlatformWriteResult.accepted(UUID.randomUUID().toString(), "ok");
        }

        Map<String, String> platformStateSnapshot() {
            return Map.copyOf(platformState);
        }

        int submitCount() {
            return submits;
        }
    }

    // --- generators --------------------------------------------------------

    /** Platform references: arbitrary strings, plus null and empty, like real platform ids. */
    @Provide
    Arbitrary<String> platformReferences() {
        return Arbitraries.oneOf(
                Arbitraries.strings().ofMaxLength(64),
                Arbitraries.just(""),
                Arbitraries.just(null));
    }

    /**
     * Platform status strings spanning the recognized vocabulary, unknown tokens,
     * blank/whitespace, arbitrary unicode, and {@code null} — so mapping totality
     * is exercised across the entire input space (Req 55.4).
     */
    @Provide
    Arbitrary<String> platformStatuses() {
        Arbitrary<String> known = Arbitraries.of(
                "SUCCESS", "succeeded", "Completed", "COMPLETE", "applied", "EFFECTIVE",
                "ENABLED", "active", "PENDING", "queued", "SUBMITTED", "ACCEPTED",
                "IN_PROGRESS", "inprogress", "PROCESSING", "running", "FAILED", "failure",
                "ERROR", "rejected", "INVALID", "CANCELLED", "canceled", "ABORTED",
                "  success  ", "unknown_status", "");
        Arbitrary<String> arbitrary = Arbitraries.strings().ofMaxLength(40);
        Arbitrary<String> unicode = Arbitraries.strings()
                .withCharRange('\u0080', '\uFFFF').ofMinLength(1).ofMaxLength(16);
        Arbitrary<String> whitespace = Arbitraries.of(" ", "\t", "\n", "   ");
        return Arbitraries.oneOf(known, arbitrary, unicode, whitespace, Arbitraries.just(null));
    }
}
