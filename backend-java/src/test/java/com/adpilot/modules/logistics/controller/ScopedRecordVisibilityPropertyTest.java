package com.adpilot.modules.logistics.controller;

import com.adpilot.common.exception.BusinessException;
import com.adpilot.common.security.CurrentUser;
import com.adpilot.common.security.DataScopeServiceImpl;
import com.adpilot.modules.logistics.entity.ShipmentEntity;
import com.adpilot.modules.store.entity.UserStoreEntity;
import com.adpilot.modules.store.mapper.UserStoreMapper;
import com.adpilot.modules.user.entity.DataScope;
import com.adpilot.modules.user.entity.UserRole;
import com.adpilot.modules.user.mapper.DataScopeMapper;
import com.adpilot.modules.user.mapper.UserRoleMapper;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.mockito.Mockito;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Property-based test for scoped record visibility enforced by
 * {@link DataScopeServiceImpl}, the mechanism the {@code LogisticsController}
 * invokes ({@code assertCanRead} / {@code assertCanWrite}) on every shipment read
 * and write.
 *
 * <p>Feature: platform-ux-logistics-enhancements, Property 20: Records are visible
 * only within the requester's scope.
 *
 * <p>Validates: Requirements 8.6, 9.7, 10.8, 16.6, 17.1, 17.2, 17.3, 17.4.
 *
 * <p>A logistics record (modelled by a store-scoped {@link ShipmentEntity}, the
 * scope anchor every shipment child record inherits) is readable and writable by a
 * requester whose effective data scope includes the record's store, and a request
 * for a record outside that scope is rejected with a 403 {@code FORBIDDEN}
 * {@link BusinessException} that carries <em>no</em> record data. The requester's
 * effective scope is resolved by the real {@link DataScopeServiceImpl} from the
 * RBAC tables (here an {@code assigned_store} scope over a generated set of stores),
 * so the test exercises the genuine enforcement rather than a stub.
 */
class ScopedRecordVisibilityPropertyTest {

    /**
     * Feature: platform-ux-logistics-enhancements, Property 20: Records are visible
     * only within the requester's scope.
     *
     * <p>Validates: Requirements 8.6, 9.7, 10.8, 16.6, 17.1, 17.2, 17.3, 17.4.
     *
     * <p>A record whose store lies outside the requester's effective store scope is
     * denied for both reads and writes with a 403 {@code FORBIDDEN} error, and the
     * guard returns no record data (the rejection is the only outcome).
     */
    @Property(tries = 200)
    void recordOutsideStoreScopeIsDeniedWithNoData(
            @ForAll("storeSets") List<UUID> stores) {

        // All but the last id are in scope; the last id is the out-of-scope store.
        List<UUID> inScopeStores = stores.subList(0, stores.size() - 1);
        UUID outOfScopeStore = stores.get(stores.size() - 1);

        UUID userId = UUID.randomUUID();
        CurrentUser user = userWithId(userId);
        DataScopeServiceImpl service = assignedStoreScopeService(userId, inScopeStores);

        ShipmentEntity foreign = shipmentInStore(outOfScopeStore);

        // Reads of an out-of-scope record are denied with a 403 FORBIDDEN error.
        BusinessException readDenial = catchThrowableOfType(
                () -> service.assertCanRead(foreign, user), BusinessException.class);
        assertThat(readDenial).isNotNull();
        assertThat(readDenial.getStatus()).isEqualTo(403);
        assertThat(readDenial.getCode()).isEqualTo("FORBIDDEN");

        // Writes of an out-of-scope record are denied identically.
        assertThatThrownBy(() -> service.assertCanWrite(foreign, user))
                .isInstanceOf(BusinessException.class)
                .matches(ex -> ((BusinessException) ex).getStatus() == 403);

        // No record data is surfaced: the guard returns nothing and the rejection
        // exception carries only an access message, never the shipment itself.
        assertThat(readDenial.getMessage()).doesNotContain(foreign.getId().toString());
        assertThat(readDenial.getMessage()).doesNotContain(outOfScopeStore.toString());
    }

    /**
     * Feature: platform-ux-logistics-enhancements, Property 20: Records are visible
     * only within the requester's scope.
     *
     * <p>Validates: Requirements 8.6, 9.7, 10.8, 16.6, 17.1, 17.2, 17.3, 17.4.
     *
     * <p>A record whose store is within the requester's effective store scope is
     * readable and writable without error.
     */
    @Property(tries = 200)
    void recordWithinStoreScopeIsReadableAndWritable(
            @ForAll("storeSets") List<UUID> stores) {

        List<UUID> inScopeStores = stores.subList(0, stores.size() - 1);

        UUID userId = UUID.randomUUID();
        CurrentUser user = userWithId(userId);
        DataScopeServiceImpl service = assignedStoreScopeService(userId, inScopeStores);

        // Any store the requester is assigned to yields a visible record.
        for (UUID store : inScopeStores) {
            ShipmentEntity owned = shipmentInStore(store);
            // Neither guard throws for an in-scope record.
            service.assertCanRead(owned, user);
            service.assertCanWrite(owned, user);
        }
    }

    // --- service wiring ----------------------------------------------------

    /**
     * Build a real {@link DataScopeServiceImpl} whose RBAC mappers resolve the given
     * user to an {@code assigned_store} effective scope over {@code inScopeStores}.
     */
    private static DataScopeServiceImpl assignedStoreScopeService(UUID userId, List<UUID> inScopeStores) {
        DataScopeMapper dataScopeMapper = Mockito.mock(DataScopeMapper.class);
        UserRoleMapper userRoleMapper = Mockito.mock(UserRoleMapper.class);
        UserStoreMapper userStoreMapper = Mockito.mock(UserStoreMapper.class);

        UUID roleId = UUID.randomUUID();

        // The user has one role.
        when(userRoleMapper.selectList(any())).thenReturn(List.of(
                UserRole.builder().id(UUID.randomUUID()).userId(userId).roleId(roleId).build()));

        // That role grants an assigned-store data scope.
        when(dataScopeMapper.selectList(any())).thenReturn(List.of(
                DataScope.builder().id(UUID.randomUUID()).roleId(roleId)
                        .scopeType("assigned_store").build()));

        // The user is assigned to exactly the in-scope stores (assigned-store ids are
        // always sourced from user_stores, never the data_scopes JSON).
        when(userStoreMapper.selectList(any())).thenReturn(inScopeStores.stream()
                .map(s -> UserStoreEntity.builder().id(UUID.randomUUID()).userId(userId).storeId(s).build())
                .collect(Collectors.toList()));

        return new DataScopeServiceImpl(dataScopeMapper, userRoleMapper, userStoreMapper,
                org.mockito.Mockito.mock(com.adpilot.modules.store.mapper.StoreMapper.class));
    }

    private static CurrentUser userWithId(UUID userId) {
        return CurrentUser.builder()
                .userId(userId.toString())
                .email("user@example.com")
                .orgId(UUID.randomUUID().toString())
                .roles(Set.of("ops")) // not super_admin: scope filtering applies
                .build();
    }

    private static ShipmentEntity shipmentInStore(UUID storeId) {
        return ShipmentEntity.builder()
                .id(UUID.randomUUID())
                .storeId(storeId)
                .shipmentId("SHIP-" + UUID.randomUUID())
                .status("pending")
                .build();
    }

    // --- generators --------------------------------------------------------

    /**
     * At least two distinct store ids: all but the last are the requester's in-scope
     * stores, the last is an out-of-scope store. Built from random UUIDs so jqwik
     * treats the generator as randomized and runs the full iteration count.
     */
    @Provide
    Arbitrary<List<UUID>> storeSets() {
        return Arbitraries.randomValue(r -> UUID.randomUUID())
                .list().ofMinSize(2).ofMaxSize(5).uniqueElements();
    }
}
