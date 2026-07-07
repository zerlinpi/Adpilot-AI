package com.adpilot.modules.advertising.operation;

import com.adpilot.common.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link BatchOperationServiceImpl} (task 6.2).
 *
 * <p>These exercise the two batch-level concerns the component owns over the delegated
 * {@code createOperation} pipeline: cross-store whole-batch rejection (Req 6.5, 25.4) and per-item
 * partial-success with a uniform creation-result list (Req 6.6, 6.7, 6.8, 36.2). The underlying
 * {@link OperationService} is mocked so the tests focus purely on the orchestration semantics.</p>
 *
 * <p>Validates: Requirements 6.5, 6.6, 6.7, 6.8, 25.4, 36.2.</p>
 */
class BatchOperationServiceImplTest {

    private OperationService operationService;
    private BatchOperationServiceImpl service;

    @BeforeEach
    void setUp() {
        operationService = mock(OperationService.class);
        service = new BatchOperationServiceImpl(operationService);
    }

    @Test
    void rejectsWholeBatchWhenItSpansMoreThanOneStore() {
        CreateOperationCommand a = platformMutation(UUID.randomUUID()).build();
        CreateOperationCommand b = platformMutation(UUID.randomUUID()).build();

        assertThatThrownBy(() -> service.createBatch(List.of(a, b)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("多个店铺");

        // The whole batch is refused — no item is attempted (Req 6.5: not silently excluded).
        verify(operationService, never()).createOperation(any());
    }

    @Test
    void rejectsNullOrEmptyBatch() {
        assertThatThrownBy(() -> service.createBatch(null)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.createBatch(List.of())).isInstanceOf(BusinessException.class);
    }

    @Test
    void attemptsEachItemIndependentlyAndReturnsUniformPerItemResults() {
        UUID storeId = UUID.randomUUID();
        CreateOperationCommand ok = platformMutation(storeId).build();
        CreateOperationCommand fails = platformMutation(storeId).build();

        when(operationService.createOperation(ok))
                .thenReturn(OperationResult.builder()
                        .operationId(UUID.randomUUID())
                        .storeId(storeId)
                        .operationScope(OperationScope.PLATFORM_MUTATION)
                        .syncState(SyncState.PENDING)
                        .entityType(ok.getEntityType())
                        .entityId(ok.getEntityId())
                        .coalesced(false)
                        .build());
        when(operationService.createOperation(fails))
                .thenThrow(new BusinessException(403, "FORBIDDEN", "缺少权限"));

        List<BatchItemResult> results = service.createBatch(List.of(ok, fails));

        assertThat(results).hasSize(2);

        BatchItemResult first = results.get(0);
        assertThat(first.isCreated()).isTrue();
        assertThat(first.getEntityId()).isEqualTo(ok.getEntityId());
        assertThat(first.getSyncState()).isEqualTo(SyncState.PENDING);
        assertThat(first.getFailureReason()).isNull();

        // The failing item does not abort the batch; it is reported with its record id + reason.
        BatchItemResult second = results.get(1);
        assertThat(second.isCreated()).isFalse();
        assertThat(second.getEntityId()).isEqualTo(fails.getEntityId());
        assertThat(second.getOperationId()).isNull();
        assertThat(second.getFailureReason()).isEqualTo("缺少权限");

        verify(operationService, times(2)).createOperation(any());
    }

    @Test
    void singleStoreBatchWithNullStoreIdItemDoesNotTripCrossStoreRejection() {
        UUID storeId = UUID.randomUUID();
        CreateOperationCommand ok = platformMutation(storeId).build();
        CreateOperationCommand malformed = platformMutation(null).build();

        when(operationService.createOperation(ok))
                .thenReturn(OperationResult.builder()
                        .operationId(UUID.randomUUID())
                        .storeId(storeId)
                        .operationScope(OperationScope.PLATFORM_MUTATION)
                        .syncState(SyncState.LOCAL_ONLY)
                        .entityType(ok.getEntityType())
                        .entityId(ok.getEntityId())
                        .coalesced(false)
                        .build());
        when(operationService.createOperation(malformed))
                .thenThrow(new BusinessException(400, "INVALID_OPERATION", "storeId is required"));

        List<BatchItemResult> results = service.createBatch(List.of(ok, malformed));

        // The malformed item fails per-item rather than rejecting the whole batch (Req 6.6).
        assertThat(results).hasSize(2);
        assertThat(results.get(0).isCreated()).isTrue();
        assertThat(results.get(1).isCreated()).isFalse();
        assertThat(results.get(1).getFailureReason()).isEqualTo("storeId is required");
    }

    @Test
    void reportsCoalescedItemAsCreated() {
        UUID storeId = UUID.randomUUID();
        CreateOperationCommand dup = platformMutation(storeId).build();
        when(operationService.createOperation(dup))
                .thenReturn(OperationResult.builder()
                        .operationId(UUID.randomUUID())
                        .storeId(storeId)
                        .operationScope(OperationScope.PLATFORM_MUTATION)
                        .syncState(SyncState.PENDING)
                        .entityType(dup.getEntityType())
                        .entityId(dup.getEntityId())
                        .coalesced(true)
                        .build());

        List<BatchItemResult> results = service.createBatch(List.of(dup));

        assertThat(results).hasSize(1);
        assertThat(results.get(0).isCreated()).isTrue();
        assertThat(results.get(0).isCoalesced()).isTrue();
    }

    // --- helpers ---------------------------------------------------------------------------------

    private static CreateOperationCommand.CreateOperationCommandBuilder platformMutation(UUID storeId) {
        return CreateOperationCommand.builder()
                .storeId(storeId)
                .operationSource(OperationSource.MANUAL)
                .operationScope(OperationScope.PLATFORM_MUTATION)
                .entityType("campaign")
                .entityId(UUID.randomUUID())
                .field("status")
                .beforeValue("enabled")
                .afterValue("paused")
                .logicalIdempotencyKey(UUID.randomUUID().toString());
    }
}
