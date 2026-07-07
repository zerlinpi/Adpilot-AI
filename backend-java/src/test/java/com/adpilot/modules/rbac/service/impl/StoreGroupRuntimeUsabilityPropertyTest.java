package com.adpilot.modules.rbac.service.impl;

import com.adpilot.common.utils.SecurityUtils;
import com.adpilot.modules.rbac.PlatformFamily;
import com.adpilot.modules.rbac.dto.CreateStoreGroupCommand;
import com.adpilot.modules.rbac.entity.StoreGroupEntity;
import com.adpilot.modules.rbac.mapper.StoreGroupMapper;
import com.adpilot.modules.rbac.vo.StoreGroupVo;
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
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Property-based test for the runtime usability of newly created Amazon
 * Store_Groups, exercised through {@link StoreGroupServiceImpl}.
 *
 * <p>Feature: platform-workspace-rbac, Property 12: Newly created Amazon group is
 * immediately usable.
 *
 * <p>Validates: Requirements 11.1, 11.2, 11.3.
 *
 * <p>For any Amazon Store_Group created at runtime, it is immediately available
 * for store assignment and for Store_Group_Scope assignment without requiring a
 * redeploy or restart. Amazon groups are ordinary persisted rows (not a hardcoded
 * set), so "immediately usable" is modelled by a single mapper instance whose
 * in-memory store reflects the {@code insert} performed by {@code create}: the
 * very next {@code listByFamily} and {@code assignStore} calls — issued without
 * any restart — observe the freshly inserted group.
 */
class StoreGroupRuntimeUsabilityPropertyTest {

    static {
        // Register entity metadata so MyBatis-Plus can resolve lambda columns and
        // materialise bound parameter values for query-wrapper introspection,
        // without a running Spring/MyBatis context.
        MapperBuilderAssistant assistant =
                new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, StoreGroupEntity.class);
    }

    /**
     * Feature: platform-workspace-rbac, Property 12: Newly created Amazon group is
     * immediately usable.
     *
     * <p>Validates: Requirements 11.1, 11.2, 11.3.
     *
     * <p>After {@code create} persists a new Amazon Store_Group, the same service
     * instance — with no restart or redeploy — must (a) return the group from
     * {@code listByFamily(AMAZON)} so it can be picked for Store_Group_Scope
     * assignment, and (b) accept it as the target of {@code assignStore} for an
     * Amazon store, binding the store to the new group.
     */
    @Property(tries = 200)
    void newlyCreatedAmazonGroupIsImmediatelyUsable(@ForAll("amazonGroupNames") String groupName) {

        UUID orgId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID storeId = UUID.randomUUID();
        UUID originalGroupId = UUID.randomUUID();

        StoreGroupMapper storeGroupMapper = Mockito.mock(StoreGroupMapper.class);
        StoreMapper storeMapper = Mockito.mock(StoreMapper.class);
        StoreGroupServiceImpl service = new StoreGroupServiceImpl(storeGroupMapper, storeMapper);

        // In-memory persistence shared by every mapper call. This models a live,
        // already-running system: there is no redeploy between create() and the
        // subsequent listByFamily()/assignStore() calls.
        List<StoreGroupEntity> persisted = new ArrayList<>();

        when(storeGroupMapper.insert(any(StoreGroupEntity.class))).thenAnswer(inv -> {
            StoreGroupEntity entity = inv.getArgument(0);
            if (entity.getId() == null) {
                entity.setId(UUID.randomUUID());
            }
            persisted.add(entity);
            return 1;
        });

        // Name-availability check: count rows whose org/family/name match the query.
        when(storeGroupMapper.selectCount(any())).thenAnswer(inv -> {
            String name = queriedName(inv);
            return persisted.stream()
                    .filter(g -> orgId.equals(g.getOrgId()))
                    .filter(g -> g.getName().equals(name))
                    .count();
        });

        // listByFamily: return the persisted groups of the queried family, ordered
        // by name, exactly as the LambdaQueryWrapper requests.
        when(storeGroupMapper.selectList(any())).thenAnswer(inv -> {
            String family = queriedFamily(inv);
            return persisted.stream()
                    .filter(g -> orgId.equals(g.getOrgId()))
                    .filter(g -> family == null || family.equals(g.getPlatformFamily()))
                    .sorted(Comparator.comparing(StoreGroupEntity::getName))
                    .toList();
        });

        // selectById resolves a group from the in-memory store (used by assignStore).
        when(storeGroupMapper.selectById(any())).thenAnswer(inv -> {
            UUID id = inv.getArgument(0);
            return persisted.stream()
                    .filter(g -> id.equals(g.getId()))
                    .findFirst()
                    .orElse(null);
        });

        // An Amazon store awaiting assignment to the new group.
        StoreEntity store = StoreEntity.builder()
                .id(storeId)
                .orgId(orgId)
                .name("amazon-store")
                .marketplaceId(UUID.randomUUID())
                .platformFamily(PlatformFamily.AMAZON.getCode())
                .storeGroupId(originalGroupId)
                .build();
        when(storeMapper.selectById(storeId)).thenReturn(store);

        try (var securityUtils = Mockito.mockStatic(SecurityUtils.class)) {
            securityUtils.when(SecurityUtils::isAuthenticated).thenReturn(true);
            securityUtils.when(SecurityUtils::getCurrentOrgId).thenReturn(orgId.toString());
            securityUtils.when(SecurityUtils::getCurrentUserIdOrNull).thenReturn(userId.toString());

            // 1. Create the Amazon group at runtime (Req 11.1, 11.2).
            CreateStoreGroupCommand cmd = new CreateStoreGroupCommand();
            cmd.setName(groupName);
            cmd.setPlatformFamily(PlatformFamily.AMAZON);
            StoreGroupVo created = service.create(cmd);

            assertThat(created).isNotNull();
            assertThat(created.getId()).isNotNull();
            UUID newGroupId = UUID.fromString(created.getId());

            // 2. Immediately available for Store_Group_Scope assignment: the new
            //    group is returned by listByFamily without any restart (Req 11.3).
            List<StoreGroupVo> amazonGroups = service.listByFamily(PlatformFamily.AMAZON);
            assertThat(amazonGroups)
                    .extracting(StoreGroupVo::getId)
                    .contains(created.getId());

            // 3. Immediately available for store assignment: the store binds to the
            //    freshly created group (Req 11.3).
            service.assignStore(storeId, newGroupId);

            verify(storeMapper, times(1)).updateById(store);
            assertThat(store.getStoreGroupId()).isEqualTo(newGroupId);
        }
    }

    // --- helpers -----------------------------------------------------------

    /**
     * Extract the {@code platform_family} code bound into the
     * {@link LambdaQueryWrapper} passed to a mapper call, or {@code null} if none
     * is present.
     */
    private static String queriedFamily(InvocationOnMock invocation) {
        LambdaQueryWrapper<?> wrapper = invocation.getArgument(0);
        wrapper.getTargetSql();
        Map<String, Object> pairs = wrapper.getParamNameValuePairs();
        return pairs.values().stream()
                .filter(v -> v instanceof String)
                .map(String.class::cast)
                .filter(StoreGroupRuntimeUsabilityPropertyTest::isFamilyCode)
                .findFirst()
                .orElse(null);
    }

    /**
     * Extract the {@code name} value bound into the {@link LambdaQueryWrapper}: the
     * first String value that is not a known family code.
     */
    private static String queriedName(InvocationOnMock invocation) {
        LambdaQueryWrapper<?> wrapper = invocation.getArgument(0);
        wrapper.getTargetSql();
        Map<String, Object> pairs = wrapper.getParamNameValuePairs();
        return pairs.values().stream()
                .filter(v -> v instanceof String)
                .map(String.class::cast)
                .filter(s -> !isFamilyCode(s))
                .findFirst()
                .orElse(null);
    }

    private static boolean isFamilyCode(String value) {
        for (PlatformFamily family : PlatformFamily.values()) {
            if (family.getCode().equals(value)) {
                return true;
            }
        }
        return false;
    }

    // --- generators --------------------------------------------------------

    /** Arbitrary valid Amazon Store_Group names: 1-100 trimmable chars, never a family code. */
    @Provide
    Arbitrary<String> amazonGroupNames() {
        return Arbitraries.strings()
                .withCharRange('a', 'z')
                .withChars(' ', '0', '9', '-', '_')
                .ofMinLength(1)
                .ofMaxLength(100)
                .map(String::trim)
                .filter(s -> !s.isEmpty() && s.length() <= 100 && !isFamilyCode(s));
    }
}
