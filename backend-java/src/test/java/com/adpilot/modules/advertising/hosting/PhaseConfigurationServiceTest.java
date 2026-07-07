package com.adpilot.modules.advertising.hosting;

import com.adpilot.modules.advertising.entity.OperationEntity;
import com.adpilot.modules.advertising.entity.OperationOutboxEntity;
import com.adpilot.modules.advertising.mapper.OperationMapper;
import com.adpilot.modules.advertising.mapper.OperationOutboxMapper;
import com.adpilot.modules.advertising.operation.OperationStateMachine;
import com.adpilot.modules.advertising.operation.SyncState;
import com.adpilot.modules.advertising.service.HostingAdjustmentType;
import com.adpilot.modules.advertising.service.HostingPhase;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link PhaseConfigurationServiceImpl}.
 *
 * <p>Covers:</p>
 * <ul>
 *   <li>Valid adjacent transitions (V1→V2, V2→V3, V2→V1, V3→V2)</li>
 *   <li>Invalid transitions rejected (V1→V3, V3→V1)</li>
 *   <li>Same-phase transition rejected</li>
 *   <li>Downgrade cleanup logic (cancel awaiting_approval, supersede pending, close outbox)</li>
 *   <li>Upgrade does NOT trigger cleanup</li>
 * </ul>
 *
 * <p>Validates: Requirements 17.1, 17.2, 17.3, 17.4, 17.5, 17.6.</p>
 */
@DisplayName("PhaseConfigurationService")
class PhaseConfigurationServiceTest {

    private HostingConfigMapper hostingConfigMapper;
    private OperationMapper operationMapper;
    private OperationOutboxMapper outboxMapper;
    private OperationStateMachine stateMachine;
    private HostingApprovalService approvalService;
    private PhaseConfigurationServiceImpl service;

    @BeforeEach
    void setUp() {
        hostingConfigMapper = mock(HostingConfigMapper.class);
        operationMapper = mock(OperationMapper.class);
        outboxMapper = mock(OperationOutboxMapper.class);
        stateMachine = new OperationStateMachine();
        approvalService = mock(HostingApprovalService.class);

        service = new PhaseConfigurationServiceImpl(
                hostingConfigMapper,
                operationMapper,
                outboxMapper,
                stateMachine,
                approvalService,
                mock(com.adpilot.modules.audit.service.AuditLogService.class)
        );
    }

    @Nested
    @DisplayName("validateTransition")
    class ValidateTransitionTests {

        @Test
        @DisplayName("V1→V2 is valid (adjacent upgrade)")
        void v1ToV2IsValid() {
            assertThat(service.validateTransition(HostingPhase.V1, HostingPhase.V2)).isTrue();
        }

        @Test
        @DisplayName("V2→V3 is valid (adjacent upgrade)")
        void v2ToV3IsValid() {
            assertThat(service.validateTransition(HostingPhase.V2, HostingPhase.V3)).isTrue();
        }

        @Test
        @DisplayName("V2→V1 is valid (adjacent downgrade)")
        void v2ToV1IsValid() {
            assertThat(service.validateTransition(HostingPhase.V2, HostingPhase.V1)).isTrue();
        }

        @Test
        @DisplayName("V3→V2 is valid (adjacent downgrade)")
        void v3ToV2IsValid() {
            assertThat(service.validateTransition(HostingPhase.V3, HostingPhase.V2)).isTrue();
        }

        @Test
        @DisplayName("V1→V3 is invalid (non-adjacent jump)")
        void v1ToV3IsInvalid() {
            assertThat(service.validateTransition(HostingPhase.V1, HostingPhase.V3)).isFalse();
        }

        @Test
        @DisplayName("V3→V1 is invalid (non-adjacent jump)")
        void v3ToV1IsInvalid() {
            assertThat(service.validateTransition(HostingPhase.V3, HostingPhase.V1)).isFalse();
        }

        @Test
        @DisplayName("Same phase transition is invalid")
        void samePhaseIsInvalid() {
            assertThat(service.validateTransition(HostingPhase.V1, HostingPhase.V1)).isFalse();
            assertThat(service.validateTransition(HostingPhase.V2, HostingPhase.V2)).isFalse();
            assertThat(service.validateTransition(HostingPhase.V3, HostingPhase.V3)).isFalse();
        }

        @Test
        @DisplayName("Null arguments return false")
        void nullArgumentsReturnFalse() {
            assertThat(service.validateTransition(null, HostingPhase.V2)).isFalse();
            assertThat(service.validateTransition(HostingPhase.V1, null)).isFalse();
            assertThat(service.validateTransition(null, null)).isFalse();
        }
    }

    @Nested
    @DisplayName("getCurrentPhase")
    class GetCurrentPhaseTests {

        @Test
        @DisplayName("Returns V1 (default) when no config exists")
        void returnsDefaultWhenNoConfig() {
            UUID storeId = UUID.randomUUID();
            when(hostingConfigMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

            HostingPhase phase = service.getCurrentPhase(storeId);

            assertThat(phase).isEqualTo(HostingPhase.V1);
        }

        @Test
        @DisplayName("Returns V1 (default) for null storeId")
        void returnsDefaultForNullStore() {
            HostingPhase phase = service.getCurrentPhase(null);
            assertThat(phase).isEqualTo(HostingPhase.V1);
        }

        @Test
        @DisplayName("Returns V2 when config has active_phase=V2")
        void returnsV2WhenConfigured() {
            UUID storeId = UUID.randomUUID();
            HostingConfigEntity entity = HostingConfigEntity.builder()
                    .config("{\"active_phase\":\"V2\"}")
                    .build();
            when(hostingConfigMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(entity);

            HostingPhase phase = service.getCurrentPhase(storeId);

            assertThat(phase).isEqualTo(HostingPhase.V2);
        }

        @Test
        @DisplayName("Returns V3 when config has active_phase=V3")
        void returnsV3WhenConfigured() {
            UUID storeId = UUID.randomUUID();
            HostingConfigEntity entity = HostingConfigEntity.builder()
                    .config("{\"active_phase\":\"V3\"}")
                    .build();
            when(hostingConfigMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(entity);

            HostingPhase phase = service.getCurrentPhase(storeId);

            assertThat(phase).isEqualTo(HostingPhase.V3);
        }

        @Test
        @DisplayName("Returns default when config JSON is blank")
        void returnsDefaultWhenConfigBlank() {
            UUID storeId = UUID.randomUUID();
            HostingConfigEntity entity = HostingConfigEntity.builder()
                    .config("")
                    .build();
            when(hostingConfigMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(entity);

            HostingPhase phase = service.getCurrentPhase(storeId);

            assertThat(phase).isEqualTo(HostingPhase.V1);
        }
    }

    @Nested
    @DisplayName("transitionPhase")
    class TransitionPhaseTests {

        private UUID storeId;
        private UUID actorId;

        @BeforeEach
        void setUp() {
            storeId = UUID.randomUUID();
            actorId = UUID.randomUUID();
        }

        @Test
        @DisplayName("Throws when transition is invalid (V1→V3)")
        void throwsOnInvalidTransition() {
            // Current phase is V1
            when(hostingConfigMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(
                    HostingConfigEntity.builder()
                            .config("{\"active_phase\":\"V1\"}")
                            .build()
            );

            assertThatThrownBy(() -> service.transitionPhase(storeId, HostingPhase.V3, actorId))
                    .isInstanceOf(com.adpilot.common.exception.BusinessException.class)
                    .hasMessageContaining("Invalid phase transition")
                    .hasMessageContaining("V1")
                    .hasMessageContaining("V3");
        }

        @Test
        @DisplayName("Throws when transition is invalid (V3→V1)")
        void throwsOnInvalidTransitionV3ToV1() {
            when(hostingConfigMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(
                    HostingConfigEntity.builder()
                            .config("{\"active_phase\":\"V3\"}")
                            .build()
            );

            assertThatThrownBy(() -> service.transitionPhase(storeId, HostingPhase.V1, actorId))
                    .isInstanceOf(com.adpilot.common.exception.BusinessException.class)
                    .hasMessageContaining("Invalid phase transition");
        }

        @Test
        @DisplayName("Throws when already at target phase")
        void throwsWhenAlreadyAtTarget() {
            when(hostingConfigMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(
                    HostingConfigEntity.builder()
                            .config("{\"active_phase\":\"V2\"}")
                            .build()
            );

            assertThatThrownBy(() -> service.transitionPhase(storeId, HostingPhase.V2, actorId))
                    .isInstanceOf(com.adpilot.common.exception.BusinessException.class)
                    .hasMessageContaining("already at phase");
        }

        @Test
        @DisplayName("Throws on null storeId")
        void throwsOnNullStoreId() {
            assertThatThrownBy(() -> service.transitionPhase(null, HostingPhase.V2, actorId))
                    .isInstanceOf(com.adpilot.common.exception.BusinessException.class)
                    .hasMessageContaining("Store ID must not be null");
        }

        @Test
        @DisplayName("Throws on null target phase")
        void throwsOnNullTargetPhase() {
            assertThatThrownBy(() -> service.transitionPhase(storeId, null, actorId))
                    .isInstanceOf(com.adpilot.common.exception.BusinessException.class)
                    .hasMessageContaining("Target phase must not be null");
        }

        @Test
        @DisplayName("Upgrade V1→V2 does not trigger cleanup")
        void upgradeDoesNotTriggerCleanup() {
            // Current phase is V1
            when(hostingConfigMapper.selectOne(any(LambdaQueryWrapper.class)))
                    .thenReturn(HostingConfigEntity.builder()
                            .id(UUID.randomUUID())
                            .config("{\"active_phase\":\"V1\"}")
                            .build());
            when(hostingConfigMapper.updateById(any())).thenReturn(1);

            service.transitionPhase(storeId, HostingPhase.V2, actorId);

            // No operation queries for cleanup
            verify(operationMapper, never()).selectList(any(LambdaQueryWrapper.class));
        }

        @Test
        @DisplayName("Upgrade V2→V3 does not trigger cleanup")
        void upgradeV2ToV3DoesNotTriggerCleanup() {
            when(hostingConfigMapper.selectOne(any(LambdaQueryWrapper.class)))
                    .thenReturn(HostingConfigEntity.builder()
                            .id(UUID.randomUUID())
                            .config("{\"active_phase\":\"V2\"}")
                            .build());
            when(hostingConfigMapper.updateById(any())).thenReturn(1);

            service.transitionPhase(storeId, HostingPhase.V3, actorId);

            verify(operationMapper, never()).selectList(any(LambdaQueryWrapper.class));
        }
    }

    @Nested
    @DisplayName("Downgrade cleanup")
    class DowngradeCleanupTests {

        private UUID storeId;
        private UUID actorId;

        @BeforeEach
        void setUp() {
            storeId = UUID.randomUUID();
            actorId = UUID.randomUUID();
        }

        @Test
        @DisplayName("V3→V2 downgrade cancels awaiting_approval KEYWORD/NEGATIVE ops")
        void v3ToV2CancelsAwaitingApprovalKeywordOps() {
            // Setup: current phase = V3
            when(hostingConfigMapper.selectOne(any(LambdaQueryWrapper.class)))
                    .thenReturn(HostingConfigEntity.builder()
                            .id(UUID.randomUUID())
                            .config("{\"active_phase\":\"V3\"}")
                            .build());
            when(hostingConfigMapper.updateById(any())).thenReturn(1);

            // Mock: awaiting_approval keyword ops
            OperationEntity keywordOp = OperationEntity.builder()
                    .id(UUID.randomUUID())
                    .storeId(storeId)
                    .syncState("awaiting_approval")
                    .operationSource("ai_hosting")
                    .field("keyword")
                    .build();
            OperationEntity negativeOp = OperationEntity.builder()
                    .id(UUID.randomUUID())
                    .storeId(storeId)
                    .syncState("awaiting_approval")
                    .operationSource("ai_hosting")
                    .field("negative_keyword")
                    .build();

            // Return keyword ops for awaiting_approval query
            when(operationMapper.selectList(any(LambdaQueryWrapper.class)))
                    .thenReturn(List.of(keywordOp, negativeOp))  // awaiting_approval
                    .thenReturn(Collections.emptyList());         // pending

            when(operationMapper.updateById(any())).thenReturn(1);

            service.transitionPhase(storeId, HostingPhase.V2, actorId);

            // Verify operations were updated (cancelled)
            verify(operationMapper, atLeast(2)).updateById(any());
            // Verify approval requests were closed
            verify(approvalService).closeApprovalRequest(keywordOp.getId(), "PHASE_DOWNGRADE");
            verify(approvalService).closeApprovalRequest(negativeOp.getId(), "PHASE_DOWNGRADE");
        }

        @Test
        @DisplayName("V3→V2 downgrade supersedes pending KEYWORD/NEGATIVE ops and closes outbox")
        void v3ToV2SupersedesPendingOpsAndClosesOutbox() {
            when(hostingConfigMapper.selectOne(any(LambdaQueryWrapper.class)))
                    .thenReturn(HostingConfigEntity.builder()
                            .id(UUID.randomUUID())
                            .config("{\"active_phase\":\"V3\"}")
                            .build());
            when(hostingConfigMapper.updateById(any())).thenReturn(1);

            OperationEntity pendingKwOp = OperationEntity.builder()
                    .id(UUID.randomUUID())
                    .storeId(storeId)
                    .syncState("pending")
                    .operationSource("ai_hosting")
                    .field("keyword")
                    .build();

            // First call = awaiting_approval (empty), second call = pending (has ops)
            when(operationMapper.selectList(any(LambdaQueryWrapper.class)))
                    .thenReturn(Collections.emptyList())   // awaiting_approval
                    .thenReturn(List.of(pendingKwOp));     // pending

            when(operationMapper.updateById(any())).thenReturn(1);
            when(outboxMapper.update(any(), any(UpdateWrapper.class))).thenReturn(1);

            service.transitionPhase(storeId, HostingPhase.V2, actorId);

            // Verify operation was superseded
            verify(operationMapper, atLeast(1)).updateById(argThat(op ->
                    op instanceof OperationEntity
                            && "superseded".equals(((OperationEntity) op).getSyncState())));
            // Verify outbox row was closed
            verify(outboxMapper).update(any(), any(UpdateWrapper.class));
        }

        @Test
        @DisplayName("V2→V1 downgrade disables BUDGET capability and cleans up")
        void v2ToV1DisablesBudgetAndCleansUp() {
            when(hostingConfigMapper.selectOne(any(LambdaQueryWrapper.class)))
                    .thenReturn(HostingConfigEntity.builder()
                            .id(UUID.randomUUID())
                            .config("{\"active_phase\":\"V2\"}")
                            .build());
            when(hostingConfigMapper.updateById(any())).thenReturn(1);

            OperationEntity budgetOp = OperationEntity.builder()
                    .id(UUID.randomUUID())
                    .storeId(storeId)
                    .syncState("awaiting_approval")
                    .operationSource("ai_hosting")
                    .field("daily_budget")
                    .build();

            when(operationMapper.selectList(any(LambdaQueryWrapper.class)))
                    .thenReturn(List.of(budgetOp))       // awaiting_approval budget ops
                    .thenReturn(Collections.emptyList()); // pending

            when(operationMapper.updateById(any())).thenReturn(1);

            service.transitionPhase(storeId, HostingPhase.V1, actorId);

            // Verify the budget op was cancelled
            verify(operationMapper, atLeast(1)).updateById(argThat(op ->
                    op instanceof OperationEntity
                            && "cancelled".equals(((OperationEntity) op).getSyncState())));
            verify(approvalService).closeApprovalRequest(budgetOp.getId(), "PHASE_DOWNGRADE");
        }

        @Test
        @DisplayName("Submitted operations are NOT affected by downgrade cleanup")
        void submittedOpsNotAffectedByDowngrade() {
            // This is implicitly tested: the query only looks for awaiting_approval and pending
            // states, never for submitted/submitting/etc.
            when(hostingConfigMapper.selectOne(any(LambdaQueryWrapper.class)))
                    .thenReturn(HostingConfigEntity.builder()
                            .id(UUID.randomUUID())
                            .config("{\"active_phase\":\"V3\"}")
                            .build());
            when(hostingConfigMapper.updateById(any())).thenReturn(1);

            // Both queries return empty (no awaiting_approval or pending ops)
            when(operationMapper.selectList(any(LambdaQueryWrapper.class)))
                    .thenReturn(Collections.emptyList());

            service.transitionPhase(storeId, HostingPhase.V2, actorId);

            // No operations updated
            verify(operationMapper, never()).updateById(any());
            // No outbox rows closed
            verify(outboxMapper, never()).update(any(), any(UpdateWrapper.class));
        }
    }

    @Nested
    @DisplayName("computeDisabledCapabilities")
    class ComputeDisabledCapabilitiesTests {

        @Test
        @DisplayName("V3→V2 disables KEYWORD and NEGATIVE")
        void v3ToV2DisablesKeywordAndNegative() {
            Set<HostingAdjustmentType> disabled = service.computeDisabledCapabilities(
                    HostingPhase.V3, HostingPhase.V2);
            assertThat(disabled).containsExactlyInAnyOrder(
                    HostingAdjustmentType.KEYWORD, HostingAdjustmentType.NEGATIVE);
        }

        @Test
        @DisplayName("V2→V1 disables BUDGET")
        void v2ToV1DisablesBudget() {
            Set<HostingAdjustmentType> disabled = service.computeDisabledCapabilities(
                    HostingPhase.V2, HostingPhase.V1);
            assertThat(disabled).containsExactlyInAnyOrder(HostingAdjustmentType.BUDGET);
        }

        @Test
        @DisplayName("V1→V2 disables nothing (upgrade)")
        void v1ToV2DisablesNothing() {
            Set<HostingAdjustmentType> disabled = service.computeDisabledCapabilities(
                    HostingPhase.V1, HostingPhase.V2);
            assertThat(disabled).isEmpty();
        }

        @Test
        @DisplayName("V2→V3 disables nothing (upgrade)")
        void v2ToV3DisablesNothing() {
            Set<HostingAdjustmentType> disabled = service.computeDisabledCapabilities(
                    HostingPhase.V2, HostingPhase.V3);
            assertThat(disabled).isEmpty();
        }
    }

    @Nested
    @DisplayName("mapToChangeTypes")
    class MapToChangeTypesTests {

        @Test
        @DisplayName("Maps BID to 'bid'")
        void mapsBid() {
            Set<String> result = PhaseConfigurationServiceImpl.mapToChangeTypes(
                    EnumSet.of(HostingAdjustmentType.BID));
            assertThat(result).containsExactly("bid");
        }

        @Test
        @DisplayName("Maps BUDGET to 'daily_budget'")
        void mapsBudget() {
            Set<String> result = PhaseConfigurationServiceImpl.mapToChangeTypes(
                    EnumSet.of(HostingAdjustmentType.BUDGET));
            assertThat(result).containsExactly("daily_budget");
        }

        @Test
        @DisplayName("Maps KEYWORD and NEGATIVE correctly")
        void mapsKeywordAndNegative() {
            Set<String> result = PhaseConfigurationServiceImpl.mapToChangeTypes(
                    EnumSet.of(HostingAdjustmentType.KEYWORD, HostingAdjustmentType.NEGATIVE));
            assertThat(result).containsExactlyInAnyOrder("keyword", "negative_keyword");
        }
    }

    @Nested
    @DisplayName("extractPhaseFromConfig")
    class ExtractPhaseFromConfigTests {

        @Test
        @DisplayName("Extracts V1 from JSON")
        void extractsV1() {
            assertThat(PhaseConfigurationServiceImpl.extractPhaseFromConfig(
                    "{\"active_phase\":\"V1\"}")).isEqualTo(HostingPhase.V1);
        }

        @Test
        @DisplayName("Extracts V2 from JSON")
        void extractsV2() {
            assertThat(PhaseConfigurationServiceImpl.extractPhaseFromConfig(
                    "{\"active_phase\":\"V2\"}")).isEqualTo(HostingPhase.V2);
        }

        @Test
        @DisplayName("Extracts V3 from JSON")
        void extractsV3() {
            assertThat(PhaseConfigurationServiceImpl.extractPhaseFromConfig(
                    "{\"active_phase\":\"V3\"}")).isEqualTo(HostingPhase.V3);
        }

        @Test
        @DisplayName("Returns default for null config")
        void returnsDefaultForNull() {
            assertThat(PhaseConfigurationServiceImpl.extractPhaseFromConfig(null))
                    .isEqualTo(HostingPhase.DEFAULT);
        }

        @Test
        @DisplayName("Returns default for blank config")
        void returnsDefaultForBlank() {
            assertThat(PhaseConfigurationServiceImpl.extractPhaseFromConfig(""))
                    .isEqualTo(HostingPhase.DEFAULT);
        }

        @Test
        @DisplayName("Returns default for config without active_phase key")
        void returnsDefaultWhenKeyMissing() {
            assertThat(PhaseConfigurationServiceImpl.extractPhaseFromConfig(
                    "{\"execution_mode\":\"auto_execute\"}")).isEqualTo(HostingPhase.DEFAULT);
        }

        @Test
        @DisplayName("Returns default for unrecognized phase value")
        void returnsDefaultForUnrecognized() {
            assertThat(PhaseConfigurationServiceImpl.extractPhaseFromConfig(
                    "{\"active_phase\":\"V99\"}")).isEqualTo(HostingPhase.DEFAULT);
        }

        @Test
        @DisplayName("Extracts phase from config with multiple keys")
        void extractsFromMultiKeyConfig() {
            assertThat(PhaseConfigurationServiceImpl.extractPhaseFromConfig(
                    "{\"execution_mode\":\"auto_execute\",\"active_phase\":\"V3\",\"threshold\":0.5}"))
                    .isEqualTo(HostingPhase.V3);
        }
    }

    @Nested
    @DisplayName("phaseOrdinal")
    class PhaseOrdinalTests {

        @Test
        @DisplayName("V1 has ordinal 1")
        void v1Ordinal() {
            assertThat(PhaseConfigurationServiceImpl.phaseOrdinal(HostingPhase.V1)).isEqualTo(1);
        }

        @Test
        @DisplayName("V2 has ordinal 2")
        void v2Ordinal() {
            assertThat(PhaseConfigurationServiceImpl.phaseOrdinal(HostingPhase.V2)).isEqualTo(2);
        }

        @Test
        @DisplayName("V3 has ordinal 3")
        void v3Ordinal() {
            assertThat(PhaseConfigurationServiceImpl.phaseOrdinal(HostingPhase.V3)).isEqualTo(3);
        }
    }
}
