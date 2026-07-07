package com.adpilot.modules.advertising.operation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import com.adpilot.modules.advertising.entity.OperationEntity;
import com.adpilot.modules.advertising.mapper.OperationMapper;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Label;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.mockito.Mockito;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Property-based test for click coalescing owned by {@link IdempotencyServiceImpl} (Req 5.3).
 *
 * <p>Feature: advertising-workspace-rework, Property 14: Repeated activations coalesce into one
 * logical Operation.
 *
 * <p>Validates: Requirements 5.3.
 *
 * <p>Requirement 5.3 states that when an operator activates the same Operation control repeatedly
 * before the logical Operation completes, the activations are coalesced into a SINGLE logical
 * Operation by {@code logicalIdempotencyKey}. {@link IdempotencyService#findLogicalOperation(UUID,
 * String)} is the coalescing primitive the {@code createOperation} pipeline (task 6.1) relies on:
 * for a repeated activation it returns the already-recorded logical Operation (so the caller reuses
 * its {@code logicalOperationId} instead of creating a second one), and for a genuinely new
 * activation it returns empty (so the caller mints a fresh logical Operation).
 *
 * <p>This test drives a faithful simulation of the activation handling that the pipeline performs:
 * it processes an arbitrary in-order stream of repeated activations (all still in flight — no
 * Operation has completed) and, for each one, consults {@code findLogicalOperation} to decide
 * whether to coalesce or to create a new logical Operation. The {@link OperationMapper} is mocked
 * to return exactly the persisted rows that the real {@code store + logical_idempotency_key} query
 * would return, so the service's own coalescing logic is exercised end to end. The asserted oracle
 * — "every {@code (store, logicalIdempotencyKey)} group resolves to exactly one logical Operation"
 * — is transcribed independently from Requirement 5.3 rather than from the implementation.
 */
@Label("Feature: advertising-workspace-rework, Property 14: Repeated activations coalesce into one logical Operation")
class RepeatedActivationCoalescingPropertyTest {

    /** A single operator activation of some control: it carries a Store and a click-coalescing key. */
    record Activation(UUID storeId, String logicalIdempotencyKey) {}

    /**
     * Feature: advertising-workspace-rework, Property 14: Repeated activations coalesce into one
     * logical Operation.
     *
     * <p>Validates: Requirements 5.3.
     *
     * <p>For any stream of activations (where the same {@code (store, logicalIdempotencyKey)} pair
     * recurs arbitrarily many times before completion), exactly one logical Operation exists per
     * distinct pair: repeated activations of the same control coalesce, while activations that
     * differ in Store or in key remain distinct logical Operations.
     */
    @Property(tries = 200)
    @Label("Property 14: repeated activations sharing a logicalIdempotencyKey coalesce into exactly one logical Operation per (store, key)")
    void repeatedActivationsCoalesceIntoOneLogicalOperation(
            @ForAll("activationStreams") List<Activation> activations) {

        OperationMapper operationMapper = Mockito.mock(OperationMapper.class);
        IdempotencyServiceImpl service = new IdempotencyServiceImpl(operationMapper);

        // The "operations" table as it would grow while the stream of activations is handled.
        List<OperationEntity> persisted = new ArrayList<>();

        // Independent bookkeeping for the oracle: the logicalOperationId chosen for each
        // (store, key) pair, and how many brand-new logical Operations the pipeline created.
        Map<String, UUID> logicalIdByPair = new HashMap<>();
        int createdLogicalOperations = 0;

        for (Activation activation : activations) {
            UUID storeId = activation.storeId();
            String key = activation.logicalIdempotencyKey();

            // Faithfully reproduce the store + logical_idempotency_key query the service issues:
            // the mapper returns the matching rows (most-recent attempt first), as the DB would.
            List<OperationEntity> matching = persisted.stream()
                    .filter(e -> e.getStoreId().equals(storeId)
                            && e.getLogicalIdempotencyKey().equals(key))
                    .sorted((a, b) -> Integer.compare(b.getAttemptNumber(), a.getAttemptNumber()))
                    .collect(Collectors.toList());
            when(operationMapper.selectList(any())).thenReturn(matching);

            Optional<OperationEntity> existing = service.findLogicalOperation(storeId, key);

            if (existing.isPresent()) {
                // Coalesce: this is a repeated click. Reuse the existing logical Operation and record
                // a further attempt under the SAME logicalOperationId — never a second logical op.
                UUID logicalId = existing.get().getLogicalOperationId();
                persisted.add(attempt(storeId, key, logicalId, nextAttemptNumber(persisted, logicalId)));
            } else {
                // New activation: mint a fresh logical Operation.
                UUID logicalId = UUID.randomUUID();
                persisted.add(attempt(storeId, key, logicalId, 1));
                createdLogicalOperations++;
            }

            // Track the logicalOperationId this pair settled on for the cross-check below.
            String pair = pairKey(storeId, key);
            logicalIdByPair.merge(pair,
                    persisted.get(persisted.size() - 1).getLogicalOperationId(),
                    (existingId, newId) -> {
                        // Once a pair has a logical id, coalescing must keep reusing the SAME id.
                        assertThat(newId)
                                .as("a repeated activation must reuse the pair's existing logicalOperationId")
                                .isEqualTo(existingId);
                        return existingId;
                    });
        }

        // --- Oracle (independent of the implementation) ------------------------------------------
        Set<String> distinctPairs = activations.stream()
                .map(a -> pairKey(a.storeId(), a.logicalIdempotencyKey()))
                .collect(Collectors.toSet());

        // (1) Exactly one logical Operation was created per distinct (store, key) pair: repeated
        //     activations of the same control coalesced rather than producing extra logical ops.
        assertThat(createdLogicalOperations)
                .as("exactly one logical Operation is created per distinct (store, logicalIdempotencyKey)")
                .isEqualTo(distinctPairs.size());

        // (2) Every persisted attempt sharing a (store, key) pair shares ONE logicalOperationId.
        Map<String, Set<UUID>> logicalIdsPerPair = persisted.stream().collect(Collectors.groupingBy(
                e -> pairKey(e.getStoreId(), e.getLogicalIdempotencyKey()),
                Collectors.mapping(OperationEntity::getLogicalOperationId, Collectors.toSet())));
        logicalIdsPerPair.forEach((pair, ids) -> assertThat(ids)
                .as("all attempts for one (store, key) belong to exactly one logical Operation")
                .hasSize(1));

        // (3) Distinct pairs never collapse into the same logical Operation (keying is exact).
        long distinctLogicalIds = persisted.stream()
                .map(OperationEntity::getLogicalOperationId)
                .distinct()
                .count();
        assertThat(distinctLogicalIds)
                .as("the number of logical Operations equals the number of distinct (store, key) pairs")
                .isEqualTo(distinctPairs.size());
    }

    private static int nextAttemptNumber(List<OperationEntity> persisted, UUID logicalId) {
        return (int) persisted.stream()
                .filter(e -> e.getLogicalOperationId().equals(logicalId))
                .count() + 1;
    }

    private static String pairKey(UUID storeId, String logicalIdempotencyKey) {
        return storeId + "::" + logicalIdempotencyKey;
    }

    private static OperationEntity attempt(UUID storeId, String key, UUID logicalId, int attemptNumber) {
        return OperationEntity.builder()
                .id(UUID.randomUUID())
                .storeId(storeId)
                .operationSource(OperationMachineValues.toValue(OperationSource.MANUAL))
                .operationScope(OperationMachineValues.toValue(OperationScope.PLATFORM_MUTATION))
                .entityType("campaign")
                .entityId(UUID.randomUUID())
                .logicalOperationId(logicalId)
                .logicalIdempotencyKey(key)
                .attemptId(UUID.randomUUID())
                .submissionIdempotencyKey(UUID.randomUUID().toString())
                .attemptNumber(attemptNumber)
                .syncState(OperationMachineValues.toValue(SyncState.PENDING))
                .build();
    }

    // --- generators ------------------------------------------------------------------------------

    /**
     * Streams of activations built from a small pool of Stores and keys so that the SAME
     * {@code (store, key)} pair recurs many times (modelling repeated clicks of one control) while
     * still mixing in other controls/Stores to prove coalescing is keyed exactly.
     */
    @Provide
    Arbitrary<List<Activation>> activationStreams() {
        Arbitrary<List<UUID>> storePool = Arbitraries.create(UUID::randomUUID).list().ofSize(3);
        Arbitrary<List<String>> keyPool =
                Arbitraries.create(() -> UUID.randomUUID().toString()).list().ofSize(4);

        return Combinators.combine(storePool, keyPool).flatAs((stores, keys) -> {
            Arbitrary<Activation> activation = Combinators.combine(
                            Arbitraries.of(stores),
                            Arbitraries.of(keys))
                    .as(Activation::new);
            return activation.list().ofMinSize(1).ofMaxSize(40);
        });
    }
}
