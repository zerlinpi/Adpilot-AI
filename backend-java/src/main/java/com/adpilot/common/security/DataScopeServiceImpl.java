package com.adpilot.common.security;

import com.adpilot.modules.store.entity.StoreEntity;
import com.adpilot.modules.store.entity.UserStoreEntity;
import com.adpilot.modules.store.mapper.StoreMapper;
import com.adpilot.modules.store.mapper.UserStoreMapper;
import com.adpilot.modules.user.entity.DataScope;
import com.adpilot.modules.user.entity.UserRole;
import com.adpilot.modules.user.mapper.DataScopeMapper;
import com.adpilot.modules.user.mapper.UserRoleMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Default {@link DataScopeService}. Resolves a user's effective data scope from the
 * RBAC tables ({@code user_roles}, {@code data_scopes}, {@code user_stores}),
 * contributes a reusable {@link QueryWrapper} fragment to list queries, and guards
 * single-record reads/writes (Req 2.2 / 7.1).
 *
 * <p>Resolution follows the precedence
 * all-company &rarr; department &rarr; assigned-store/assigned-product &rarr; own,
 * choosing the broadest tier across the user's roles and unioning the permitted
 * identifiers within that tier (Req 7.1.4). Super-administrators bypass all
 * filtering (Req 2.2.5 / 7.1.3).</p>
 *
 * <p>Safety-by-default: when a scope cannot be expressed on the queried entity
 * (the relevant dimension column/field is absent), the service narrows to the
 * owner dimension when available and otherwise denies access, so an unrecognised
 * configuration never widens access.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DataScopeServiceImpl implements DataScopeService {

    /** Role code that bypasses all data-scope filtering. */
    private static final String SUPER_ADMIN_ROLE = "super_admin";

    /** Conventional entity field names per scope dimension, checked by the guards. */
    private static final List<String> STORE_FIELDS = List.of("storeId");
    private static final List<String> PRODUCT_FIELDS = List.of("productId");
    private static final List<String> STORE_GROUP_FIELDS = List.of("storeGroupId");
    private static final List<String> DEPARTMENT_FIELDS = List.of("departmentId", "deptId");
    private static final List<String> OWNER_FIELDS = List.of("ownerId", "createdBy", "userId", "createdByUserId");

    private final DataScopeMapper dataScopeMapper;
    private final UserRoleMapper userRoleMapper;
    private final UserStoreMapper userStoreMapper;
    private final StoreMapper storeMapper;

    // ---------------------------------------------------------------------
    // resolve
    // ---------------------------------------------------------------------

    @Override
    public EffectiveScope resolve(CurrentUser user) {
        if (user == null) {
            // No principal -> narrowest possible scope that matches nothing.
            return EffectiveScope.builder().type(ScopeType.OWN).build();
        }

        if (isSuperAdmin(user)) {
            return EffectiveScope.superAdmin();
        }

        UUID userId = parseUuidOrNull(user.getUserId());

        List<UUID> roleIds = roleIdsFor(userId);
        if (roleIds.isEmpty()) {
            return ownScope(userId);
        }

        List<DataScope> scopes = scopesForRoles(roleIds);
        if (scopes.isEmpty()) {
            return ownScope(userId);
        }

        int bestRank = scopes.stream()
                .map(s -> ScopeType.fromCode(s.getScopeType()))
                .mapToInt(ScopeType::rank)
                .min()
                .orElse(ScopeType.OWN.rank());

        if (bestRank == ScopeType.ALL_COMPANY.rank()) {
            return EffectiveScope.builder().type(ScopeType.ALL_COMPANY).build();
        }
        if (bestRank == ScopeType.DEPARTMENT.rank()) {
            return EffectiveScope.builder()
                    .type(ScopeType.DEPARTMENT)
                    .departmentId(parseUuidOrNull(user.getDepartmentId()))
                    .userId(userId)
                    .build();
        }
        if (bestRank == ScopeType.OWN.rank()) {
            return ownScope(userId);
        }

        // Rank 2: assigned-store, assigned-product, and assigned-store-group are peers;
        // union every granted dimension so the broadest combination across roles applies.
        boolean hasStoreScope = scopes.stream()
                .anyMatch(s -> ScopeType.fromCode(s.getScopeType()) == ScopeType.ASSIGNED_STORE);
        boolean hasProductScope = scopes.stream()
                .anyMatch(s -> ScopeType.fromCode(s.getScopeType()) == ScopeType.ASSIGNED_PRODUCT);
        boolean hasStoreGroupScope = scopes.stream()
                .anyMatch(s -> ScopeType.fromCode(s.getScopeType()) == ScopeType.ASSIGNED_STORE_GROUP);

        // Assigned-store is always sourced from the user's user_stores assignments,
        // never from the data_scopes.store_ids JSON (Req 2.2.4 / task note).
        Set<UUID> storeIds = hasStoreScope ? assignedStoreIds(userId) : Set.of();

        Set<UUID> productIds = hasProductScope
                ? scopes.stream()
                    .filter(s -> ScopeType.fromCode(s.getScopeType()) == ScopeType.ASSIGNED_PRODUCT)
                    .map(DataScope::getProductIds)
                    .filter(java.util.Objects::nonNull)
                    .flatMap(List::stream)
                    .map(DataScopeServiceImpl::parseUuidOrNull)
                    .filter(java.util.Objects::nonNull)
                    .collect(Collectors.toSet())
                : Set.of();

        // Store_Group_Scope: union the store_group_ids carried by every assigned_store_group
        // row across the account's roles (Req 13.7).
        Set<UUID> storeGroupIds = hasStoreGroupScope
                ? scopes.stream()
                    .filter(s -> ScopeType.fromCode(s.getScopeType()) == ScopeType.ASSIGNED_STORE_GROUP)
                    .map(DataScope::getStoreGroupIds)
                    .filter(java.util.Objects::nonNull)
                    .flatMap(List::stream)
                    .map(DataScopeServiceImpl::parseUuidOrNull)
                    .filter(java.util.Objects::nonNull)
                    .collect(Collectors.toSet())
                : Set.of();

        // The singular tier marker only needs to identify a rank-2 assigned scope; the
        // active dimensions are determined per-dimension below (by the populated id sets)
        // so any combination of the three peers is honored simultaneously. Assigned-store
        // keeps precedence on the marker to preserve the legacy assigned-store honoring
        // unchanged when a store-group dimension co-exists (Req 18.4); the store-group
        // dimension stays active via its (non-empty) id set regardless of the marker.
        ScopeType resolvedType;
        if (hasStoreScope) {
            resolvedType = ScopeType.ASSIGNED_STORE;
        } else if (hasProductScope) {
            resolvedType = ScopeType.ASSIGNED_PRODUCT;
        } else {
            resolvedType = ScopeType.ASSIGNED_STORE_GROUP;
        }

        return EffectiveScope.builder()
                .type(resolvedType)
                .storeIds(storeIds)
                .productIds(productIds)
                .storeGroupIds(storeGroupIds)
                .userId(userId)
                .build();
    }

    // ---------------------------------------------------------------------
    // applyScope
    // ---------------------------------------------------------------------

    @Override
    public <T> void applyScope(QueryWrapper<T> wrapper, ScopeTarget target, CurrentUser user) {
        EffectiveScope scope = resolve(user);
        if (scope.isUnrestricted()) {
            return; // all-company / super-admin: no row restriction (Req 7.1.3 / 2.2.5)
        }
        if (target == null) {
            deny(wrapper);
            return;
        }

        switch (scope.getType()) {
            case DEPARTMENT -> {
                if (target.getDepartmentIdColumn() != null) {
                    if (scope.getDepartmentId() != null) {
                        wrapper.eq(target.getDepartmentIdColumn(), scope.getDepartmentId().toString());
                    } else {
                        deny(wrapper);
                    }
                } else {
                    applyOwnerOrDeny(wrapper, target, scope);
                }
            }
            case ASSIGNED_STORE, ASSIGNED_PRODUCT, ASSIGNED_STORE_GROUP -> applyStoreProduct(wrapper, target, scope);
            case OWN -> applyOwnerOrDeny(wrapper, target, scope);
            default -> {
                // ALL_COMPANY is unrestricted and handled above.
            }
        }
    }

    private <T> void applyStoreProduct(QueryWrapper<T> wrapper, ScopeTarget target, EffectiveScope scope) {
        boolean storeUsable = target.getStoreIdColumn() != null && storeDimensionActive(scope);
        boolean productUsable = target.getProductIdColumn() != null && productDimensionActive(scope);
        // The store-group dimension is expressible either directly (the entity carries a
        // store_group_id column) or by resolving the entity's store -> group via the
        // stores table (Req 13.6). Both route through this shared layer.
        boolean groupViaGroupColumn = target.getStoreGroupIdColumn() != null && storeGroupDimensionActive(scope);
        boolean groupViaStoreColumn = !groupViaGroupColumn
                && target.getStoreIdColumn() != null && storeGroupDimensionActive(scope);
        boolean groupUsable = groupViaGroupColumn || groupViaStoreColumn;

        if (!storeUsable && !productUsable && !groupUsable) {
            // Entity exposes none of the assigned dimensions: narrow to owner or deny.
            applyOwnerOrDeny(wrapper, target, scope);
            return;
        }

        List<String> storeIds = toStringList(scope.getStoreIds());
        List<String> productIds = toStringList(scope.getProductIds());
        List<String> groupIds = toStringList(scope.getStoreGroupIds());

        boolean storePred = storeUsable && !storeIds.isEmpty();
        boolean productPred = productUsable && !productIds.isEmpty();
        // When the store-group dimension is usable it always contributes a predicate, even
        // if it resolves to an empty id set, so an account scoped to no (or empty) groups
        // matches no rows rather than widening (the Store-Group isolation invariant, Req 16.1).
        boolean groupPred = groupUsable;

        if (!storePred && !productPred && !groupPred) {
            // Dimensions apply but the user is assigned to nothing -> match no rows.
            deny(wrapper);
            return;
        }

        // Resolve the in-scope store ids once when the group dimension is expressed through
        // the entity's store column.
        List<String> groupStoreIds = groupViaStoreColumn ? storeIdsInGroups(scope.getStoreGroupIds()) : List.of();

        wrapper.and(w -> {
            boolean needOr = false;
            if (storePred) {
                w.in(target.getStoreIdColumn(), storeIds);
                needOr = true;
            }
            if (productPred) {
                if (needOr) {
                    w.or();
                }
                w.in(target.getProductIdColumn(), productIds);
                needOr = true;
            }
            if (groupPred) {
                if (needOr) {
                    w.or();
                }
                if (groupViaGroupColumn) {
                    if (groupIds.isEmpty()) {
                        w.apply("1 = 0");
                    } else {
                        w.in(target.getStoreGroupIdColumn(), groupIds);
                    }
                } else { // groupViaStoreColumn
                    if (groupStoreIds.isEmpty()) {
                        w.apply("1 = 0");
                    } else {
                        w.in(target.getStoreIdColumn(), groupStoreIds);
                    }
                }
            }
        });
    }

    private <T> void applyOwnerOrDeny(QueryWrapper<T> wrapper, ScopeTarget target, EffectiveScope scope) {
        if (target.getOwnerIdColumn() != null && scope.getUserId() != null) {
            wrapper.eq(target.getOwnerIdColumn(), scope.getUserId().toString());
        } else {
            deny(wrapper);
        }
    }

    // ---------------------------------------------------------------------
    // single-record guards
    // ---------------------------------------------------------------------

    @Override
    public void assertCanRead(Object entity, CurrentUser user) {
        assertInScope(entity, user);
    }

    @Override
    public void assertCanWrite(Object entity, CurrentUser user) {
        assertInScope(entity, user);
    }

    /**
     * Single shared scope decision for both single-record reads and writes. Read
     * ({@link #assertCanRead}) and write ({@link #assertCanWrite}) both route through
     * here so they reach the identical allow/deny outcome for a given (account, record)
     * pair (read/write isolation consistency, Req 16.4). A record outside scope
     * (including Cross_Group_Access) is rejected with HTTP 403 (Req 13.3, 13.4).
     */
    private void assertInScope(Object entity, CurrentUser user) {
        EffectiveScope scope = resolve(user);
        if (scope.isUnrestricted()) {
            return;
        }
        if (entity == null || !withinScope(scope, entity)) {
            throw new com.adpilot.common.exception.BusinessException(
                    403, "FORBIDDEN", "Access to the requested record is outside your data scope");
        }
    }

    private boolean withinScope(EffectiveScope scope, Object entity) {
        switch (scope.getType()) {
            case DEPARTMENT -> {
                if (scope.getDepartmentId() == null) {
                    return false;
                }
                String dept = readField(entity, DEPARTMENT_FIELDS);
                return matches(dept, scope.getDepartmentId());
            }
            case ASSIGNED_STORE, ASSIGNED_PRODUCT, ASSIGNED_STORE_GROUP -> {
                boolean ok = false;
                if (storeDimensionActive(scope)) {
                    String store = readField(entity, STORE_FIELDS);
                    ok = containsId(scope.getStoreIds(), store);
                }
                if (!ok && productDimensionActive(scope)) {
                    String product = readField(entity, PRODUCT_FIELDS);
                    ok = containsId(scope.getProductIds(), product);
                }
                if (!ok && storeGroupDimensionActive(scope)) {
                    ok = recordStoreGroupInScope(scope, entity);
                }
                return ok;
            }
            case OWN -> {
                if (scope.getUserId() == null) {
                    return false;
                }
                String owner = readField(entity, OWNER_FIELDS);
                return matches(owner, scope.getUserId());
            }
            default -> {
                return true; // ALL_COMPANY (unrestricted handled earlier)
            }
        }
    }

    /**
     * Decide whether {@code entity}'s Store_Group is within the account's
     * Store_Group_Scope. The record's group is taken directly when it carries a
     * {@code storeGroupId}; otherwise the record's store is resolved to its group via
     * the stores table (Req 13.6). An unresolvable group is denied so an unknown
     * configuration never widens access.
     */
    private boolean recordStoreGroupInScope(EffectiveScope scope, Object entity) {
        String group = readField(entity, STORE_GROUP_FIELDS);
        if (group != null) {
            return containsId(scope.getStoreGroupIds(), group);
        }
        String store = readField(entity, STORE_FIELDS);
        UUID resolved = groupForStore(store);
        return resolved != null && containsId(scope.getStoreGroupIds(), resolved.toString());
    }

    // ---------------------------------------------------------------------
    // helpers
    // ---------------------------------------------------------------------

    private boolean isSuperAdmin(CurrentUser user) {
        Set<String> roles = user.getRoles();
        return roles != null && roles.contains(SUPER_ADMIN_ROLE);
    }

    private EffectiveScope ownScope(UUID userId) {
        return EffectiveScope.builder().type(ScopeType.OWN).userId(userId).build();
    }

    private List<UUID> roleIdsFor(UUID userId) {
        if (userId == null) {
            return List.of();
        }
        LambdaQueryWrapper<UserRole> w = new LambdaQueryWrapper<>();
        w.eq(UserRole::getUserId, userId);
        return userRoleMapper.selectList(w).stream()
                .map(UserRole::getRoleId)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());
    }

    private List<DataScope> scopesForRoles(List<UUID> roleIds) {
        LambdaQueryWrapper<DataScope> w = new LambdaQueryWrapper<>();
        w.in(DataScope::getRoleId, roleIds);
        return dataScopeMapper.selectList(w);
    }

    private Set<UUID> assignedStoreIds(UUID userId) {
        if (userId == null) {
            return Set.of();
        }
        LambdaQueryWrapper<UserStoreEntity> w = new LambdaQueryWrapper<>();
        w.eq(UserStoreEntity::getUserId, userId);
        return userStoreMapper.selectList(w).stream()
                .map(UserStoreEntity::getStoreId)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());
    }

    private static boolean storeDimensionActive(EffectiveScope scope) {
        return scope.getType() == ScopeType.ASSIGNED_STORE
                || (scope.getStoreIds() != null && !scope.getStoreIds().isEmpty());
    }

    private static boolean productDimensionActive(EffectiveScope scope) {
        return scope.getType() == ScopeType.ASSIGNED_PRODUCT
                || (scope.getProductIds() != null && !scope.getProductIds().isEmpty());
    }

    private static boolean storeGroupDimensionActive(EffectiveScope scope) {
        return scope.getType() == ScopeType.ASSIGNED_STORE_GROUP
                || (scope.getStoreGroupIds() != null && !scope.getStoreGroupIds().isEmpty());
    }

    /** Resolve the ids of all stores belonging to any of the given store groups (Req 13.6). */
    private List<String> storeIdsInGroups(Set<UUID> groupIds) {
        if (groupIds == null || groupIds.isEmpty()) {
            return List.of();
        }
        List<String> groupIdStrings = groupIds.stream().map(UUID::toString).collect(Collectors.toList());
        LambdaQueryWrapper<StoreEntity> w = new LambdaQueryWrapper<>();
        w.in(StoreEntity::getStoreGroupId, groupIdStrings);
        return storeMapper.selectList(w).stream()
                .map(StoreEntity::getId)
                .filter(java.util.Objects::nonNull)
                .map(UUID::toString)
                .collect(Collectors.toList());
    }

    /** Resolve a single store id to its store group, or null when unknown (Req 13.6). */
    private UUID groupForStore(String storeId) {
        UUID id = parseUuidOrNull(storeId);
        if (id == null) {
            return null;
        }
        StoreEntity store = storeMapper.selectById(id);
        return store != null ? store.getStoreGroupId() : null;
    }

    private static List<String> toStringList(Set<UUID> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return ids.stream().map(UUID::toString).collect(Collectors.toList());
    }

    private static boolean containsId(Set<UUID> ids, String value) {
        if (ids == null || ids.isEmpty() || value == null) {
            return false;
        }
        return ids.stream().anyMatch(id -> matches(value, id));
    }

    private static boolean matches(String value, UUID id) {
        if (value == null || id == null) {
            return false;
        }
        return value.equalsIgnoreCase(id.toString());
    }

    private static <T> void deny(QueryWrapper<T> wrapper) {
        wrapper.apply("1 = 0");
    }

    /** Read the first matching field (searching the class hierarchy) as a String, or null. */
    private static String readField(Object entity, List<String> candidates) {
        if (entity == null) {
            return null;
        }
        for (String name : candidates) {
            Class<?> type = entity.getClass();
            while (type != null && type != Object.class) {
                try {
                    Field field = type.getDeclaredField(name);
                    field.setAccessible(true);
                    Object value = field.get(entity);
                    return value != null ? value.toString() : null;
                } catch (NoSuchFieldException e) {
                    type = type.getSuperclass();
                } catch (IllegalAccessException e) {
                    return null;
                }
            }
        }
        return null;
    }

    private static UUID parseUuidOrNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
