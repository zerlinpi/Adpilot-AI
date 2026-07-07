package com.adpilot.modules.user.service;

import com.adpilot.modules.user.entity.Role;

import java.util.List;
import java.util.Optional;

public interface RoleService {

    /**
     * List all roles for an organization.
     */
    List<Role> listRoles(String orgId);

    /**
     * Get role by ID.
     */
    Optional<Role> getRoleById(String roleId);

    /**
     * Create a new role.
     */
    Role createRole(Role role);

    /**
     * Update an existing role.
     */
    Role updateRole(String roleId, Role role);

    /**
     * Get permission codes for a role.
     */
    List<String> getRolePermissions(String roleId);

    /**
     * Assign permissions to a role.
     */
    void assignPermissions(String roleId, List<String> permissionIds);
}
