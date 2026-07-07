package com.adpilot.modules.advertising.hosting;

import com.adpilot.common.exception.BusinessException;
import com.adpilot.modules.advertising.entity.OperationEntity;
import com.adpilot.modules.advertising.mapper.OperationMapper;
import com.adpilot.modules.advertising.operation.CreateOperationCommand;
import com.adpilot.modules.advertising.operation.OperationJsonCodec;
import com.adpilot.modules.advertising.operation.OperationMachineValues;
import com.adpilot.modules.advertising.operation.OperationResult;
import com.adpilot.modules.advertising.operation.OperationScope;
import com.adpilot.modules.advertising.operation.OperationService;
import com.adpilot.modules.advertising.operation.OperationSource;
import com.adpilot.modules.advertising.operation.SyncState;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link RollbackServiceImpl}.
 *
 * Validates: Requirements 10.1, 10.2, 10.4, 10.5, 10.6
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RollbackServiceImpl")
class RollbackServiceImplTest {

    @Mock
    private OperationMapper operationMapper;

    @Mock
    private OperationService operationService;

    @Captor
    private ArgumentCaptor<CreateOperationCommand> commandCaptor;

    private RollbackServiceImpl rollbackService;
    private OperationJsonCodec jsonCodec;

    @BeforeEach
    void setUp() {
        jsonCodec = new OperationJsonCodec(new ObjectMapper());
        rollbackService = new RollbackServiceImpl(
                operationMapper, operationService, jsonCodec, new ReversibilityClassifier());
    }

    // ---- Test fixtures -------------------------------------------------------------------------

    private OperationEntity buildEffectiveReversibleOperation() {
        UUID opId = UUID.randomUUID();
        OperationEntity entity = new OperationEntity();
        entity.setId(opId);
        entity.setStoreId(UUID.randomUUID());
        entity.setOperationSource(OperationMachineValues.toValue(OperationSource.AI_HOSTING));
        entity.setOperationScope(OperationMachineValues.toValue(OperationScope.PLATFORM_MUTATION));
        entity.setEntityType("keyword");
        entity.setEntityId(UUID.randomUUID());
        entity.setField("bid");
        entity.setSyncState(OperationMachineValues.toValue(SyncState.EFFECTIVE));
        entity.setReversible(true);
        entity.setBeforeValue("1.50");
        entity.setAfterValue("2.00");
        entity.setAffectedCount(1);
        entity.setCreatedAt(LocalDateTime.now().minusHours(2));
        entity.setLogicalOperationId(UUID.randomUUID());
        return entity;
    }

    private OperationResult buildMockOperationResult(UUID operationId, UUID storeId) {
        return OperationResult.builder()
                .operationId(operationId)
                .logicalOperationId(UUID.randomUUID())
                .storeId(storeId)
                .operationScope(OperationScope.PLATFORM_MUTATION)
                .syncState(SyncState.PENDING)
                .entityType("keyword")
                .entityId(UUID.randomUUID())
                .coalesced(false)
                .build();
    }

    // ---- Happy path: effective + reversible → rollback created ---------------------------------

    @Nested
    @DisplayName("Happy path - effective + reversible → rollback created")
    class HappyPath {

        @Test
        @DisplayName("Creates compensating operation with swapped before/after values")
        void createsCompensatingOperationWithSwappedValues() {
            OperationEntity original = buildEffectiveReversibleOperation();
            UUID newOpId = UUID.randomUUID();

            when(operationMapper.selectById(original.getId().toString())).thenReturn(original);
            when(operationMapper.selectList(any())).thenReturn(Collections.emptyList());
            when(operationService.createOperation(any(CreateOperationCommand.class)))
                    .thenReturn(buildMockOperationResult(newOpId, original.getStoreId()));

            RollbackResult result = rollbackService.rollback(original.getId());

            assertThat(result.isConfirmationRequired()).isFalse();
            assertThat(result.getOperationResult()).isNotNull();
            assertThat(result.getOperationResult().getOperationId()).isEqualTo(newOpId);

            verify(operationService).createOperation(commandCaptor.capture());
            CreateOperationCommand cmd = commandCaptor.getValue();

            // Verify before/after are swapped (Req 10.2):
            // compensating.beforeValue = original.afterValue (deserialized)
            // compensating.afterValue = original.beforeValue (deserialized)
            // Note: JSON round-trip may normalize decimals (e.g. 2.00 → 2.0)
            Object expectedBefore = jsonCodec.fromJson(original.getAfterValue(), Object.class);
            Object expectedAfter = jsonCodec.fromJson(original.getBeforeValue(), Object.class);
            assertThat(cmd.getBeforeValue()).isEqualTo(expectedBefore);
            assertThat(cmd.getAfterValue()).isEqualTo(expectedAfter);

            // Verify OperationSource.MANUAL (Req 10.2)
            assertThat(cmd.getOperationSource()).isEqualTo(OperationSource.MANUAL);

            // Verify parentOperationId = original (Req 10.2)
            assertThat(cmd.getParentOperationId()).isEqualTo(original.getId());

            // Verify scope is PLATFORM_MUTATION (routes through standard pipeline, Req 10.4)
            assertThat(cmd.getOperationScope()).isEqualTo(OperationScope.PLATFORM_MUTATION);

            // Verify entity info matches original
            assertThat(cmd.getStoreId()).isEqualTo(original.getStoreId());
            assertThat(cmd.getEntityType()).isEqualTo(original.getEntityType());
            assertThat(cmd.getEntityId()).isEqualTo(original.getEntityId());
            assertThat(cmd.getField()).isEqualTo(original.getField());

            // Verify the compensating operation is itself reversible
            assertThat(cmd.getReversible()).isTrue();
        }

        @Test
        @DisplayName("Compensating operation for budget change also swaps correctly")
        void budgetChangeRollback() {
            OperationEntity original = buildEffectiveReversibleOperation();
            original.setEntityType("campaign");
            original.setField("budget");
            original.setBeforeValue("100.00");
            original.setAfterValue("150.00");

            UUID newOpId = UUID.randomUUID();
            when(operationMapper.selectById(original.getId().toString())).thenReturn(original);
            when(operationMapper.selectList(any())).thenReturn(Collections.emptyList());
            when(operationService.createOperation(any(CreateOperationCommand.class)))
                    .thenReturn(buildMockOperationResult(newOpId, original.getStoreId()));

            RollbackResult result = rollbackService.rollback(original.getId());

            assertThat(result.isConfirmationRequired()).isFalse();
            verify(operationService).createOperation(commandCaptor.capture());
            CreateOperationCommand cmd = commandCaptor.getValue();

            // After rollback: before = original.afterValue (150.00), after = original.beforeValue (100.00)
            // Values are deserialized from JSON and compared as domain objects
            Object expectedBefore = jsonCodec.fromJson("150.00", Object.class);
            Object expectedAfter = jsonCodec.fromJson("100.00", Object.class);
            assertThat(cmd.getBeforeValue()).isEqualTo(expectedBefore);
            assertThat(cmd.getAfterValue()).isEqualTo(expectedAfter);
        }
    }

    // ---- Rejection: non-effective operation cannot be rolled back -------------------------------

    @Nested
    @DisplayName("Rejection - non-effective operation")
    class NonEffective {

        @Test
        @DisplayName("Pending operation throws OPERATION_NOT_EFFECTIVE")
        void pendingOperationRejected() {
            OperationEntity original = buildEffectiveReversibleOperation();
            original.setSyncState(OperationMachineValues.toValue(SyncState.PENDING));

            when(operationMapper.selectById(original.getId().toString())).thenReturn(original);

            assertThatThrownBy(() -> rollbackService.rollback(original.getId()))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("status", 409)
                    .hasFieldOrPropertyWithValue("code", "OPERATION_NOT_EFFECTIVE");

            verify(operationService, never()).createOperation(any());
        }

        @Test
        @DisplayName("Failed operation throws OPERATION_NOT_EFFECTIVE")
        void failedOperationRejected() {
            OperationEntity original = buildEffectiveReversibleOperation();
            original.setSyncState(OperationMachineValues.toValue(SyncState.FAILED));

            when(operationMapper.selectById(original.getId().toString())).thenReturn(original);

            assertThatThrownBy(() -> rollbackService.rollback(original.getId()))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("status", 409)
                    .hasFieldOrPropertyWithValue("code", "OPERATION_NOT_EFFECTIVE");

            verify(operationService, never()).createOperation(any());
        }

        @Test
        @DisplayName("Submitted operation throws OPERATION_NOT_EFFECTIVE")
        void submittedOperationRejected() {
            OperationEntity original = buildEffectiveReversibleOperation();
            original.setSyncState(OperationMachineValues.toValue(SyncState.SUBMITTED));

            when(operationMapper.selectById(original.getId().toString())).thenReturn(original);

            assertThatThrownBy(() -> rollbackService.rollback(original.getId()))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("status", 409)
                    .hasFieldOrPropertyWithValue("code", "OPERATION_NOT_EFFECTIVE");

            verify(operationService, never()).createOperation(any());
        }
    }

    // ---- Rejection: non-reversible operation cannot be rolled back ------------------------------

    @Nested
    @DisplayName("Rejection - non-reversible operation")
    class NonReversible {

        @Test
        @DisplayName("Keyword addition (reversible=false) throws OPERATION_NOT_REVERSIBLE")
        void keywordAdditionRejected() {
            OperationEntity original = buildEffectiveReversibleOperation();
            original.setField("keyword");
            original.setReversible(false);

            when(operationMapper.selectById(original.getId().toString())).thenReturn(original);

            assertThatThrownBy(() -> rollbackService.rollback(original.getId()))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("status", 409)
                    .hasFieldOrPropertyWithValue("code", "OPERATION_NOT_REVERSIBLE");

            verify(operationService, never()).createOperation(any());
        }

        @Test
        @DisplayName("Negative keyword addition (reversible=false) throws OPERATION_NOT_REVERSIBLE")
        void negativeKeywordAdditionRejected() {
            OperationEntity original = buildEffectiveReversibleOperation();
            original.setField("negative_keyword");
            original.setReversible(false);

            when(operationMapper.selectById(original.getId().toString())).thenReturn(original);

            assertThatThrownBy(() -> rollbackService.rollback(original.getId()))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("status", 409)
                    .hasFieldOrPropertyWithValue("code", "OPERATION_NOT_REVERSIBLE");

            verify(operationService, never()).createOperation(any());
        }
    }

    // ---- Warning: overlapping subsequent operations detected ------------------------------------

    @Nested
    @DisplayName("Warning - overlapping subsequent operations")
    class OverlappingOperations {

        @Test
        @DisplayName("Detects overlapping operations and requires confirmation")
        void detectsOverlapsAndRequiresConfirmation() {
            OperationEntity original = buildEffectiveReversibleOperation();

            // Create a subsequent effective operation on the same entity+field
            OperationEntity subsequent = buildEffectiveReversibleOperation();
            subsequent.setId(UUID.randomUUID());
            subsequent.setEntityType(original.getEntityType());
            subsequent.setEntityId(original.getEntityId());
            subsequent.setField(original.getField());
            subsequent.setSyncState(OperationMachineValues.toValue(SyncState.EFFECTIVE));
            subsequent.setCreatedAt(original.getCreatedAt().plusHours(1));

            when(operationMapper.selectById(original.getId().toString())).thenReturn(original);
            when(operationMapper.selectList(any())).thenReturn(List.of(subsequent));

            RollbackResult result = rollbackService.rollback(original.getId());

            assertThat(result.isConfirmationRequired()).isTrue();
            assertThat(result.getConflictingOperationIds()).containsExactly(subsequent.getId());
            assertThat(result.getWarningMessage()).contains("subsequent operation");
            assertThat(result.getOperationResult()).isNull();

            // Should NOT create the compensating operation yet
            verify(operationService, never()).createOperation(any());
        }

        @Test
        @DisplayName("Multiple overlapping operations all reported")
        void multipleOverlapsReported() {
            OperationEntity original = buildEffectiveReversibleOperation();

            OperationEntity sub1 = buildEffectiveReversibleOperation();
            sub1.setId(UUID.randomUUID());
            sub1.setEntityType(original.getEntityType());
            sub1.setEntityId(original.getEntityId());
            sub1.setField(original.getField());
            sub1.setCreatedAt(original.getCreatedAt().plusHours(1));

            OperationEntity sub2 = buildEffectiveReversibleOperation();
            sub2.setId(UUID.randomUUID());
            sub2.setEntityType(original.getEntityType());
            sub2.setEntityId(original.getEntityId());
            sub2.setField(original.getField());
            sub2.setCreatedAt(original.getCreatedAt().plusHours(2));

            when(operationMapper.selectById(original.getId().toString())).thenReturn(original);
            when(operationMapper.selectList(any())).thenReturn(List.of(sub1, sub2));

            RollbackResult result = rollbackService.rollback(original.getId());

            assertThat(result.isConfirmationRequired()).isTrue();
            assertThat(result.getConflictingOperationIds()).containsExactlyInAnyOrder(sub1.getId(), sub2.getId());
        }
    }

    // ---- Confirmation: rollback proceeds despite overlaps when confirmed ------------------------

    @Nested
    @DisplayName("Confirmation - rollback proceeds despite overlaps")
    class ConfirmationFlow {

        @Test
        @DisplayName("rollbackWithConfirmation creates compensating operation despite overlaps")
        void confirmedRollbackProceeds() {
            OperationEntity original = buildEffectiveReversibleOperation();
            UUID newOpId = UUID.randomUUID();

            when(operationMapper.selectById(original.getId().toString())).thenReturn(original);
            when(operationService.createOperation(any(CreateOperationCommand.class)))
                    .thenReturn(buildMockOperationResult(newOpId, original.getStoreId()));

            RollbackResult result = rollbackService.rollbackWithConfirmation(original.getId());

            assertThat(result.isConfirmationRequired()).isFalse();
            assertThat(result.getOperationResult()).isNotNull();
            assertThat(result.getOperationResult().getOperationId()).isEqualTo(newOpId);

            // Verify the compensating operation was created
            verify(operationService).createOperation(commandCaptor.capture());
            CreateOperationCommand cmd = commandCaptor.getValue();
            assertThat(cmd.getOperationSource()).isEqualTo(OperationSource.MANUAL);
            assertThat(cmd.getParentOperationId()).isEqualTo(original.getId());
        }

        @Test
        @DisplayName("rollbackWithConfirmation still validates effective state")
        void confirmedRollbackValidatesState() {
            OperationEntity original = buildEffectiveReversibleOperation();
            original.setSyncState(OperationMachineValues.toValue(SyncState.PENDING));

            when(operationMapper.selectById(original.getId().toString())).thenReturn(original);

            assertThatThrownBy(() -> rollbackService.rollbackWithConfirmation(original.getId()))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("code", "OPERATION_NOT_EFFECTIVE");
        }

        @Test
        @DisplayName("rollbackWithConfirmation still validates reversibility")
        void confirmedRollbackValidatesReversibility() {
            OperationEntity original = buildEffectiveReversibleOperation();
            original.setReversible(false);

            when(operationMapper.selectById(original.getId().toString())).thenReturn(original);

            assertThatThrownBy(() -> rollbackService.rollbackWithConfirmation(original.getId()))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("code", "OPERATION_NOT_REVERSIBLE");
        }
    }

    // ---- Edge cases -----------------------------------------------------------------------------

    @Nested
    @DisplayName("Edge cases")
    class EdgeCases {

        @Test
        @DisplayName("Null operationId throws 400")
        void nullOperationIdThrows() {
            assertThatThrownBy(() -> rollbackService.rollback(null))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("status", 400);
        }

        @Test
        @DisplayName("Non-existent operation throws 404")
        void nonExistentOperationThrows() {
            UUID fakeId = UUID.randomUUID();
            when(operationMapper.selectById(fakeId.toString())).thenReturn(null);

            assertThatThrownBy(() -> rollbackService.rollback(fakeId))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("status", 404)
                    .hasFieldOrPropertyWithValue("code", "OPERATION_NOT_FOUND");
        }

        @Test
        @DisplayName("State change (field=state) rollback also works")
        void stateChangeRollback() {
            OperationEntity original = buildEffectiveReversibleOperation();
            original.setEntityType("campaign");
            original.setField("state");
            original.setBeforeValue("\"ENABLED\"");
            original.setAfterValue("\"PAUSED\"");

            UUID newOpId = UUID.randomUUID();
            when(operationMapper.selectById(original.getId().toString())).thenReturn(original);
            when(operationMapper.selectList(any())).thenReturn(Collections.emptyList());
            when(operationService.createOperation(any(CreateOperationCommand.class)))
                    .thenReturn(buildMockOperationResult(newOpId, original.getStoreId()));

            RollbackResult result = rollbackService.rollback(original.getId());

            assertThat(result.isConfirmationRequired()).isFalse();
            verify(operationService).createOperation(commandCaptor.capture());
            CreateOperationCommand cmd = commandCaptor.getValue();

            // After rollback: should go from PAUSED back to ENABLED
            // Values deserialized from JSON strings
            assertThat(cmd.getBeforeValue()).isEqualTo("PAUSED");
            assertThat(cmd.getAfterValue()).isEqualTo("ENABLED");
        }
    }
}
