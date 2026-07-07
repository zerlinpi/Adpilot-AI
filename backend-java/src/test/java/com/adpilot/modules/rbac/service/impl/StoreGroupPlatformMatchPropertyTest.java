package com.adpilot.modules.rbac.service.impl;

import com.adpilot.common.exception.BusinessException;
import com.adpilot.common.utils.SecurityUtils;
import com.adpilot.modules.rbac.entity.StoreGroupEntity;
import com.adpilot.modules.rbac.mapper.StoreGroupMapper;
import com.adpilot.modules.store.entity.StoreEntity;
import com.adpilot.modules.store.mapper.StoreMapper;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.mockito.Mockito;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Property-based test for the Store↔Store_Group platform-family match enforced by
 * {@link StoreGroupServiceImpl#assignStore(UUID, UUID)}.
 *
 * <p>Feature: platform-workspace-rbac, Property 10: Store-group platform-family
 * match on assignment.
 *
 * <p>Validates: Requirements 10.6, 11.4.
 *
 * <p>For any store and any target Store_Group, assigning the store to the group
 * succeeds if and only if the group's platform family equals the store's platform
 * family; a mismatch is rejected with an error and no association change. The
 * service is exercised with mocked {@link StoreMapper}/{@link StoreGroupMapper}
 * (the mock-mapper modeling pattern) and a stubbed {@link SecurityUtils} org
 * context, mirroring {@code TableViewIsolationPropertyTest}.
 */
class StoreGroupPlatformMatchPropertyTest {

    /**
     * Feature: platform-workspace-rbac, Property 10: Store-group platform-family
     * match on assignment.
     *
     * <p>Validates: Requirements 10.6, 11.4.
     *
     * <p>Assignment persists the new {@code store_group_id} exactly when the store's
     * platform family equals the group's family, and otherwise throws a
     * {@code STORE_GROUP_PLATFORM_MISMATCH} {@link BusinessException} leaving the
     * store's association untouched.
     */
    @Property(tries = 200)
    void assignmentSucceedsIffPlatformFamiliesMatch(
            @ForAll("families") String storeFamily,
            @ForAll("families") String groupFamily) {

        UUID orgId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();
        UUID groupId = UUID.randomUUID();
        UUID originalGroupId = UUID.randomUUID();

        StoreMapper storeMapper = Mockito.mock(StoreMapper.class);
        StoreGroupMapper storeGroupMapper = Mockito.mock(StoreGroupMapper.class);
        StoreGroupServiceImpl service = new StoreGroupServiceImpl(storeGroupMapper, storeMapper);

        StoreEntity store = StoreEntity.builder()
                .id(storeId)
                .orgId(orgId)
                .name("store")
                .marketplaceId(UUID.randomUUID())
                .platformFamily(storeFamily)
                .storeGroupId(originalGroupId)
                .build();

        StoreGroupEntity group = StoreGroupEntity.builder()
                .id(groupId)
                .orgId(orgId)
                .name("target-group")
                .platformFamily(groupFamily)
                .isDefault(false)
                .build();

        when(storeMapper.selectById(storeId)).thenReturn(store);
        when(storeGroupMapper.selectById(groupId)).thenReturn(group);

        boolean familiesMatch = storeFamily.equalsIgnoreCase(groupFamily);

        try (var securityUtils = Mockito.mockStatic(SecurityUtils.class)) {
            securityUtils.when(SecurityUtils::isAuthenticated).thenReturn(true);
            securityUtils.when(SecurityUtils::getCurrentOrgId).thenReturn(orgId.toString());
            securityUtils.when(SecurityUtils::getCurrentUserIdOrNull).thenReturn(UUID.randomUUID().toString());

            if (familiesMatch) {
                service.assignStore(storeId, groupId);

                // Success: the new association is persisted.
                verify(storeMapper, times(1)).updateById(store);
                assertThat(store.getStoreGroupId()).isEqualTo(groupId);
            } else {
                assertThatThrownBy(() -> service.assignStore(storeId, groupId))
                        .isInstanceOf(BusinessException.class)
                        .hasMessageContaining("does not match");

                // Mismatch: rejected with no association change and no persistence.
                verify(storeMapper, never()).updateById(any(StoreEntity.class));
                assertThat(store.getStoreGroupId()).isEqualTo(originalGroupId);
            }
        }
    }

    // --- generators --------------------------------------------------------

    /** The two valid Store_Group platform families (Req 10.1). */
    @Provide
    Arbitrary<String> families() {
        return Arbitraries.of("amazon", "independent_site");
    }
}
