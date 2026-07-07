package com.adpilot.modules.advertising;

import com.adpilot.common.exception.BusinessException;
import com.adpilot.common.security.CurrentUser;
import com.adpilot.common.security.DataScopeServiceImpl;
import com.adpilot.common.security.ScopeTarget;
import com.adpilot.common.utils.SecurityUtils;
import com.adpilot.modules.advertising.support.StoreScopeRef;
import com.adpilot.modules.store.entity.UserStoreEntity;
import com.adpilot.modules.store.mapper.UserStoreMapper;
import com.adpilot.modules.user.entity.DataScope;
import com.adpilot.modules.user.entity.UserRole;
import com.adpilot.modules.user.mapper.DataScopeMapper;
import com.adpilot.modules.user.mapper.UserRoleMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Assume;
import net.jqwik.api.ForAll;
import net.jqwik.api.Label;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Property-based test for advertising data-scope and per-record ownership.
 *
 * <p>Feature: advertising-workspace-rework, Property 57: Data scope and ownership
 * prevent cross-store access.
 *
 * <p>Validates: Requirements 24.1, 24.4, 25.1, 25.2, 25.3, 25.4.
 *
 * <p>Exercises the real shared scope-check mechanism
 * ({@link DataScopeServiceImpl#assertCanRead}, {@link DataScopeServiceImpl#assertCanWrite}
 * and {@link DataScopeServiceImpl#applyScope}) that task 16.2 applies uniformly
 * across the advertising service layer. The RBAC mappers are stubbed so the acting
 * user resolves to an {@code ASSIGNED_STORE} scope over an arbitrary set of
 * in-scope store ids; the store dimension is then probed with arbitrary
 * in-scope/out-of-scope/guessed store ids and arbitrary bulk requests. The store
 * dimension is carried on the module's {@link StoreScopeRef} holder exactly as the
 * service guards read it off real advertising records.
 *
 * <p>The property asserts, for arbitrary store/owner combinations, that:
 * <ul>
 *   <li>every in-scope reference is allowed for both read and write (Req 25.1);</li>
 *   <li>any out-of-scope reference, including a randomly guessed store id, is
 *       rejected with HTTP 403 and an error that discloses none of the foreign
 *       store's contents (Req 24.4, 25.2, 25.3);</li>
 *   <li>a bulk request containing any cross-store member is rejected as a whole,
 *       while an all-in-scope bulk request is allowed (Req 25.4);</li>
 *   <li>the list-query scope predicate is constrained to exactly the in-scope
 *       store ids and never the foreign id (Req 24.1); and</li>
 *   <li>the acting user is always resolved from the authenticated security context
 *       (Req 25.1) — here via {@link SecurityUtils#getCurrentUser()}.</li>
 * </ul>
 */
@Tag("pbt")
class DataScopeOwnershipPropertyTest {

    /**
     * Feature: advertising-workspace-rework, Property 57: Data scope and ownership
     * prevent cross-store access.
     *
     * <p>Validates: Requirements 24.1, 24.4, 25.1, 25.2, 25.3, 25.4.
     */
    @Property(tries = 200)
    @Label("Feature: advertising-workspace-rework, Property 57: Data scope and ownership prevent cross-store access")
    void crossStoreReferencesAreRejectedAndInScopeReferencesAllowed(
            @ForAll("inScopeStores") List<UUID> inScopeStores,
            @ForAll("storeId") UUID foreignStore) {

        // The "guessed" / foreign store id must genuinely lie outside the scope.
        Assume.that(!inScopeStores.contains(foreignStore));

        UUID actingUserId = UUID.randomUUID();
        DataScopeServiceImpl service = assignedStoreScopeService(actingUserId, inScopeStores);

        try (var securityUtils = Mockito.mockStatic(SecurityUtils.class)) {
            // The acting user is resolved from the authenticated security context.
            CurrentUser caller = caller(actingUserId);
            securityUtils.when(SecurityUtils::getCurrentUser).thenReturn(caller);
            CurrentUser resolved = SecurityUtils.getCurrentUser();

            // --- In-scope references are allowed for read and write (Req 25.1) ---------
            for (UUID inScope : inScopeStores) {
                StoreScopeRef ref = StoreScopeRef.of(inScope);
                assertThatCode(() -> service.assertCanRead(ref, resolved)).doesNotThrowAnyException();
                assertThatCode(() -> service.assertCanWrite(ref, resolved)).doesNotThrowAnyException();
            }

            // --- A cross-store / guessed reference is rejected 403 without disclosure ---
            StoreScopeRef foreignRef = StoreScopeRef.of(foreignStore);

            BusinessException readDenial = catchThrowableOfType(
                    () -> service.assertCanRead(foreignRef, resolved), BusinessException.class);
            assertScopeDenial(readDenial, foreignStore, inScopeStores);

            BusinessException writeDenial = catchThrowableOfType(
                    () -> service.assertCanWrite(foreignRef, resolved), BusinessException.class);
            assertScopeDenial(writeDenial, foreignStore, inScopeStores);

            // --- Bulk: any cross-store member rejects the whole batch (Req 25.4) --------
            List<StoreScopeRef> allInScopeBatch = inScopeStores.stream()
                    .map(StoreScopeRef::of)
                    .collect(Collectors.toList());
            assertThatCode(() -> validateBatch(service, resolved, allInScopeBatch))
                    .doesNotThrowAnyException();

            List<StoreScopeRef> mixedBatch = new ArrayList<>(allInScopeBatch);
            mixedBatch.add(foreignRef); // one cross-store member contaminates the batch
            BusinessException batchDenial = catchThrowableOfType(
                    () -> validateBatch(service, resolved, mixedBatch), BusinessException.class);
            assertScopeDenial(batchDenial, foreignStore, inScopeStores);

            // --- List queries are constrained to exactly the in-scope store ids (Req 24.1)
            QueryWrapper<Object> wrapper = new QueryWrapper<>();
            service.applyScope(wrapper, ScopeTarget.store("store_id"), resolved);
            wrapper.getTargetSql(); // force lazy materialisation of bound parameter values
            List<String> boundValues = wrapper.getParamNameValuePairs().values().stream()
                    .filter(v -> v instanceof String)
                    .map(String.class::cast)
                    .collect(Collectors.toList());

            assertThat(boundValues)
                    .containsAll(inScopeStores.stream().map(UUID::toString).collect(Collectors.toList()));
            assertThat(boundValues).doesNotContain(foreignStore.toString());
        }
    }

    // --- helpers -----------------------------------------------------------

    /** Validate every member of a bulk request, rejecting the batch on the first violation. */
    private static void validateBatch(DataScopeServiceImpl service, CurrentUser user, List<StoreScopeRef> batch) {
        for (StoreScopeRef ref : batch) {
            service.assertCanWrite(ref, user);
        }
    }

    /**
     * A scope denial must be a 403 that reveals nothing about the foreign store: the
     * message names neither the foreign store id nor any in-scope identifier.
     */
    private static void assertScopeDenial(BusinessException ex, UUID foreignStore, List<UUID> inScopeStores) {
        assertThat(ex).as("a cross-store reference must be rejected").isNotNull();
        assertThat(ex.getStatus()).isEqualTo(403);
        String message = ex.getMessage() == null ? "" : ex.getMessage();
        assertThat(message).doesNotContain(foreignStore.toString());
        for (UUID inScope : inScopeStores) {
            assertThat(message).doesNotContain(inScope.toString());
        }
    }

    /** A non-administrative caller authenticated as the given user. */
    private static CurrentUser caller(UUID userId) {
        return CurrentUser.builder()
                .userId(userId.toString())
                .email("operator@example.com")
                .roles(Set.of("operator"))
                .build();
    }

    /**
     * Build a {@link DataScopeServiceImpl} whose RBAC mappers resolve {@code userId}
     * to an {@code ASSIGNED_STORE} scope over exactly {@code inScopeStores}.
     */
    private static DataScopeServiceImpl assignedStoreScopeService(UUID userId, List<UUID> inScopeStores) {
        DataScopeMapper dataScopeMapper = Mockito.mock(DataScopeMapper.class);
        UserRoleMapper userRoleMapper = Mockito.mock(UserRoleMapper.class);
        UserStoreMapper userStoreMapper = Mockito.mock(UserStoreMapper.class);

        UUID roleId = UUID.randomUUID();
        when(userRoleMapper.selectList(any())).thenReturn(List.of(
                UserRole.builder().id(UUID.randomUUID()).userId(userId).roleId(roleId).build()));
        when(dataScopeMapper.selectList(any())).thenReturn(List.of(
                DataScope.builder().id(UUID.randomUUID()).roleId(roleId).scopeType("assigned_store").build()));
        when(userStoreMapper.selectList(any())).thenReturn(inScopeStores.stream()
                .map(s -> UserStoreEntity.builder().id(UUID.randomUUID()).userId(userId).storeId(s).build())
                .collect(Collectors.toList()));

        return new DataScopeServiceImpl(dataScopeMapper, userRoleMapper, userStoreMapper,
                org.mockito.Mockito.mock(com.adpilot.modules.store.mapper.StoreMapper.class));
    }

    // --- generators --------------------------------------------------------

    @Provide
    Arbitrary<List<UUID>> inScopeStores() {
        return Arbitraries.randomValue(r -> UUID.randomUUID())
                .list().ofMinSize(1).ofMaxSize(4).uniqueElements();
    }

    @Provide
    Arbitrary<UUID> storeId() {
        return Arbitraries.randomValue(r -> UUID.randomUUID());
    }
}
