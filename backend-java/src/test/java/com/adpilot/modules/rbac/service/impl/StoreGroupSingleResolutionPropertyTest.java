package com.adpilot.modules.rbac.service.impl;

import com.adpilot.common.utils.SecurityUtils;
import com.adpilot.modules.rbac.PlatformFamily;
import com.adpilot.modules.rbac.entity.StoreGroupEntity;
import com.adpilot.modules.rbac.mapper.StoreGroupMapper;
import com.adpilot.modules.store.entity.StoreEntity;
import com.adpilot.modules.store.mapper.StoreMapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Property-based test for the single-Store_Group resolution invariant enforced by
 * {@link StoreGroupServiceImpl} across the full store lifecycle.
 *
 * <p>Feature: platform-workspace-rbac, Property 9: Every store resolves to exactly
 * one Store_Group of its platform family.
 *
 * <p>Validates: Requirements 10.2, 10.7, 4.5, 5.4.
 *
 * <p>For any store — whether created, connected through a Connection_Wizard, or
 * migrated from legacy data — the store resolves to exactly one Store_Group, and
 * that Store_Group's platform family matches the store's platform family. The two
 * runtime paths a store reaches its group through are exercised against the real
 * service (mirroring the mock-mapper modelling pattern of
 * {@code TableViewIsolationPropertyTest}):
 * <ul>
 *   <li><b>Explicit assignment</b> — {@link StoreGroupServiceImpl#assignStore} binds
 *       the store to a chosen group of its family, setting exactly one
 *       {@code store_group_id} (Req 10.2). The CREATED and CONNECTED origins both
 *       flow through this path (a store created in a group, or bound + grouped by a
 *       Connection_Wizard — Req 4.5, 5.4).</li>
 *   <li><b>Family-default fallback</b> — {@link StoreGroupServiceImpl#defaultGroupFor}
 *       resolves the per-family default group used when a store is otherwise
 *       unassigned (Req 10.7). The MIGRATED origin resolves its group this way.</li>
 * </ul>
 * In every case the store must end bound to a single group whose platform family
 * equals the store's own.
 *
 * <p>This focuses on Property 9 (exactly one group, family matches across
 * created/connected/migrated origins) and intentionally avoids re-asserting
 * Property 24's migration determinism or Property 10's mismatch-rejection details.
 */
class StoreGroupSingleResolutionPropertyTest {

    private static final String DEFAULT_GROUP_NAME = "默认分组";

    static {
        // Register entity metadata so MyBatis-Plus can resolve lambda columns and
        // materialise bound parameter values for query-wrapper introspection,
        // without a running Spring/MyBatis context.
        MapperBuilderAssistant assistant =
                new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, StoreGroupEntity.class);
        TableInfoHelper.initTableInfo(assistant, StoreEntity.class);
    }

    /** How a store reaches its Store_Group; all three must satisfy the invariant. */
    private enum Origin {
        /** Created directly inside an operations group. */
        CREATED,
        /** Bound and grouped by a Connection_Wizard (Req 4.5, 5.4). */
        CONNECTED,
        /** Backfilled from legacy data onto the family-default group (Req 10.7). */
        MIGRATED
    }

    /**
     * Feature: platform-workspace-rbac, Property 9: Every store resolves to exactly
     * one Store_Group of its platform family.
     *
     * <p>Validates: Requirements 10.2, 10.7, 4.5, 5.4.
     *
     * <p>Whatever its origin, a store that obtains its group through the service ends
     * bound to exactly one {@code store_group_id}, that group exists, and its platform
     * family equals the store's platform family.
     */
    @Property(tries = 200)
    void everyStoreResolvesToExactlyOneStoreGroupOfItsFamily(
            @ForAll("storeFamilies") PlatformFamily family,
            @ForAll Origin origin,
            @ForAll("groupNames") String groupName) {

        UUID orgId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        // Backing model of the two mappers: groups keyed by id, one store.
        Map<UUID, StoreGroupEntity> groups = new HashMap<>();

        StoreGroupEntity defaultGroup = group(orgId, family, DEFAULT_GROUP_NAME, true);
        StoreGroupEntity targetGroup = group(orgId, family, groupName, false);
        groups.put(defaultGroup.getId(), defaultGroup);
        groups.put(targetGroup.getId(), targetGroup);

        // The store starts unresolved (no store_group_id) with its platform family set.
        StoreEntity store = StoreEntity.builder()
                .id(UUID.randomUUID())
                .orgId(orgId)
                .name("store-" + origin)
                .marketplaceId(UUID.randomUUID())
                .platformFamily(family.getCode())
                .build();

        StoreGroupMapper groupMapper = Mockito.mock(StoreGroupMapper.class);
        StoreMapper storeMapper = Mockito.mock(StoreMapper.class);

        when(groupMapper.selectById(any())).thenAnswer(inv -> groups.get((UUID) inv.getArgument(0)));
        // defaultGroupFor(...) issues a selectOne scoped to org + family + is_default.
        when(groupMapper.selectOne(any())).thenAnswer(inv -> findDefaultGroup(inv, groups.values()));

        when(storeMapper.selectById(store.getId())).thenReturn(store);
        // updateById mutates the shared instance by reference, as a real update would persist.
        when(storeMapper.updateById(any())).thenReturn(1);

        StoreGroupServiceImpl service = new StoreGroupServiceImpl(groupMapper, storeMapper);

        try (var securityUtils = Mockito.mockStatic(SecurityUtils.class)) {
            securityUtils.when(SecurityUtils::isAuthenticated).thenReturn(true);
            securityUtils.when(SecurityUtils::getCurrentOrgId).thenReturn(orgId.toString());
            securityUtils.when(SecurityUtils::getCurrentUserIdOrNull).thenReturn(userId.toString());

            switch (origin) {
                case CREATED, CONNECTED ->
                        // Explicit assignment to a chosen group of the store's family.
                        service.assignStore(store.getId(), targetGroup.getId());
                case MIGRATED -> {
                    // Unassigned store falls back to the family-default group, which is
                    // then bound exactly as the migration backfill does.
                    UUID resolved = service.defaultGroupFor(family);
                    service.assignStore(store.getId(), resolved);
                }
            }
        }

        // Exactly one group id, and it resolves to an existing group of the store's family.
        assertThat(store.getStoreGroupId())
                .as("a store resolves to a single, non-null store group")
                .isNotNull();

        StoreGroupEntity resolved = groups.get(store.getStoreGroupId());
        assertThat(resolved)
                .as("the resolved store group exists")
                .isNotNull();
        assertThat(resolved.getPlatformFamily())
                .as("the resolved store group's platform family matches the store's")
                .isEqualTo(store.getPlatformFamily());

        if (origin == Origin.MIGRATED) {
            assertThat(resolved.getIsDefault())
                    .as("a migrated/unassigned store resolves to the family-default group")
                    .isTrue();
        }
    }

    /**
     * Feature: platform-workspace-rbac, Property 9: Every store resolves to exactly
     * one Store_Group of its platform family.
     *
     * <p>Validates: Requirements 10.2, 10.7, 4.5, 5.4.
     *
     * <p>The family-default fallback always resolves to a single default group whose
     * platform family equals the requested family, so any store relying on the
     * fallback (Req 10.7) still lands in a same-family group.
     */
    @Property(tries = 200)
    void familyDefaultFallbackResolvesToASingleSameFamilyGroup(
            @ForAll("storeFamilies") PlatformFamily family) {

        UUID orgId = UUID.randomUUID();

        // Both store families have a default group seeded for the org; the lookup must
        // pick exactly the one matching the requested family.
        Map<UUID, StoreGroupEntity> groups = new HashMap<>();
        StoreGroupEntity amazonDefault = group(orgId, PlatformFamily.AMAZON, DEFAULT_GROUP_NAME, true);
        StoreGroupEntity siteDefault = group(orgId, PlatformFamily.INDEPENDENT_SITE, DEFAULT_GROUP_NAME, true);
        groups.put(amazonDefault.getId(), amazonDefault);
        groups.put(siteDefault.getId(), siteDefault);

        StoreGroupMapper groupMapper = Mockito.mock(StoreGroupMapper.class);
        StoreMapper storeMapper = Mockito.mock(StoreMapper.class);
        when(groupMapper.selectOne(any())).thenAnswer(inv -> findDefaultGroup(inv, groups.values()));

        StoreGroupServiceImpl service = new StoreGroupServiceImpl(groupMapper, storeMapper);

        UUID resolved;
        try (var securityUtils = Mockito.mockStatic(SecurityUtils.class)) {
            securityUtils.when(SecurityUtils::isAuthenticated).thenReturn(true);
            securityUtils.when(SecurityUtils::getCurrentOrgId).thenReturn(orgId.toString());
            resolved = service.defaultGroupFor(family);
        }

        assertThat(resolved).as("the family default resolves to a single group").isNotNull();
        StoreGroupEntity group = groups.get(resolved);
        assertThat(group).isNotNull();
        assertThat(group.getIsDefault()).as("the fallback group is the family default").isTrue();
        assertThat(group.getPlatformFamily())
                .as("the fallback group belongs to the requested platform family")
                .isEqualTo(family.getCode());
    }

    // --- helpers -----------------------------------------------------------

    /**
     * Resolve the family-default group the way {@code defaultGroupFor}'s
     * {@link LambdaQueryWrapper} would: scoped to the org id, the platform family
     * code, and {@code is_default = true} carried in the wrapper's bound parameters.
     */
    private static StoreGroupEntity findDefaultGroup(InvocationOnMock invocation,
                                                     java.util.Collection<StoreGroupEntity> all) {
        List<Object> values = filterValues(invocation.getArgument(0));
        return all.stream()
                .filter(g -> Boolean.TRUE.equals(g.getIsDefault()))
                .filter(g -> values.contains(g.getOrgId()))
                .filter(g -> values.contains(g.getPlatformFamily()))
                .findFirst()
                .orElse(null);
    }

    private static List<Object> filterValues(LambdaQueryWrapper<?> wrapper) {
        // MyBatis-Plus materialises bound parameter values lazily while building the
        // SQL segment, so force segment generation before reading the pairs.
        wrapper.getTargetSql();
        Map<String, Object> pairs = wrapper.getParamNameValuePairs();
        return new ArrayList<>(pairs.values());
    }

    private static StoreGroupEntity group(UUID orgId, PlatformFamily family, String name, boolean isDefault) {
        return StoreGroupEntity.builder()
                .id(UUID.randomUUID())
                .orgId(orgId)
                .name(name)
                .platformFamily(family.getCode())
                .isDefault(isDefault)
                .build();
    }

    // --- generators --------------------------------------------------------

    /** Store_Group families: only amazon and independent_site host stores (Req 10.1). */
    @Provide
    Arbitrary<PlatformFamily> storeFamilies() {
        return Arbitraries.of(PlatformFamily.AMAZON, PlatformFamily.INDEPENDENT_SITE);
    }

    /** Non-default group names: non-blank, within the 100-char schema bound. */
    @Provide
    Arbitrary<String> groupNames() {
        return Arbitraries.strings()
                .withCharRange('a', 'z')
                .withChars('一', '二', '三', '组', ' ', '-')
                .ofMinLength(1)
                .ofMaxLength(100)
                .filter(s -> !s.trim().isEmpty() && !DEFAULT_GROUP_NAME.equals(s.trim()));
    }
}
