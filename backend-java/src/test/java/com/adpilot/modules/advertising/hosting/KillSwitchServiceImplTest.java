package com.adpilot.modules.advertising.hosting;

import com.adpilot.common.exception.BusinessException;
import com.adpilot.modules.advertising.entity.OperationEntity;
import com.adpilot.modules.advertising.entity.OperationOutboxEntity;
import com.adpilot.modules.advertising.mapper.OperationMapper;
import com.adpilot.modules.advertising.mapper.OperationOutboxMapper;
import com.adpilot.modules.advertising.operation.OperationStateMachine;
import com.adpilot.modules.advertising.operation.SyncState;
import com.adpilot.modules.store.entity.StoreEntity;
import com.adpilot.modules.store.mapper.StoreMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link KillSwitchServiceImpl}.
 *
 * <p>Covers:</p>
 * <ul>
 *   <li>Activation at all scope levels (system, organization, store, campaign)</li>
 *   <li>Deactivation</li>
 *   <li>Hierarchy check (isActiveForCampaign checks system → org → store → campaign)</li>
 *   <li>Cleanup on activation (cancel awaiting_approval, supersede pending, close outbox, close approval requests)</li>
 *   <li>Validation of scope and scopeId</li>
 *   <li>Idempotent activation (already active does nothing)</li>
 * </ul>
 *
 * <p>Validates: Requirements 35.1, 35.2, 35.3, 35.4, 35.5, 35.6, 35.7, 35.8, 40.6.</p>
 */
@DisplayName("KillSwitchServiceImpl")
class KillSwitchServiceImplTest {

    private KillSwitchMapper killSwitchMapper;
    private OperationMapper operationMapper;
    private OperationOutboxMapper outboxMapper;
    private OperationStateMachine stateMachine;
    private HostingApprovalService approvalService;
    private StoreMapper storeMapper;
    private KillSwitchServiceImpl service;

    @BeforeEach
    void setUp() {
        killSwitchMapper = mock(KillSwitchMapper.class);
        operationMapper = mock(OperationMapper.class);
        outboxMapper = mock(OperationOutboxMapper.class);
        stateMachine = new OperationStateMachine();
        approvalService = mock(HostingApprovalService.class);
        storeMapper = mock(StoreMapper.class);

        service = new KillSwitchServiceImpl(
                killSwitchMapper,
                operationMapper,
                outboxMapper,
                stateMachine,
                approvalService,
                storeMapper,
                mock(com.adpilot.modules.audit.service.AuditLogService.class)
        );
    }

    @Nested
    @DisplayName("activate")
    class ActivateTests {

        @Test
        @DisplayName("Activates system-level kill switch")
        void activatesSystemLevel() {
            UUID actorId = UUID.randomUUID();

            // No existing active kill switch
            when(killSwitchMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
            when(killSwitchMapper.insert(any(KillSwitchEntity.class))).thenReturn(1);
            // No operations to clean up
            when(operationMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(Collections.emptyList());

            service.activate("system", null, "Emergency stop", actorId);

            verify(killSwitchMapper).insert(argThat(entity ->
                    "system".equals(entity.getScope())
                            && entity.getScopeId() == null
                            && "active".equals(entity.getStatus())
                            && "Emergency stop".equals(entity.getReason())
                            && actorId.equals(entity.getActivatedBy())
            ));
        }

        @Test
        @DisplayName("Activates store-level kill switch and resolves org")
        void activatesStoreLevel() {
            UUID storeId = UUID.randomUUID();
            UUID orgId = UUID.randomUUID();
            UUID actorId = UUID.randomUUID();

            when(killSwitchMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
            when(killSwitchMapper.insert(any(KillSwitchEntity.class))).thenReturn(1);
            when(operationMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(Collections.emptyList());

            StoreEntity store = new StoreEntity();
            store.setId(storeId);
            store.setOrgId(orgId);
            when(storeMapper.selectById(storeId)).thenReturn(store);

            service.activate("store", storeId, "Store emergency", actorId);

            verify(killSwitchMapper).insert(argThat(entity ->
                    "store".equals(entity.getScope())
                            && storeId.equals(entity.getScopeId())
                            && storeId.equals(entity.getStoreId())
                            && orgId.equals(entity.getOrgId())
                            && "active".equals(entity.getStatus())
            ));
        }

        @Test
        @DisplayName("Idempotent: does nothing when already active at same scope")
        void idempotentWhenAlreadyActive() {
            UUID storeId = UUID.randomUUID();
            UUID actorId = UUID.randomUUID();

            KillSwitchEntity existing = KillSwitchEntity.builder()
                    .id(UUID.randomUUID())
                    .scope("store")
                    .scopeId(storeId)
                    .status("active")
                    .build();
            when(killSwitchMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(existing);

            service.activate("store", storeId, "Duplicate", actorId);

            // No insert or cleanup attempted
            verify(killSwitchMapper, never()).insert(any());
            verify(operationMapper, never()).selectList(any(LambdaQueryWrapper.class));
        }

        @Test
        @DisplayName("Cancels awaiting_approval operations on activation")
        void cancelsAwaitingApprovalOps() {
            UUID storeId = UUID.randomUUID();
            UUID actorId = UUID.randomUUID();
            UUID orgId = UUID.randomUUID();

            when(killSwitchMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
            when(killSwitchMapper.insert(any(KillSwitchEntity.class))).thenReturn(1);

            StoreEntity store = new StoreEntity();
            store.setId(storeId);
            store.setOrgId(orgId);
            when(storeMapper.selectById(storeId)).thenReturn(store);

            OperationEntity awaitingOp = OperationEntity.builder()
                    .id(UUID.randomUUID())
                    .storeId(storeId)
                    .syncState("awaiting_approval")
                    .operationSource("ai_hosting")
                    .build();

            // First query: awaiting_approval ops, second query: pending ops
            when(operationMapper.selectList(any(LambdaQueryWrapper.class)))
                    .thenReturn(List.of(awaitingOp))
                    .thenReturn(Collections.emptyList());
            when(operationMapper.updateById(any())).thenReturn(1);

            service.activate("store", storeId, "test", actorId);

            // Verify the operation was cancelled
            verify(operationMapper).updateById(argThat(op ->
                    op instanceof OperationEntity
                            && "cancelled".equals(((OperationEntity) op).getSyncState())
                            && "KILL_SWITCH".equals(((OperationEntity) op).getStatusReason())
            ));
            // Verify approval request was closed
            verify(approvalService).closeApprovalRequest(awaitingOp.getId(), "KILL_SWITCH");
        }

        @Test
        @DisplayName("Supersedes pending operations and closes outbox rows on activation")
        void supersedesPendingOpsAndClosesOutbox() {
            UUID storeId = UUID.randomUUID();
            UUID actorId = UUID.randomUUID();
            UUID orgId = UUID.randomUUID();

            when(killSwitchMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
            when(killSwitchMapper.insert(any(KillSwitchEntity.class))).thenReturn(1);

            StoreEntity store = new StoreEntity();
            store.setId(storeId);
            store.setOrgId(orgId);
            when(storeMapper.selectById(storeId)).thenReturn(store);

            UUID pendingOpId = UUID.randomUUID();
            OperationEntity pendingOp = OperationEntity.builder()
                    .id(pendingOpId)
                    .storeId(storeId)
                    .syncState("pending")
                    .operationSource("ai_hosting")
                    .build();

            // First query: awaiting_approval (empty), second: pending ops
            when(operationMapper.selectList(any(LambdaQueryWrapper.class)))
                    .thenReturn(Collections.emptyList())
                    .thenReturn(List.of(pendingOp));
            when(operationMapper.updateById(any())).thenReturn(1);
            when(outboxMapper.update(any(), any(UpdateWrapper.class))).thenReturn(1);

            service.activate("store", storeId, "test", actorId);

            // Verify the operation was superseded
            verify(operationMapper).updateById(argThat(op ->
                    op instanceof OperationEntity
                            && "superseded".equals(((OperationEntity) op).getSyncState())
                            && "KILL_SWITCH".equals(((OperationEntity) op).getStatusReason())
            ));
            // Verify outbox row was closed
            verify(outboxMapper).update(any(), any(UpdateWrapper.class));
        }

        @Test
        @DisplayName("Throws on invalid scope")
        void throwsOnInvalidScope() {
            UUID actorId = UUID.randomUUID();

            assertThatThrownBy(() -> service.activate("invalid", UUID.randomUUID(), "test", actorId))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("Invalid kill switch scope");
        }

        @Test
        @DisplayName("Throws on null scope")
        void throwsOnNullScope() {
            UUID actorId = UUID.randomUUID();

            assertThatThrownBy(() -> service.activate(null, UUID.randomUUID(), "test", actorId))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("Invalid kill switch scope");
        }

        @Test
        @DisplayName("Throws when non-system scope has null scopeId")
        void throwsWhenNonSystemScopeHasNullScopeId() {
            UUID actorId = UUID.randomUUID();

            assertThatThrownBy(() -> service.activate("store", null, "test", actorId))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("scopeId is required");
        }
    }

    @Nested
    @DisplayName("deactivate")
    class DeactivateTests {

        @Test
        @DisplayName("Deactivates an active kill switch")
        void deactivatesActiveKillSwitch() {
            UUID storeId = UUID.randomUUID();
            UUID actorId = UUID.randomUUID();

            KillSwitchEntity existing = KillSwitchEntity.builder()
                    .id(UUID.randomUUID())
                    .scope("store")
                    .scopeId(storeId)
                    .status("active")
                    .build();
            when(killSwitchMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(existing);
            when(killSwitchMapper.updateById(any())).thenReturn(1);

            service.deactivate("store", storeId, actorId);

            verify(killSwitchMapper).updateById(argThat(entity ->
                    "inactive".equals(entity.getStatus())
                            && entity.getDeactivatedAt() != null
            ));
        }

        @Test
        @DisplayName("Does nothing when no active kill switch exists")
        void doesNothingWhenNotActive() {
            UUID storeId = UUID.randomUUID();
            UUID actorId = UUID.randomUUID();

            when(killSwitchMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

            service.deactivate("store", storeId, actorId);

            verify(killSwitchMapper, never()).updateById(any());
        }

        @Test
        @DisplayName("Throws on invalid scope")
        void throwsOnInvalidScope() {
            assertThatThrownBy(() -> service.deactivate("bad", UUID.randomUUID(), UUID.randomUUID()))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("Invalid kill switch scope");
        }
    }

    @Nested
    @DisplayName("isActive")
    class IsActiveTests {

        @Test
        @DisplayName("Returns true when kill switch is active at scope")
        void returnsTrueWhenActive() {
            UUID storeId = UUID.randomUUID();

            KillSwitchEntity entity = KillSwitchEntity.builder()
                    .scope("store")
                    .scopeId(storeId)
                    .status("active")
                    .build();
            when(killSwitchMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(entity);

            assertThat(service.isActive("store", storeId)).isTrue();
        }

        @Test
        @DisplayName("Returns false when no active kill switch at scope")
        void returnsFalseWhenNotActive() {
            when(killSwitchMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

            assertThat(service.isActive("store", UUID.randomUUID())).isFalse();
        }

        @Test
        @DisplayName("System scope checks with null scopeId")
        void systemScopeChecksNullScopeId() {
            when(killSwitchMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

            assertThat(service.isActive("system", null)).isFalse();

            // Verify the query was made (proving the method runs without error for null scopeId)
            verify(killSwitchMapper).selectOne(any(LambdaQueryWrapper.class));
        }
    }

    @Nested
    @DisplayName("isActiveForCampaign")
    class IsActiveForCampaignTests {

        @Test
        @DisplayName("Returns true when system-level kill switch is active")
        void returnsTrueWhenSystemActive() {
            UUID storeId = UUID.randomUUID();
            UUID campaignId = UUID.randomUUID();

            KillSwitchEntity systemKs = KillSwitchEntity.builder()
                    .scope("system")
                    .status("active")
                    .build();

            // First query for system scope returns active
            when(killSwitchMapper.selectOne(any(LambdaQueryWrapper.class)))
                    .thenReturn(systemKs);

            assertThat(service.isActiveForCampaign(storeId, campaignId)).isTrue();
        }

        @Test
        @DisplayName("Returns true when organization-level kill switch is active")
        void returnsTrueWhenOrgActive() {
            UUID storeId = UUID.randomUUID();
            UUID campaignId = UUID.randomUUID();
            UUID orgId = UUID.randomUUID();

            StoreEntity store = new StoreEntity();
            store.setId(storeId);
            store.setOrgId(orgId);
            when(storeMapper.selectById(storeId)).thenReturn(store);

            // system = null, org = active
            when(killSwitchMapper.selectOne(any(LambdaQueryWrapper.class)))
                    .thenReturn(null)  // system check
                    .thenReturn(KillSwitchEntity.builder().scope("organization").scopeId(orgId).status("active").build());

            assertThat(service.isActiveForCampaign(storeId, campaignId)).isTrue();
        }

        @Test
        @DisplayName("Returns true when store-level kill switch is active")
        void returnsTrueWhenStoreActive() {
            UUID storeId = UUID.randomUUID();
            UUID campaignId = UUID.randomUUID();
            UUID orgId = UUID.randomUUID();

            StoreEntity store = new StoreEntity();
            store.setId(storeId);
            store.setOrgId(orgId);
            when(storeMapper.selectById(storeId)).thenReturn(store);

            // system = null, org = null, store = active
            when(killSwitchMapper.selectOne(any(LambdaQueryWrapper.class)))
                    .thenReturn(null)  // system
                    .thenReturn(null)  // org
                    .thenReturn(KillSwitchEntity.builder().scope("store").scopeId(storeId).status("active").build());

            assertThat(service.isActiveForCampaign(storeId, campaignId)).isTrue();
        }

        @Test
        @DisplayName("Returns true when campaign-level kill switch is active")
        void returnsTrueWhenCampaignActive() {
            UUID storeId = UUID.randomUUID();
            UUID campaignId = UUID.randomUUID();
            UUID orgId = UUID.randomUUID();

            StoreEntity store = new StoreEntity();
            store.setId(storeId);
            store.setOrgId(orgId);
            when(storeMapper.selectById(storeId)).thenReturn(store);

            // system = null, org = null, store = null, campaign = active
            when(killSwitchMapper.selectOne(any(LambdaQueryWrapper.class)))
                    .thenReturn(null)  // system
                    .thenReturn(null)  // org
                    .thenReturn(null)  // store
                    .thenReturn(KillSwitchEntity.builder().scope("campaign").scopeId(campaignId).status("active").build());

            assertThat(service.isActiveForCampaign(storeId, campaignId)).isTrue();
        }

        @Test
        @DisplayName("Returns false when no kill switch is active at any level")
        void returnsFalseWhenNoneActive() {
            UUID storeId = UUID.randomUUID();
            UUID campaignId = UUID.randomUUID();
            UUID orgId = UUID.randomUUID();

            StoreEntity store = new StoreEntity();
            store.setId(storeId);
            store.setOrgId(orgId);
            when(storeMapper.selectById(storeId)).thenReturn(store);

            // All levels return null
            when(killSwitchMapper.selectOne(any(LambdaQueryWrapper.class)))
                    .thenReturn(null);

            assertThat(service.isActiveForCampaign(storeId, campaignId)).isFalse();
        }

        @Test
        @DisplayName("Handles null storeId gracefully")
        void handlesNullStoreId() {
            UUID campaignId = UUID.randomUUID();

            when(killSwitchMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

            // Should not throw; system and campaign levels still checked
            assertThat(service.isActiveForCampaign(null, campaignId)).isFalse();
        }
    }
}
