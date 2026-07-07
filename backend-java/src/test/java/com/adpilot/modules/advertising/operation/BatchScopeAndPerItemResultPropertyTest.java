package com.adpilot.modules.advertising.operation;

import com.adpilot.common.exception.BusinessException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Label;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Property-based test for the batch-level semantics of
 * {@link BatchOperationServiceImpl#createBatch(java.util.List)} (task 6.7).
 *
 * <p>Feature: advertising-workspace-rework, Property 20: Batch scope and per-item result semantics.
 *
 * <p>Validates: Requirements 6.5, 6.6, 6.7, 6.8, 36.2, 25.4.
 *
 * <p><em>For any</em> Bulk_Operation, if it references records belonging to more than one Store the
 * whole batch is rejected; otherwise each referenced item is attempted independently and the API
 * returns a uniform per-item result identifying the record id, the creation result, and the failure
 * reason where creation failed, representing creation (not platform application).
 *
 * <p>The underlying {@link OperationService} is mocked so the test isolates the two batch-level
 * concerns the component owns. Each generated batch carries a pool of Stores (sized 1–3) and a list
 * of items, each item assigned to one of those Stores and pre-designated to either create
 * successfully or be rejected. The mocked {@code createOperation} honours that per-item designation,
 * which lets the property assert that:
 * <ul>
 *   <li>a batch spanning more than one Store is rejected as a whole and no item is attempted
 *       (Req 6.5, 25.4 — cross-store records are NOT silently excluded); and</li>
 *   <li>a single-Store batch returns exactly one uniform {@link BatchItemResult} per item, in input
 *       order, each carrying the record id (Req 6.7) and the creation result, with a failure reason
 *       where creation failed (Req 6.6, 6.7); and</li>
 *   <li>failures are isolated — every designated-success item still creates even when interleaved
 *       with failing items, so one item's failure never aborts the others (Req 6.6), and every item
 *       is attempted independently.</li>
 * </ul>
 *
 * <p>The result represents <em>creation</em>, never platform application (Req 6.8, 36.2):
 * {@link BatchItemResult} exposes the persisted {@code operationId}/lifecycle state for a created
 * item and has no notion of platform-applied success, so a created item can never be read as "Amazon
 * applied it".
 */
@Label("Feature: advertising-workspace-rework, Property 20: Batch scope and per-item result semantics")
class BatchScopeAndPerItemResultPropertyTest {

    /** One generated batch item: which Store it belongs to and whether its creation should succeed. */
    record ItemSpec(UUID storeId, boolean shouldSucceed) {}

    /** A generated batch: the Store pool it draws from, and its ordered items. */
    record Batch(List<UUID> storePool, List<ItemSpec> items) {}

    /**
     * Feature: advertising-workspace-rework, Property 20: Batch scope and per-item result semantics.
     *
     * <p>Validates: Requirements 6.5, 6.6, 6.7, 6.8, 36.2, 25.4.
     */
    @Property(tries = 100)
    @Label("Property 20: cross-store batches are rejected wholesale; single-store batches return a uniform, order-preserving, failure-isolated per-item creation result")
    void batchScopeAndPerItemResultSemantics(@ForAll("batches") Batch batch) {
        OperationService operationService = mock(OperationService.class);
        BatchOperationServiceImpl service = new BatchOperationServiceImpl(operationService);

        // Build one command per item, each with a unique entityId so its per-item result can be
        // correlated back to the row the operator selected (Req 6.7).
        List<CreateOperationCommand> commands = new ArrayList<>(batch.items().size());
        Map<UUID, Boolean> outcomeByEntity = new HashMap<>();
        for (ItemSpec spec : batch.items()) {
            UUID entityId = UUID.randomUUID();
            commands.add(CreateOperationCommand.builder()
                    .storeId(spec.storeId())
                    .operationSource(OperationSource.MANUAL)
                    .operationScope(OperationScope.PLATFORM_MUTATION)
                    .entityType("campaign")
                    .entityId(entityId)
                    .field("status")
                    .beforeValue("enabled")
                    .afterValue("paused")
                    .logicalIdempotencyKey(UUID.randomUUID().toString())
                    .build());
            outcomeByEntity.put(entityId, spec.shouldSucceed());
        }

        // The delegated pipeline succeeds or is rejected per the item's designated outcome. A
        // success returns a created Operation (a creation result, not a platform-applied result).
        when(operationService.createOperation(any())).thenAnswer(invocation -> {
            CreateOperationCommand cmd = invocation.getArgument(0);
            if (Boolean.TRUE.equals(outcomeByEntity.get(cmd.getEntityId()))) {
                return OperationResult.builder()
                        .operationId(UUID.randomUUID())
                        .storeId(cmd.getStoreId())
                        .operationScope(OperationScope.PLATFORM_MUTATION)
                        .syncState(SyncState.PENDING)
                        .entityType(cmd.getEntityType())
                        .entityId(cmd.getEntityId())
                        .coalesced(false)
                        .build();
            }
            throw new BusinessException(403, "FORBIDDEN", "拒绝创建 " + cmd.getEntityId());
        });

        long distinctStores = commands.stream()
                .map(CreateOperationCommand::getStoreId)
                .distinct()
                .count();

        if (distinctStores > 1) {
            // Cross-store batch (Req 6.5, 25.4): the WHOLE batch is rejected up front and NO item is
            // attempted — cross-store records are not silently excluded.
            assertThatThrownBy(() -> service.createBatch(commands))
                    .isInstanceOf(BusinessException.class);
            verify(operationService, never()).createOperation(any());
            return;
        }

        // Single-Store batch (Req 6.6, 6.7): partial-success semantics — a uniform per-item result.
        List<BatchItemResult> results = service.createBatch(commands);

        // One result per item, in the same order as the input.
        assertThat(results).hasSize(commands.size());
        for (int i = 0; i < commands.size(); i++) {
            CreateOperationCommand cmd = commands.get(i);
            BatchItemResult result = results.get(i);

            // Every item — succeeded or failed — carries the referencing record id (Req 6.7).
            assertThat(result.getEntityId())
                    .as("per-item result at index %s preserves input order and record id", i)
                    .isEqualTo(cmd.getEntityId());

            boolean expectedSuccess = outcomeByEntity.get(cmd.getEntityId());
            assertThat(result.isCreated())
                    .as("creation result for item %s reflects the designated outcome", i)
                    .isEqualTo(expectedSuccess);

            if (expectedSuccess) {
                // A created item carries its persisted Operation identity (the creation result,
                // Req 6.8) and no failure reason.
                assertThat(result.getOperationId())
                        .as("a created item carries the persisted Operation id (creation result)")
                        .isNotNull();
                assertThat(result.getFailureReason())
                        .as("a created item has no failure reason")
                        .isNull();
            } else {
                // A failed item is reported with its record id + reason and no Operation id — its
                // failure does not abort the batch (Req 6.6).
                assertThat(result.getOperationId())
                        .as("a failed item has no Operation id")
                        .isNull();
                assertThat(result.getFailureReason())
                        .as("a failed item carries the reason creation failed")
                        .isNotBlank();
            }
        }

        // Every item is attempted independently — one item's failure never aborts the others
        // (Req 6.6). This holds regardless of where the failures fall in the order.
        verify(operationService, times(commands.size())).createOperation(any());
    }

    // --- generators ------------------------------------------------------------------------------

    /**
     * Batches drawing items from a Store pool sized 1–3 so the generated space covers both
     * single-Store batches (partial-success path) and cross-Store batches (wholesale rejection).
     */
    @Provide
    Arbitrary<Batch> batches() {
        return Arbitraries.integers().between(1, 3).flatMap(poolSize -> {
            List<UUID> pool = new ArrayList<>(poolSize);
            for (int i = 0; i < poolSize; i++) {
                pool.add(UUID.randomUUID());
            }
            Arbitrary<ItemSpec> item = Combinators.combine(
                            Arbitraries.integers().between(0, poolSize - 1),
                            Arbitraries.of(true, false))
                    .as((storeIndex, shouldSucceed) -> new ItemSpec(pool.get(storeIndex), shouldSucceed));
            return item.list().ofMinSize(1).ofMaxSize(6)
                    .map(items -> new Batch(pool, items));
        });
    }
}
