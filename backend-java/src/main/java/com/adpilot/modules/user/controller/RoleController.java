package com.adpilot.modules.user.controller;

import com.adpilot.modules.user.entity.Permission;
import com.adpilot.modules.user.entity.Role;
import com.adpilot.modules.user.entity.RolePermission;
import com.adpilot.modules.user.entity.UserRole;
import com.adpilot.modules.user.mapper.PermissionMapper;
import com.adpilot.modules.user.mapper.RolePermissionMapper;
import com.adpilot.modules.user.mapper.UserRoleMapper;
import com.adpilot.modules.user.service.RoleService;
import com.adpilot.common.security.RequirePermission;
import com.adpilot.common.utils.SecurityUtils;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/roles")
@RequiredArgsConstructor
public class RoleController {

    private final RoleService roleService;
    private final RolePermissionMapper rolePermissionMapper;
    private final UserRoleMapper userRoleMapper;
    private final PermissionMapper permissionMapper;

    /**
     * GET /api/roles
     * List all roles for the organization with permission/user counts.
     */
    @GetMapping
    @RequirePermission("role:view")
    public ResponseEntity<List<Map<String, Object>>> listRoles() {
        // Multi-tenant isolation: the org is always derived from the authenticated
        // security context and never accepted from the request, so a caller cannot
        // enumerate roles in another organization.
        String orgId = SecurityUtils.getCurrentOrgId();
        List<Role> roles = roleService.listRoles(orgId);
        List<Map<String, Object>> result = new ArrayList<>();
        for (Role r : roles) {
            Map<String, Object> m = new HashMap<>();
            m.put("id", r.getId() != null ? r.getId().toString() : null);
            m.put("name", r.getName());
            m.put("code", r.getCode());
            m.put("description", r.getDescription() != null ? r.getDescription() : "");
            m.put("permissionCount", countPermissions(r.getId()));
            m.put("userCount", countUsers(r.getId()));
            m.put("status", "active");
            result.add(m);
        }
        return ResponseEntity.ok(result);
    }

    private long countPermissions(UUID roleId) {
        if (roleId == null) return 0;
        LambdaQueryWrapper<RolePermission> w = new LambdaQueryWrapper<>();
        w.eq(RolePermission::getRoleId, roleId);
        return rolePermissionMapper.selectCount(w);
    }

    private long countUsers(UUID roleId) {
        if (roleId == null) return 0;
        LambdaQueryWrapper<UserRole> w = new LambdaQueryWrapper<>();
        w.eq(UserRole::getRoleId, roleId);
        return userRoleMapper.selectCount(w);
    }

    /**
     * POST /api/roles
     * Create a new role.
     */
    @PostMapping
    @RequirePermission("role:manage")
    public ResponseEntity<Role> createRole(@RequestBody Role role) {
        try {
            Role createdRole = roleService.createRole(role);
            return ResponseEntity.status(HttpStatus.CREATED).body(createdRole);
        } catch (RuntimeException e) {
            log.error("Failed to create role: {}", e.getMessage());
            return ResponseEntity.badRequest().build();
        }
    }

    /**
     * PATCH /api/roles/:id
     * Update an existing role.
     */
    @PatchMapping("/{id}")
    @RequirePermission("role:manage")
    public ResponseEntity<Role> updateRole(@PathVariable String id, @RequestBody Role role) {
        try {
            Role updatedRole = roleService.updateRole(id, role);
            return ResponseEntity.ok(updatedRole);
        } catch (RuntimeException e) {
            log.error("Failed to update role {}: {}", id, e.getMessage());
            return ResponseEntity.badRequest().build();
        }
    }

    /**
     * GET /api/roles/:id/permissions
     * Returns the full catalog of available permissions plus the subset of
     * permission ids currently assigned to the role, so the frontend can render
     * an editable selector showing every permission's assigned/unassigned state
     * (Req 3.1, 3.2).
     */
    @GetMapping("/{id}/permissions")
    @RequirePermission("role:view")
    public ResponseEntity<Map<String, Object>> getRolePermissions(@PathVariable String id) {
        // Multi-tenant isolation: only expose the permissions of a role in the caller's
        // org. A missing or cross-org role returns 404 so existence is never revealed.
        String orgId = SecurityUtils.getCurrentOrgId();
        Role role = roleService.getRoleById(id).orElse(null);
        if (role == null || role.getOrgId() == null || !role.getOrgId().toString().equals(orgId)) {
            return ResponseEntity.notFound().build();
        }

        // Full catalog, ordered by module then action for stable grouping.
        LambdaQueryWrapper<Permission> catalogWrapper = new LambdaQueryWrapper<>();
        catalogWrapper.orderByAsc(Permission::getModule).orderByAsc(Permission::getAction);
        List<Permission> catalog = permissionMapper.selectList(catalogWrapper);

        // Assigned permission ids for this role.
        LambdaQueryWrapper<RolePermission> rpWrapper = new LambdaQueryWrapper<>();
        rpWrapper.eq(RolePermission::getRoleId, UUID.fromString(id));
        List<String> assigned = rolePermissionMapper.selectList(rpWrapper).stream()
                .map(RolePermission::getPermissionId)
                .filter(java.util.Objects::nonNull)
                .map(UUID::toString)
                .distinct()
                .collect(java.util.stream.Collectors.toList());

        List<Map<String, Object>> permissions = new ArrayList<>();
        for (Permission p : catalog) {
            Map<String, Object> m = new HashMap<>();
            m.put("id", p.getId() != null ? p.getId().toString() : null);
            m.put("code", p.getCode());
            m.put("name", p.getName());
            m.put("module", p.getModule());
            m.put("action", p.getAction());
            m.put("description", p.getDescription() != null ? p.getDescription() : "");
            permissions.add(m);
        }

        Map<String, Object> result = new HashMap<>();
        result.put("permissions", permissions);
        result.put("assigned", assigned);
        return ResponseEntity.ok(result);
    }

    /**
     * POST /api/roles/:id/permissions
     * Assign permissions to a role.
     */
    @PostMapping("/{id}/permissions")
    @RequirePermission("role:manage")
    public ResponseEntity<Map<String, String>> assignPermissions(
            @PathVariable String id,
            @RequestBody List<String> permissionIds) {
        try {
            roleService.assignPermissions(id, permissionIds);
            return ResponseEntity.ok(Map.of("message", "Permissions assigned successfully"));
        } catch (RuntimeException e) {
            log.error("Failed to assign permissions to role {}: {}", id, e.getMessage());
            return ResponseEntity.badRequest().build();
        }
    }
}
