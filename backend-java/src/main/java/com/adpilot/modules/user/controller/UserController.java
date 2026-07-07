package com.adpilot.modules.user.controller;

import com.adpilot.modules.user.entity.User;
import com.adpilot.modules.user.service.UserService;
import com.adpilot.common.security.RequirePermission;
import com.adpilot.common.utils.SecurityUtils;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    /**
     * GET /api/users
     * List all users for the caller's organization. The org is always derived from the
     * authenticated security context; it is never accepted from the request so a caller
     * cannot enumerate users in another organization.
     */
    @GetMapping
    @RequirePermission("user:view")
    public ResponseEntity<List<User>> listUsers() {
        String orgId = SecurityUtils.getCurrentOrgId();
        List<User> users = userService.listUsers(orgId);
        return ResponseEntity.ok(users);
    }

    /**
     * GET /api/users/:id
     * Get a user by ID. Returns 404 when the user does not exist OR belongs to a
     * different organization, so cross-org access never reveals a user's existence.
     */
    @GetMapping("/{id}")
    @RequirePermission("user:view")
    public ResponseEntity<User> getUser(@PathVariable String id) {
        String orgId = SecurityUtils.getCurrentOrgId();
        return userService.getUserById(id)
                .filter(u -> u.getOrgId() != null && u.getOrgId().toString().equals(orgId))
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * POST /api/users
     * Create a new user.
     */
    @PostMapping
    @RequirePermission("user:manage")
    public ResponseEntity<User> createUser(@Valid @RequestBody User user) {
        // Validation and duplicate-email errors are raised as BusinessException and
        // serialized to the standard JSON error envelope by GlobalExceptionHandler
        // (Req 5.3, 5.4, 5.6). The @RequirePermission aspect enforces user:manage (Req 5.5).
        User createdUser = userService.createUser(user);
        return ResponseEntity.status(HttpStatus.CREATED).body(createdUser);
    }

    /**
     * PATCH /api/users/:id
     * Update an existing user.
     */
    @PatchMapping("/{id}")
    @RequirePermission("user:manage")
    public ResponseEntity<User> updateUser(@PathVariable String id, @RequestBody User user) {
        try {
            User updatedUser = userService.updateUser(id, user);
            return ResponseEntity.ok(updatedUser);
        } catch (RuntimeException e) {
            log.error("Failed to update user {}: {}", id, e.getMessage());
            return ResponseEntity.badRequest().build();
        }
    }

    /**
     * POST /api/users/:id/roles
     * Assign roles to a user.
     */
    @PostMapping("/{id}/roles")
    @RequirePermission("user:manage")
    public ResponseEntity<Map<String, String>> assignRoles(
            @PathVariable String id,
            @RequestBody List<String> roleIds) {
        try {
            userService.assignRole(id, roleIds);
            return ResponseEntity.ok(Map.of("message", "Roles assigned successfully"));
        } catch (RuntimeException e) {
            log.error("Failed to assign roles to user {}: {}", id, e.getMessage());
            return ResponseEntity.badRequest().build();
        }
    }

    /**
     * GET /api/users/:id/permissions
     * Get all permissions for a user.
     */
    @GetMapping("/{id}/permissions")
    public ResponseEntity<List<String>> getUserPermissions(@PathVariable String id) {
        List<String> permissions = userService.getUserPermissions(id);
        return ResponseEntity.ok(permissions);
    }
}
