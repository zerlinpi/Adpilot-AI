package com.adpilot.modules.advertising.operation;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.adpilot.modules.advertising.entity.OperationEntity;
import com.adpilot.modules.advertising.mapper.OperationMapper;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Label;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;
import net.jqwik.api.constraints.IntRange;
import org.mockito.Mockito;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Property-based test for the two distinct idempotency layers owned by
 * {@link IdempotencyServiceImpl} (Req 5.2, 5.7).
 *
 * <p>Feature: advertising-workspace-rework, Property 13: Submission idempotency vs retry
 * independence.
 *
 * <p>Validates: Requirements 5.2, 5.7.
 *
 * <p>For any sequence of platform callbacks, poll results, or re-deliveries, a delivery carrying an
 * already-processed {@code submissionIdempotencyKey} produces no duplicate platform re-submission
 * (it is recognized as already processed), while a retry — which carries its OWN new
 * {@code submissionIdempotencyKey} — is never blocked or deduped by a prior attempt's submission
 * key. The property asserts both halves:
 *
 * <ol>
 *   <li><strong>Independence (retry is never blocked):</strong> {@link
 *       IdempotencyServiceImpl#newSubmissionIdempotencyKey()} yields a distinct, non-blank key on
 *       every call across many attempts, so each retry attempt carries a key that differs from all
 *       prior attempts' keys.</li>
 *   <li><strong>Dedupe (already-processed recognition):</strong> {@link
 *       IdempotencyServiceImpl#isSubmissionProcessed(String)} returns {@code true} exactly when the
 *       attempt carrying that key has reached a settled platform Sync_State
 *       ({@code effective}/{@code failed}/{@code cancelled}), and {@code false} for any unsettled
 *       state or unknown key — so an already-processed submission is recognized while a
 *       new/unprocessed key is not blocked.</li>
 * </ol>
 *
 * <p>The settled-state oracle below is transcribed <strong>independently</strong> from the
 * Requirement glossary (a submission is final only on {@code effective}, {@code failed}, or
 * {@code cancelled}), so the test cannot trivially agree with the implementation by sharing its
 * data. The {@link OperationMapper} is mocked to return controllable rows, as a real query would.
 */
@Tag("pbt")
@Label("Feature: advertising-workspace-rework, Property 13: Submission idempotency vs retry independence")
class SubmissionIdempotencyIndependencePropertyTest {

    /**
     * The settled platform Sync_States, transcribed independently from the Requirement glossary: a
     * submission is final (already processed) only once its attempt is {@code effective},
     * {@code failed}, or {@code cancelled}. Every other Sync_State leaves the submission in flight.
     */
    private static final Set<SyncState> SETTLED =
            Set.of(SyncState.EFFECTIVE, SyncState.FAILED, SyncState.CANCELLED);

    /**
     * Feature: advertising-workspace-rework, Property 13: Submission idempotency vs retry
     * independence.
     *
     * <p>Validates: Requirements 5.2, 5.7.
     */
    @Property(tries = 200)
    @Label("Property 13: fresh per-attempt submission keys are independent, and an already-processed key is recognized while a new/unprocessed key is not blocked")
    void submissionKeysAreIndependentAndProcessedStateIsRecognizedExactly(
            @ForAll @IntRange(min = 2, max = 40) int attemptCount,
            @ForAll("syncStates") SyncState lookedUpState,
            @ForAll boolean keyKnown) {

        OperationMapper operationMapper = Mockito.mock(OperationMapper.class);
        IdempotencyServiceImpl service = new IdempotencyServiceImpl(operationMapper);

        // --- (a) Independence: every attempt (including every retry) mints its OWN key ----------
        // A retry is never blocked by a prior attempt's submission key because each call yields a
        // fresh, distinct, non-blank key (Req 5.2, 5.7).
        Set<String> mintedKeys = new HashSet<>();
        for (int i = 0; i < attemptCount; i++) {
            String key = service.newSubmissionIdempotencyKey();
            assertThat(key).isNotNull().isNotBlank();
            assertThat(mintedKeys.add(key))
                    .as("each attempt's submissionIdempotencyKey must differ from every prior attempt's key")
                    .isTrue();
        }
        assertThat(mintedKeys).hasSize(attemptCount);

        // --- (b) Dedupe: an already-processed key is recognized; a new/unprocessed key is not ----
        // Pick one of the freshly minted keys to model the key carried by an inbound delivery.
        String deliveredKey = mintedKeys.iterator().next();

        // Model the persisted attempt the mapper would return for that key. When the key is unknown
        // (no attempt has reached the platform yet), the mapper returns no rows.
        List<OperationEntity> rows = keyKnown
                ? List.of(attempt(deliveredKey, lookedUpState))
                : List.of();
        when(operationMapper.selectList(any())).thenReturn(rows);

        boolean expectedProcessed = keyKnown && SETTLED.contains(lookedUpState);

        assertThat(service.isSubmissionProcessed(deliveredKey))
                .as("a submission is already-processed iff its attempt is in a settled platform state")
                .isEqualTo(expectedProcessed);
    }

    private static OperationEntity attempt(String submissionKey, SyncState state) {
        return OperationEntity.builder()
                .id(UUID.randomUUID())
                .storeId(UUID.randomUUID())
                .operationSource(OperationMachineValues.toValue(OperationSource.MANUAL))
                .operationScope(OperationMachineValues.toValue(OperationScope.PLATFORM_MUTATION))
                .entityType("campaign")
                .entityId(UUID.randomUUID())
                .logicalOperationId(UUID.randomUUID())
                .logicalIdempotencyKey(UUID.randomUUID().toString())
                .attemptId(UUID.randomUUID())
                .submissionIdempotencyKey(submissionKey)
                .syncState(OperationMachineValues.toValue(state))
                .build();
    }

    // --- generators ------------------------------------------------------------------------------

    @Provide
    Arbitrary<SyncState> syncStates() {
        return Arbitraries.of(SyncState.values());
    }
}
