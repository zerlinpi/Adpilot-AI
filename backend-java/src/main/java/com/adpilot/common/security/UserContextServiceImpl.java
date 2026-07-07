package com.adpilot.common.security;

import com.adpilot.modules.user.entity.Permission;
import com.adpilot.modules.user.entity.Role;
import com.adpilot.modules.user.entity.RolePermission;
import com.adpilot.modules.user.entity.User;
import com.adpilot.modules.user.entity.UserDepartment;
import com.adpilot.modules.user.entity.UserRole;
import com.adpilot.modules.user.mapper.PermissionMapper;
import com.adpilot.modules.user.mapper.RoleMapper;
import com.adpilot.modules.user.mapper.RolePermissionMapper;
import com.adpilot.modules.user.mapper.UserDepartmentMapper;
import com.adpilot.modules.user.mapper.UserMapper;
import com.adpilot.modules.user.mapper.UserRoleMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Default {@link UserContextService} that resolves the principal from the existing
 * RBAC tables (users, user_roles, roles, role_permissions, permissions,
 * user_departments) and caches the effective permission set in Redis with a short TTL.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserContextServiceImpl implements UserContextService {

    private static final String PERMISSION_CACHE_PREFIX = "user:permissions:";

    private final UserMapper userMapper;
    private final UserRoleMapper userRoleMapper;
    private final RoleMapper roleMapper;
    private final RolePermissionMapper rolePermissionMapper;
    private final PermissionMapper permissionMapper;
    private final UserDepartmentMapper userDepartmentMapper;
    private final RedisTemplate<String, Object> redisTemplate;

    @Value("${adpilot.security.permission-cache-ttl-seconds:300}")
    private long permissionCacheTtlSeconds;

    @Override
    public CurrentUser load(String userId) {
        if (userId == null || userId.isBlank()) {
            return null;
        }

        UUID userUuid;
        try {
            userUuid = UUID.fromString(userId);
        } catch (IllegalArgumentException e) {
            log.warn("Cannot load user context for non-UUID user id: {}", userId);
            return null;
        }

        User user = userMapper.selectById(userUuid);
        if (user == null) {
            log.debug("No user found for id {} when loading security context", userId);
            return null;
        }

        List<UUID> roleIds = getUserRoleIds(userUuid);
        Set<String> roleCodes = loadRoleCodes(roleIds);
        List<String> permissions = loadPermissions(userId, roleIds);
        String departmentId = loadPrimaryDepartmentId(userUuid);

        return CurrentUser.builder()
                .userId(user.getId() != null ? user.getId().toString() : userId)
                .email(user.getEmail())
                .name(user.getName())
                .orgId(user.getOrgId() != null ? user.getOrgId().toString() : null)
                .departmentId(departmentId)
                .roles(roleCodes)
                .permissions(permissions)
                .build();
    }

    @Override
    public void invalidate(String userId) {
        if (userId == null || userId.isBlank()) {
            return;
        }
        try {
            redisTemplate.delete(permissionCacheKey(userId));
            log.debug("Invalidated cached permission set for user {}", userId);
        } catch (Exception e) {
            // Never let a cache failure break a role/permission change.
            log.warn("Failed to invalidate permission cache for user {}: {}", userId, e.getMessage());
        }
    }

    /**
     * Load the effective permission set, preferring the Redis cache keyed by user id
     * and falling back to a DB read (which then repopulates the cache) on miss.
     */
    @SuppressWarnings("unchecked")
    private List<String> loadPermissions(String userId, List<UUID> roleIds) {
        String cacheKey = permissionCacheKey(userId);

        try {
            Object cached = redisTemplate.opsForValue().get(cacheKey);
            if (cached instanceof List<?> cachedList) {
                return cachedList.stream().map(String::valueOf).collect(Collectors.toList());
            }
        } catch (Exception e) {
            log.warn("Failed to read permission cache for user {}: {}", userId, e.getMessage());
        }

        List<String> permissions = resolvePermissionsFromDb(roleIds);

        try {
            redisTemplate.opsForValue().set(cacheKey, permissions,
                    Duration.ofSeconds(permissionCacheTtlSeconds));
        } catch (Exception e) {
            log.warn("Failed to cache permissions for user {}: {}", userId, e.getMessage());
        }

        return permissions;
    }

    private List<String> resolvePermissionsFromDb(List<UUID> roleIds) {
        if (roleIds.isEmpty()) {
            return new ArrayList<>();
        }

        LambdaQueryWrapper<RolePermission> rpWrapper = new LambdaQueryWrapper<>();
        rpWrapper.in(RolePermission::getRoleId, roleIds);
        List<UUID> permissionIds = rolePermissionMapper.selectList(rpWrapper).stream()
                .map(RolePermission::getPermissionId)
                .distinct()
                .collect(Collectors.toList());
        if (permissionIds.isEmpty()) {
            return new ArrayList<>();
        }

        LambdaQueryWrapper<Permission> pWrapper = new LambdaQueryWrapper<>();
        pWrapper.in(Permission::getId, permissionIds);
        return permissionMapper.selectList(pWrapper).stream()
                .map(Permission::getCode)
                .filter(Objects::nonNull)
                .distinct()
                .sorted()
                .collect(Collectors.toList());
    }

    private Set<String> loadRoleCodes(List<UUID> roleIds) {
        if (roleIds.isEmpty()) {
            return Set.of();
        }
        return roleMapper.selectBatchIds(roleIds).stream()
                .map(Role::getCode)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
    }

    private List<UUID> getUserRoleIds(UUID userUuid) {
        LambdaQueryWrapper<UserRole> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(UserRole::getUserId, userUuid);
        return userRoleMapper.selectList(wrapper).stream()
                .map(UserRole::getRoleId)
                .distinct()
                .collect(Collectors.toList());
    }

    /**
     * Resolve the user's primary department, falling back to any assigned department.
     */
    private String loadPrimaryDepartmentId(UUID userUuid) {
        LambdaQueryWrapper<UserDepartment> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(UserDepartment::getUserId, userUuid);
        List<UserDepartment> assignments = userDepartmentMapper.selectList(wrapper);
        if (assignments.isEmpty()) {
            return null;
        }
        return assignments.stream()
                .min(Comparator.comparing(ud -> Boolean.TRUE.equals(ud.getIsPrimary()) ? 0 : 1))
                .map(ud -> ud.getDepartmentId() != null ? ud.getDepartmentId().toString() : null)
                .orElse(null);
    }

    private String permissionCacheKey(String userId) {
        return PERMISSION_CACHE_PREFIX + userId;
    }
}
