package com.adpilot.modules.rbac.service.impl;

import com.adpilot.common.utils.SecurityUtils;
import com.adpilot.modules.rbac.PlatformFamily;
import com.adpilot.modules.rbac.dto.CreateStoreGroupCommand;
import com.adpilot.modules.rbac.entity.StoreGroupEntity;
import com.adpilot.modules.rbac.mapper.StoreGroupMapper;
import com.adpilot.modules.rbac.vo.StoreGroupVo;
import com.adpilot.modules.store.entity.StoreEntity;
import com.adpilot.modules.store.mapper.StoreMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link StoreGroupServiceImpl} covering Store_Group creation
 * (platform-workspace-rbac Req 10.3) and Store reassignment to a different
 * same-family group (Req 10.5).
 *
 * <p>The mappers are mocked; {@link SecurityUtils} is stubbed to present an
 * authenticated caller with an organization context, mirroring the project's
 * existing service unit tests (see {@code CarrierServiceImplTest}).
 */
@ExtendWith(MockitoExtension.class)
class StoreGroupServiceImplTest {

    @Mock
    private StoreGroupMapper storeGroupMapper;

    @Mock
    private StoreMapper storeMapper;

    @InjectMocks
    private StoreGroupServiceImpl service;

    private MockedStatic<SecurityUtils> securityUtils;
    private UUID orgId;
    private UUID userId;

    @BeforeEach
    void setUp() {
        orgId = UUID.randomUUID();
        userId = UUID.randomUUID();
        securityUtils = Mockito.mockStatic(SecurityUtils.class);
        securityUtils.when(SecurityUtils::isAuthenticated).thenReturn(true);
        securityUtils.when(SecurityUtils::getCurrentOrgId).thenReturn(orgId.toString());
        securityUtils.when(SecurityUtils::getCurrentUserIdOrNull).thenReturn(userId.toString());
    }

    @AfterEach
    void tearDown() {
        securityUtils.close();
    }

    // --- create: success returns generated id (Req 10.3) --------------------

    @Test
    void create_success_persistsEntityAndReturnsGeneratedId() {
        // No existing group with this name in the org+family.
        when(storeGroupMapper.selectCount(any())).thenReturn(0L);
        // Simulate MyBatis-Plus assigning the generated identifier on insert.
        UUID generatedId = UUID.randomUUID();
        when(storeGroupMapper.insert(any(StoreGroupEntity.class))).thenAnswer(invocation -> {
            StoreGroupEntity persisted = invocation.getArgument(0);
            persisted.setId(generatedId);
            return 1;
        });

        CreateStoreGroupCommand cmd = new CreateStoreGroupCommand();
        cmd.setName("Holiday Stores");
        cmd.setPlatformFamily(PlatformFamily.AMAZON);

        StoreGroupVo vo = service.create(cmd);

        // The entity is persisted exactly once, scoped to the caller's org/family.
        ArgumentCaptor<StoreGroupEntity> captor = ArgumentCaptor.forClass(StoreGroupEntity.class);
        verify(storeGroupMapper, times(1)).insert(captor.capture());
        StoreGroupEntity inserted = captor.getValue();
        assertThat(inserted.getOrgId()).isEqualTo(orgId);
        assertThat(inserted.getName()).isEqualTo("Holiday Stores");
        assertThat(inserted.getPlatformFamily()).isEqualTo(PlatformFamily.AMAZON.getCode());
        assertThat(inserted.getIsDefault()).isFalse();

        // The returned view carries the generated id and the persisted values.
        assertThat(vo).isNotNull();
        assertThat(vo.getId()).isEqualTo(generatedId.toString());
        assertThat(vo.getOrgId()).isEqualTo(orgId.toString());
        assertThat(vo.getName()).isEqualTo("Holiday Stores");
        assertThat(vo.getPlatformFamily()).isEqualTo(PlatformFamily.AMAZON.getCode());
        assertThat(vo.getIsDefault()).isFalse();
    }

    // --- assignStore: reassignment applies to scope (Req 10.5) --------------

    @Test
    void assignStore_reassignsToDifferentSameFamilyGroup_persistsNewGroupId() {
        UUID storeId = UUID.randomUUID();
        UUID originalGroupId = UUID.randomUUID();
        UUID newGroupId = UUID.randomUUID();

        StoreEntity store = StoreEntity.builder()
                .id(storeId)
                .orgId(orgId)
                .name("Store A")
                .marketplaceId(UUID.randomUUID())
                .platformFamily(PlatformFamily.AMAZON.getCode())
                .storeGroupId(originalGroupId)
                .build();

        StoreGroupEntity newGroup = StoreGroupEntity.builder()
                .id(newGroupId)
                .orgId(orgId)
                .name("Target Group")
                .platformFamily(PlatformFamily.AMAZON.getCode())
                .isDefault(false)
                .build();

        when(storeMapper.selectById(storeId)).thenReturn(store);
        when(storeGroupMapper.selectById(newGroupId)).thenReturn(newGroup);

        service.assignStore(storeId, newGroupId);

        // The store's store_group_id is updated to the new group and persisted,
        // so subsequent Store-Group data-scope evaluation resolves the new group.
        ArgumentCaptor<StoreEntity> captor = ArgumentCaptor.forClass(StoreEntity.class);
        verify(storeMapper, times(1)).updateById(captor.capture());
        StoreEntity updated = captor.getValue();
        assertThat(updated.getId()).isEqualTo(storeId);
        assertThat(updated.getStoreGroupId()).isEqualTo(newGroupId);
        assertThat(updated.getStoreGroupId()).isNotEqualTo(originalGroupId);
    }
}
