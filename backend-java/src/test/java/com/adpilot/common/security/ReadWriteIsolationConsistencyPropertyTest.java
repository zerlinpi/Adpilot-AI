package com.adpilot.common.security;

import com.adpilot.common.exception.BusinessException;
import com.adpilot.modules.store.entity.StoreEntity;
import com.adpilot.modules.store.entity.UserStoreEntity;
import com.adpilot.modules.store.mapper.StoreMapper;
import com.adpilot.modules.store.mapper.UserStoreMapper;
import com.adpilot.modules.user.entity.DataScope;
import com.adpilot.modules.user.entity.UserRole;
import com.adpilot.modules.user.mapper.DataScopeMapper;
import com.adpilot.modules.user.mapper.UserRoleMapper;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Property-based test for read/write isolation consistency across the
 * single-record data-scope guards.
 *
 * <p>Feature: platform-workspace-rbac, Property 4: Read/write isolation consistency.
 *
 * <p>Validates: Requirements 16.4.
 *
 * <p>Requirement 16.4 / Property 4: For any account and any single store-scoped
 * record, the read guard ({@link DataScopeServiceImpl#assertCanRead}) and the write
 * guard ({@link DataScopeServiceImpl#assertCanWrite}) make the identical allow/deny
 * decision for that (account, record) pair. Task 5.1 implements both guards by
 * delegating to a single shared {@code assertInScope}/{@code withinScope} decision,
 * so the two must never diverge for any account flavor (assigned_store,
 * assigned_store_group, department, own, all_company, super_admin) nor any record.
 *
 * <p>Each guard's outcome is captured as allow (returns normally) or deny (throws a
 * 403 {@link BusinessException}); the property asserts the two outcomes are equal.
 *
 * <p>The mappers are modelled as fixed-result stores (the mock-mapper modelling
 * pattern from {@code AssignedStoreScopePreservationPropertyTest} /
 * {@code TableViewIsolationPropertyTest}) so the decision logic is exercised without
 * a running Spring/MyBatis context.
 */
class ReadWriteIsolationConsistencyPropertyTest {

    /** Sentinel mapped to {@code null} so generated record fields can be absent. */
    private static final String NULL_TOKEN = "\u2205";

    /**
     * Feature: platform-workspace-rbac, Property 4: Read/write isolation consistency.
     *
     * <p>Validates: Requirements 16.4.
     *
     * <p>For any generated account and any generated single record, the read guard
     * and the write guard reach the identical allow/deny outcome.
     */
    @Property(tries = 200)
    void readAndWriteGuardsReachIdenticalDecision(@ForAll("scenarios") Scenario scenario) {
        DataScopeServiceImpl service = serviceFor(scenario.account());
        CurrentUser user = scenario.account().principal();
        Object record = scenario.record();

        boolean readAllowed = decide(() -> service.assertCanRead(record, user));
        boolean writeAllowed = decide(() -> service.assertCanWrite(record, user));

        assertThat(readAllowed)
                .as("read and write guards must reach the identical decision for the same (account, record)")
                .isEqualTo(writeAllowed);
    }

    /** Run a guard and report allow (true) when it returns, deny (false) on a 403. */
    private static boolean decide(Runnable guard) {
        try {
            guard.run();
            return true;
        } catch (BusinessException e) {
            return false;
        }
    }

    // --- service / mapper modelling ---------------------------------------

    private static DataScopeServiceImpl serviceFor(Account account) {
        DataScopeMapper dataScopeMapper = Mockito.mock(DataScopeMapper.class);
        UserRoleMapper userRoleMapper = Mockito.mock(UserRoleMapper.class);
        UserStoreMapper userStoreMapper = Mockito.mock(UserStoreMapper.class);
        StoreMapper storeMapper = Mockito.mock(StoreMapper.class);

        when(userRoleMapper.selectList(any())).thenReturn(account.userRoleRows());
        when(dataScopeMapper.selectList(any())).thenReturn(account.dataScopeRows());
        when(userStoreMapper.selectList(any())).thenReturn(account.userStoreRows());
        when(storeMapper.selectById(any())).thenAnswer(inv -> account.storeById().get(inv.getArgument(0)));

        return new DataScopeServiceImpl(dataScopeMapper, userRoleMapper, userStoreMapper, storeMapper);
    }

    // --- generated model ---------------------------------------------------

    /** A single store-scoped record exposing every dimension the guards inspect. */
    static final class StoreScopedRecord {
        final String storeId;
        final String productId;
        final String storeGroupId;
        final String departmentId;
        final String ownerId;

        StoreScopedRecord(String storeId, String productId, String storeGroupId,
                          String departmentId, String ownerId) {
            this.storeId = storeId;
            this.productId = productId;
            this.storeGroupId = storeGroupId;
            this.departmentId = departmentId;
            this.ownerId = ownerId;
        }
    }

    /** A generated account, pre-resolved into the mapper rows the service reads. */
    record Account(Set<String> roleNames,
                   UUID userId,
                   UUID departmentId,
                   List<UUID> roleIds,
                   List<DataScope> dataScopeRows,
                   List<UserStoreEntity> userStoreRows,
                   Map<UUID, StoreEntity> storeById,
                   List<String> storeCandidates,
                   List<String> productCandidates,
                   List<String> groupCandidates,
                   List<String> deptCandidates,
                   List<String> ownerCandidates) {

        CurrentUser principal() {
            return CurrentUser.builder()
                    .userId(userId.toString())
                    .email("user@example.com")
                    .roles(roleNames)
                    .departmentId(departmentId == null ? null : departmentId.toString())
                    .build();
        }

        List<UserRole> userRoleRows() {
            return roleIds.stream()
                    .map(rid -> UserRole.builder().id(UUID.randomUUID()).userId(userId).roleId(rid).build())
                    .collect(Collectors.toList());
        }
    }

    @Provide
    Arbitrary<Scenario> scenarios() {
        return accounts().flatMap(account ->
                recordsFor(account).map(record -> new Scenario(account, record)));
    }

    record Scenario(Account account, StoreScopedRecord record) {}

    /** Each generated account belongs to exactly one scope flavor. */
    @Provide
    Arbitrary<Account> accounts() {
        return Arbitraries.oneOf(
                superAdminAccounts(),
                allCompanyAccounts(),
                departmentAccounts(),
                ownAccounts(),
                assignedStoreAccounts(),
                assignedStoreGroupAccounts());
    }

    // --- flavor builders ---------------------------------------------------

    private Arbitrary<Account> superAdminAccounts() {
        return Combinators.combine(uuid(), uuids(1, 3), uuids(1, 3))
                .as((uid, inGroups, foreignGroups) -> new Account(
                        Set.of("super_admin", "operator"),
                        uid, null,
                        List.of(), List.of(), List.of(), Map.of(),
                        strings(inGroups), strings(foreignGroups),
                        strings(inGroups), strings(foreignGroups), strings(List.of(uid))));
    }

    private Arbitrary<Account> allCompanyAccounts() {
        return Combinators.combine(uuid(), uuid(), uuids(1, 3))
                .as((uid, roleId, anyIds) -> new Account(
                        Set.of("operator"),
                        uid, null,
                        List.of(roleId),
                        List.of(scopeRow(roleId, "all_company")),
                        List.of(), Map.of(),
                        strings(anyIds), strings(anyIds), strings(anyIds),
                        strings(anyIds), strings(anyIds)));
    }

    private Arbitrary<Account> departmentAccounts() {
        return Combinators.combine(uuid(), uuid(), uuid(), uuid())
                .as((uid, roleId, deptId, foreignDept) -> new Account(
                        Set.of("operator"),
                        uid, deptId,
                        List.of(roleId),
                        List.of(scopeRow(roleId, "department")),
                        List.of(), Map.of(),
                        strings(List.of()), strings(List.of()), strings(List.of()),
                        strings(List.of(deptId, foreignDept)), strings(List.of(uid))));
    }

    private Arbitrary<Account> ownAccounts() {
        return Combinators.combine(uuid(), uuid(), uuid())
                .as((uid, roleId, foreignUser) -> new Account(
                        Set.of("operator"),
                        uid, null,
                        List.of(roleId),
                        List.of(scopeRow(roleId, "own")),
                        List.of(), Map.of(),
                        strings(List.of()), strings(List.of()), strings(List.of()),
                        strings(List.of()), strings(List.of(uid, foreignUser))));
    }

    private Arbitrary<Account> assignedStoreAccounts() {
        Arbitrary<UUID> userId = uuid();
        Arbitrary<UUID> roleId = uuid();
        Arbitrary<List<UUID>> inStores = uuids(1, 4);
        Arbitrary<List<UUID>> foreignStores = uuids(1, 4);

        return Combinators.combine(userId, roleId, inStores, foreignStores)
                .as((uid, rid, inSt, foreignSt) -> {
                    List<UserStoreEntity> userStores = inSt.stream()
                            .map(sid -> UserStoreEntity.builder()
                                    .id(UUID.randomUUID()).userId(uid).storeId(sid).build())
                            .collect(Collectors.toList());
                    List<String> storeCandidates = new ArrayList<>(strings(inSt));
                    storeCandidates.addAll(strings(foreignSt));
                    return new Account(
                            Set.of("operator"),
                            uid, null,
                            List.of(rid),
                            List.of(scopeRow(rid, "assigned_store")),
                            userStores, Map.of(),
                            storeCandidates, strings(foreignSt), strings(foreignSt),
                            strings(foreignSt), strings(List.of(uid)));
                });
    }

    private Arbitrary<Account> assignedStoreGroupAccounts() {
        Arbitrary<UUID> userId = uuid();
        Arbitrary<UUID> roleId = uuid();
        Arbitrary<List<UUID>> inGroups = uuids(1, 3);
        Arbitrary<List<UUID>> foreignGroups = uuids(1, 3);

        return Combinators.combine(userId, roleId, inGroups, foreignGroups)
                .as((uid, rid, inGr, foreignGr) -> {
                    // Build stores that resolve (via the stores table) to in-scope and
                    // foreign groups, so the store->group resolution path is exercised too.
                    Map<UUID, StoreEntity> storeById = new HashMap<>();
                    List<String> storeCandidates = new ArrayList<>();
                    for (UUID g : inGr) {
                        UUID sid = UUID.randomUUID();
                        storeById.put(sid, StoreEntity.builder().id(sid).storeGroupId(g).build());
                        storeCandidates.add(sid.toString());
                    }
                    for (UUID g : foreignGr) {
                        UUID sid = UUID.randomUUID();
                        storeById.put(sid, StoreEntity.builder().id(sid).storeGroupId(g).build());
                        storeCandidates.add(sid.toString());
                    }

                    DataScope row = DataScope.builder()
                            .id(UUID.randomUUID())
                            .roleId(rid)
                            .scopeType("assigned_store_group")
                            .storeGroupIds(strings(inGr))
                            .build();

                    List<String> groupCandidates = new ArrayList<>(strings(inGr));
                    groupCandidates.addAll(strings(foreignGr));

                    return new Account(
                            Set.of("operator"),
                            uid, null,
                            List.of(rid),
                            List.of(row),
                            List.of(), storeById,
                            storeCandidates, strings(foreignGr), groupCandidates,
                            strings(foreignGr), strings(List.of(uid)));
                });
    }

    // --- record generator (drawn from the account's candidate pools) -------

    private Arbitrary<StoreScopedRecord> recordsFor(Account a) {
        return Combinators.combine(
                        pickOrNull(a.storeCandidates()),
                        pickOrNull(a.productCandidates()),
                        pickOrNull(a.groupCandidates()),
                        pickOrNull(a.deptCandidates()),
                        pickOrNull(a.ownerCandidates()))
                .as(StoreScopedRecord::new);
    }

    // --- helpers -----------------------------------------------------------

    private static DataScope scopeRow(UUID roleId, String scopeType) {
        return DataScope.builder()
                .id(UUID.randomUUID())
                .roleId(roleId)
                .scopeType(scopeType)
                .build();
    }

    private static List<String> strings(List<UUID> ids) {
        return ids.stream().map(UUID::toString).collect(Collectors.toList());
    }

    private Arbitrary<String> pickOrNull(List<String> values) {
        List<String> withToken = new ArrayList<>(values);
        withToken.add(NULL_TOKEN);
        return Arbitraries.of(withToken).map(s -> NULL_TOKEN.equals(s) ? null : s);
    }

    private Arbitrary<UUID> uuid() {
        return Arbitraries.randomValue(r -> UUID.randomUUID());
    }

    private Arbitrary<List<UUID>> uuids(int min, int max) {
        return uuid().list().ofMinSize(min).ofMaxSize(max).uniqueElements();
    }
}
