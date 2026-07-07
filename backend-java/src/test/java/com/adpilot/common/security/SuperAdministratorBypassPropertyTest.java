package com.adpilot.common.security;

import com.adpilot.modules.rbac.PlatformFamily;
import com.adpilot.modules.rbac.entity.AccountPlatformAccessEntity;
import com.adpilot.modules.rbac.mapper.AccountPlatformAccessMapper;
import com.adpilot.modules.rbac.service.impl.PlatformAccessServiceImpl;
import com.adpilot.modules.store.mapper.StoreMapper;
import com.adpilot.modules.store.mapper.UserStoreMapper;
import com.adpilot.modules.user.mapper.DataScopeMapper;
import com.adpilot.modules.user.mapper.UserRoleMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.mockito.Mockito;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Property-based test for the Super_Administrator bypass invariant across all three
 * RBAC dimensions: data scope ({@link DataScopeServiceImpl}), platform access
 * ({@link PlatformAccessServiceImpl}), and functional permission
 * ({@link PermissionChecker}).
 *
 * <p>Feature: platform-workspace-rbac, Property 5: Super-administrator bypass.
 *
 * <p>Validates: Requirements 15.1, 15.2, 15.4.
 *
 * <p>Property 5 states: for any operation, store-scoped record, and platform family,
 * an account holding the {@code super_admin} role is authorized — it passes the
 * functional-permission check without an explicit grant, falls within data scope for
 * every Store_Group, and is permitted across every platform family.
 *
 * <p>The bypass is purely a function of the acting account's roles containing
 * {@code super_admin}; it consults no persisted grant. The mappers are therefore
 * modelled as bare mocks (the mock-mapper modelling pattern from
 * {@code AssignedStoreScopePreservationPropertyTest} / {@code PlatformAccessRejectionPropertyTest}),
 * and the tests additionally assert the bypass never touches them — proving it
 * "without an explicit grant" (Req 15.1, 15.4).
 */
class SuperAdministratorBypassPropertyTest {

    private static final String SUPER_ADMIN_ROLE = "super_admin";

    // ---------------------------------------------------------------------
    // Data-scope dimension (Req 15.2 — in scope for every Store_Group)
    // ---------------------------------------------------------------------

    /**
     * Feature: platform-workspace-rbac, Property 5: Super-administrator bypass.
     *
     * <p>Validates: Requirements 15.1, 15.2, 15.4.
     *
     * <p>For any super-admin account, {@link DataScopeServiceImpl#resolve} yields an
     * unrestricted, super-admin scope without consulting any RBAC grant table.
     */
    @Property(tries = 200)
    void superAdminResolvesUnrestrictedScopeWithoutConsultingGrants(
            @ForAll("superAdminUsers") CurrentUser superAdmin) {

        DataScopeMapper dataScopeMapper = Mockito.mock(DataScopeMapper.class);
        UserRoleMapper userRoleMapper = Mockito.mock(UserRoleMapper.class);
        UserStoreMapper userStoreMapper = Mockito.mock(UserStoreMapper.class);
        StoreMapper storeMapper = Mockito.mock(StoreMapper.class);

        DataScopeServiceImpl service = new DataScopeServiceImpl(
                dataScopeMapper, userRoleMapper, userStoreMapper, storeMapper);

        EffectiveScope scope = service.resolve(superAdmin);

        assertThat(scope.isSuperAdmin()).isTrue();
        assertThat(scope.isUnrestricted()).isTrue();

        // The bypass is granted by the role alone — no grant lookup is performed.
        verifyNoInteractions(dataScopeMapper, userRoleMapper, userStoreMapper, storeMapper);
    }

    /**
     * Feature: platform-workspace-rbac, Property 5: Super-administrator bypass.
     *
     * <p>Validates: Requirements 15.1, 15.2, 15.4.
     *
     * <p>For any super-admin account and any store-scoped query target,
     * {@link DataScopeServiceImpl#applyScope} adds no row restriction at all — the
     * query wrapper carries no bound predicate and no {@code 1 = 0} deny clause, so
     * the super-admin sees records from every Store_Group.
     */
    @Property(tries = 200)
    void superAdminApplyScopeAddsNoRowRestriction(
            @ForAll("superAdminUsers") CurrentUser superAdmin) {

        DataScopeServiceImpl service = serviceWithBareMocks();

        QueryWrapper<Object> wrapper = new QueryWrapper<>();
        // A fully-populated target exposing every scope dimension: a non-super-admin
        // would have a restricting predicate appended, a super-admin must not.
        ScopeTarget target = ScopeTarget.builder()
                .storeIdColumn("store_id")
                .productIdColumn("product_id")
                .storeGroupIdColumn("store_group_id")
                .departmentIdColumn("department_id")
                .ownerIdColumn("owner_id")
                .build();

        service.applyScope(wrapper, target, superAdmin);

        // Materialise the SQL and bound parameters.
        wrapper.getTargetSql();
        assertThat(wrapper.getParamNameValuePairs()).isEmpty();
        assertThat(wrapper.isEmptyOfWhere()).isTrue();
        assertThat(wrapper.getTargetSql()).doesNotContain("1 = 0");
    }

    /**
     * Feature: platform-workspace-rbac, Property 5: Super-administrator bypass.
     *
     * <p>Validates: Requirements 15.1, 15.2, 15.4.
     *
     * <p>For any super-admin account and any arbitrary store-scoped record — even one
     * whose store, store-group, product, department, and owner are all foreign (so a
     * non-super-admin would be rejected) — both single-record guards
     * {@link DataScopeServiceImpl#assertCanRead} and
     * {@link DataScopeServiceImpl#assertCanWrite} permit access (never throw).
     */
    @Property(tries = 200)
    void superAdminReadAndWriteGuardsNeverRejectAnyRecord(
            @ForAll("superAdminUsers") CurrentUser superAdmin,
            @ForAll("records") StoreScopedRecord record) {

        DataScopeServiceImpl service = serviceWithBareMocks();

        assertThatCode(() -> service.assertCanRead(record, superAdmin)).doesNotThrowAnyException();
        assertThatCode(() -> service.assertCanWrite(record, superAdmin)).doesNotThrowAnyException();
    }

    // ---------------------------------------------------------------------
    // Platform-access dimension (Req 15.4 — every platform family)
    // ---------------------------------------------------------------------

    /**
     * Feature: platform-workspace-rbac, Property 5: Super-administrator bypass.
     *
     * <p>Validates: Requirements 15.1, 15.2, 15.4.
     *
     * <p>For any super-admin account, {@link PlatformAccessServiceImpl#accessibleFamilies}
     * returns all four platform families and {@link PlatformAccessServiceImpl#hasAccess}
     * is true for every family, with no persisted Platform_Access grant consulted.
     */
    @Property(tries = 200)
    void superAdminIsPermittedAcrossEveryPlatformFamily(
            @ForAll("superAdminUsers") CurrentUser superAdmin) {

        AccountPlatformAccessMapper mapper = Mockito.mock(AccountPlatformAccessMapper.class);
        PlatformAccessServiceImpl service = new PlatformAccessServiceImpl(mapper);

        assertThat(service.accessibleFamilies(superAdmin))
                .containsExactlyInAnyOrder(PlatformFamily.values());

        for (PlatformFamily family : PlatformFamily.values()) {
            assertThat(service.hasAccess(superAdmin, family))
                    .as("super-admin access to %s", family)
                    .isTrue();
        }

        // Bypass without an explicit grant: the grant table is never queried.
        verifyNoInteractions(mapper);
    }

    // ---------------------------------------------------------------------
    // Functional-permission dimension (Req 15.1 — without an explicit grant)
    // ---------------------------------------------------------------------

    /**
     * Feature: platform-workspace-rbac, Property 5: Super-administrator bypass.
     *
     * <p>Validates: Requirements 15.1, 15.2, 15.4.
     *
     * <p>For any {@code module:action} permission code, a super-admin passes
     * {@link PermissionChecker#hasPermission} even though it holds no explicit
     * permission grants ({@code permissions} is empty).
     */
    @Property(tries = 200)
    void superAdminPassesAnyFunctionalPermissionWithoutAGrant(
            @ForAll("superAdminUsers") CurrentUser superAdmin,
            @ForAll("permissionCodes") String permission) {

        // The super-admin holds no explicit permission grants.
        superAdmin.setPermissions(java.util.List.of());

        PermissionChecker checker = new PermissionChecker();
        try {
            SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken(
                            superAdmin, null, superAdmin.getAuthorities()));

            assertThat(checker.hasPermission(permission))
                    .as("super-admin functional permission for %s", permission)
                    .isTrue();
            assertThat(checker.isSuperAdmin()).isTrue();
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    // --- service modelling -------------------------------------------------

    private static DataScopeServiceImpl serviceWithBareMocks() {
        return new DataScopeServiceImpl(
                Mockito.mock(DataScopeMapper.class),
                Mockito.mock(UserRoleMapper.class),
                Mockito.mock(UserStoreMapper.class),
                Mockito.mock(StoreMapper.class));
    }

    // --- generated model ---------------------------------------------------

    /**
     * An arbitrary store-scoped record exposing every scope dimension the guards may
     * read. Each id is independently random/foreign, so a non-super-admin would be
     * rejected — making the super-admin's unconditional acceptance meaningful.
     */
    record StoreScopedRecord(String storeId,
                             String productId,
                             String storeGroupId,
                             String departmentId,
                             String ownerId) {
    }

    @Provide
    Arbitrary<StoreScopedRecord> records() {
        Arbitrary<String> id = uuidString();
        return Combinators.combine(id, id, id, id, id).as(StoreScopedRecord::new);
    }

    /**
     * A super-admin {@link CurrentUser}: its role set always contains
     * {@code super_admin}, possibly alongside other arbitrary roles, and it carries no
     * explicit permission grants by default.
     */
    @Provide
    Arbitrary<CurrentUser> superAdminUsers() {
        Arbitrary<UUID> userId = Arbitraries.randomValue(r -> UUID.randomUUID());
        Arbitrary<Set<String>> otherRoles = Arbitraries
                .of("operator", "viewer", "ad_manager", "finance_clerk", "warehouse_staff")
                .set().ofMinSize(0).ofMaxSize(3);

        return Combinators.combine(userId, otherRoles).as((uid, roles) -> {
            Set<String> roleSet = new HashSet<>(roles);
            roleSet.add(SUPER_ADMIN_ROLE);
            return CurrentUser.builder()
                    .userId(uid.toString())
                    .email("super@example.com")
                    .orgId(UUID.randomUUID().toString())
                    .roles(roleSet)
                    .permissions(java.util.List.of())
                    .build();
        });
    }

    /** Arbitrary {@code module:action} functional-permission codes. */
    @Provide
    Arbitrary<String> permissionCodes() {
        Arbitrary<String> module = Arbitraries.of(
                "advertising", "product", "customer", "finance", "warehouse", "order", "report");
        Arbitrary<String> action = Arbitraries.of(
                "read", "write", "create", "update", "delete", "approve", "export");
        return Combinators.combine(module, action).as((m, a) -> m + ":" + a);
    }

    private Arbitrary<String> uuidString() {
        return Arbitraries.randomValue(r -> UUID.randomUUID().toString());
    }
}
