package com.adpilot.modules.user.service.impl;

import com.adpilot.common.exception.BusinessException;
import com.adpilot.common.security.CurrentUser;
import com.adpilot.common.security.UserContextService;
import com.adpilot.common.utils.SecurityUtils;
import com.adpilot.modules.audit.service.AuditLogService;
import com.adpilot.modules.user.entity.Permission;
import com.adpilot.modules.user.entity.Role;
import com.adpilot.modules.user.entity.RolePermission;
import com.adpilot.modules.user.entity.UserRole;
import com.adpilot.modules.user.mapper.PermissionMapper;
import com.adpilot.modules.user.mapper.RoleMapper;
import com.adpilot.modules.user.mapper.RolePermissionMapper;
import com.adpilot.modules.user.mapper.UserRoleMapper;
import com.adpilot.modules.user.service.RoleService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class RoleServiceImpl implements RoleService {

    private final RoleMapper roleMapper;
    private final RolePermissionMapper rolePermissionMapper;
    private final PermissionMapper permissionMapper;
    private final UserRoleMapper userRoleMapper;
    private final UserContextService userContextService;
    private final AuditLogService auditLogService;

    @Override
    public List<Role> listRoles(String orgId) {
        LambdaQueryWrapper<Role> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Role::getOrgId, UUID.fromString(orgId));
        wrapper.orderByAsc(Role::getName);
        return roleMapper.selectList(wrapper);
    }

    @Override
    public Optional<Role> getRoleById(String roleId) {
        return Optional.ofNullable(roleMapper.selectById(UUID.fromString(roleId)));
    }

    @Override
    @Transactional
    public Role createRole(Role role) {
        // Multi-tenant isolation: the role's org is always the caller's org from the
        // authenticated context; any client-supplied orgId in the body is ignored. When
        // there is no interactive principal (system actor) the entity's org is preserved.
        UUID contextOrgId = resolveOrgId();
        if (contextOrgId != null) {
            role.setOrgId(contextOrgId);
        }

        // Check for duplicate code within org
        LambdaQueryWrapper<Role> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Role::getOrgId, role.getOrgId());
        wrapper.eq(Role::getCode, role.getCode());
        Long count = roleMapper.selectCount(wrapper);
        if (count > 0) {
            throw new BusinessException(409, "ROLE_CODE_EXISTS", "Role code already exists: " + role.getCode());
        }

        role.setCreatedAt(LocalDateTime.now());
        role.setUpdatedAt(LocalDateTime.now());
        roleMapper.insert(role);
        log.info("Role created: {}", role.getCode());

        // Forensic audit trail (additive, best-effort): record the creation by its
        // non-secret code and name only.
        Map<String, Object> createDetails = new LinkedHashMap<>();
        createDetails.put("code", role.getCode());
        createDetails.put("name", role.getName());
        writeAudit("CREATE_ROLE", role.getId(), createDetails);

        return role;
    }

    @Override
    @Transactional
    public Role updateRole(String roleId, Role role) {
        Role existingRole = roleMapper.selectById(UUID.fromString(roleId));
        if (existingRole == null) {
            throw new BusinessException(404, "ROLE_NOT_FOUND", "Role not found: " + roleId);
        }
        // Multi-tenant isolation: reject a cross-org target as 404 (existence hidden).
        assertSameOrg(existingRole.getOrgId());

        // Collect the names of the fields that actually changed for the audit trail;
        // no field VALUES are captured.
        List<String> changedFields = new ArrayList<>();
        if (role.getName() != null) {
            existingRole.setName(role.getName());
            changedFields.add("name");
        }
        if (role.getDescription() != null) {
            existingRole.setDescription(role.getDescription());
            changedFields.add("description");
        }

        existingRole.setUpdatedAt(LocalDateTime.now());
        roleMapper.updateById(existingRole);
        log.info("Role updated: {}", existingRole.getCode());

        // Forensic audit trail (additive, best-effort): record WHICH fields changed by
        // name only.
        Map<String, Object> updateDetails = new LinkedHashMap<>();
        updateDetails.put("changedFields", changedFields);
        writeAudit("UPDATE_ROLE", existingRole.getId(), updateDetails);

        return existingRole;
    }

    @Override
    public List<String> getRolePermissions(String roleId) {
        log.debug("Getting permissions for role: {}", roleId);
        // Multi-tenant isolation: only expose permissions of a role in the caller's org.
        Role role = roleMapper.selectById(UUID.fromString(roleId));
        if (role == null) {
            throw new BusinessException(404, "ROLE_NOT_FOUND", "Role not found: " + roleId);
        }
        assertSameOrg(role.getOrgId());

        LambdaQueryWrapper<RolePermission> rpWrapper = new LambdaQueryWrapper<>();
        rpWrapper.eq(RolePermission::getRoleId, UUID.fromString(roleId));
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
                .distinct()
                .sorted()
                .collect(Collectors.toList());
    }

    @Override
    @Transactional
    public void assignPermissions(String roleId, List<String> permissionIds) {
        UUID roleUuid = UUID.fromString(roleId);
        // Multi-tenant isolation: the target role must exist and belong to the caller's
        // org before its permission set is replaced; a cross-org target is rejected as 404.
        Role role = roleMapper.selectById(roleUuid);
        if (role == null) {
            throw new BusinessException(404, "ROLE_NOT_FOUND", "Role not found: " + roleId);
        }
        assertSameOrg(role.getOrgId());

        // Privilege-escalation guard: a role:manage holder may only attach permissions
        // they themselves hold, so they cannot bootstrap a role beyond their own rights.
        assertCallerHoldsPermissions(permissionIds);

        // Replace the role's permission set.
        LambdaQueryWrapper<RolePermission> del = new LambdaQueryWrapper<>();
        del.eq(RolePermission::getRoleId, roleUuid);
        rolePermissionMapper.delete(del);

        if (permissionIds != null) {
            for (String permId : permissionIds) {
                if (permId == null || permId.isBlank()) continue;
                RolePermission rp = RolePermission.builder()
                        .roleId(roleUuid)
                        .permissionId(UUID.fromString(permId))
                        .build();
                rolePermissionMapper.insert(rp);
            }
        }
        log.info("Assigned {} permission(s) to role {}", permissionIds != null ? permissionIds.size() : 0, roleId);

        // Permissions changed for this role: invalidate the cached permission set of
        // every user holding it so the change takes effect without re-login (Req 3.1.5).
        invalidatePermissionCacheForRole(roleUuid);

        // Forensic audit trail (additive, best-effort): recorded AFTER the successful
        // mutation and cache invalidation. Records the permission ids attached to the
        // role (ids only — no secret material is involved).
        Map<String, Object> assignDetails = new LinkedHashMap<>();
        assignDetails.put("permissionIds", permissionIds != null ? permissionIds : new ArrayList<>());
        writeAudit("ASSIGN_PERMISSIONS", roleUuid, assignDetails);
    }

    /**
     * Invalidate the cached permission set for all users assigned the given role.
     */
    private void invalidatePermissionCacheForRole(UUID roleUuid) {
        LambdaQueryWrapper<UserRole> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(UserRole::getRoleId, roleUuid);
        userRoleMapper.selectList(wrapper).stream()
                .map(UserRole::getUserId)
                .filter(java.util.Objects::nonNull)
                .map(UUID::toString)
                .distinct()
                .forEach(userContextService::invalidate);
    }

    /**
     * Enforce that the caller may attach every requested permission: a non-super_admin
     * caller must already hold each permission themselves, preventing a {@code role:manage}
     * holder from escalating a role beyond their own effective rights. A super_admin holds
     * everything. An unknown permission id is rejected as 404. Non-interactive (system)
     * actors with no security context skip this caller-based check.
     */
    private void assertCallerHoldsPermissions(List<String> permissionIds) {
        if (permissionIds == null || permissionIds.isEmpty()) {
            return;
        }
        if (!SecurityUtils.isAuthenticated()) {
            return;
        }
        CurrentUser caller = SecurityUtils.getCurrentUser();
        if (caller.getRoles() != null && caller.getRoles().contains("super_admin")) {
            return;
        }
        Set<String> callerPermissions = caller.getPermissions() != null
                ? new HashSet<>(caller.getPermissions())
                : new HashSet<>();

        for (String permId : permissionIds) {
            if (permId == null || permId.isBlank()) {
                continue;
            }
            UUID permUuid;
            try {
                permUuid = UUID.fromString(permId.trim());
            } catch (IllegalArgumentException e) {
                throw new BusinessException(400, "INVALID_PERMISSION", "Invalid permission id: " + permId);
            }
            Permission permission = permissionMapper.selectById(permUuid);
            if (permission == null) {
                throw new BusinessException(404, "PERMISSION_NOT_FOUND", "Permission not found");
            }
            if (!callerPermissions.contains(permission.getCode())) {
                throw new BusinessException(403, "PERMISSION_ESCALATION",
                        "Cannot assign a permission you do not hold");
            }
        }
    }

    /**
     * The caller's organization id from the authenticated security context, or
     * {@code null} for a non-interactive/system actor (mirroring OperationServiceImpl).
     * Never trusts a client-supplied org value.
     */
    private UUID resolveOrgId() {
        if (!SecurityUtils.isAuthenticated()) {
            return null;
        }
        return parseUuidOrNull(SecurityUtils.getCurrentOrgId());
    }

    /**
     * The acting user's id from the authenticated security context, or {@code null} for a
     * non-interactive/system actor. Used as the actor attribution on audit records.
     */
    private UUID resolveActorId() {
        return parseUuidOrNull(SecurityUtils.getCurrentUserIdOrNull());
    }

    /**
     * Write a forensic AUDIT-TRAIL entry for a state-changing RBAC role action. Auditing
     * is additive and best-effort: a failure here is logged and swallowed so it can never
     * break the business operation. The details map must never carry secret values.
     */
    private void writeAudit(String action, UUID entityId, Map<String, Object> details) {
        try {
            auditLogService.createLog(resolveActorId(), resolveOrgId(), action, "role", entityId, details);
        } catch (Exception ex) {
            log.warn("Failed to write audit log action={} entityId={}: {}", action, entityId, ex.getMessage());
        }
    }

    /**
     * Enforce that {@code entityOrgId} belongs to the caller's organization. Skipped for
     * a non-interactive/system actor. A cross-org mismatch is rejected as 404 so the
     * existence of roles in other organizations is never revealed.
     */
    private void assertSameOrg(UUID entityOrgId) {
        UUID callerOrgId = resolveOrgId();
        if (callerOrgId != null && !callerOrgId.equals(entityOrgId)) {
            throw new BusinessException(404, "ROLE_NOT_FOUND", "Role not found");
        }
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
