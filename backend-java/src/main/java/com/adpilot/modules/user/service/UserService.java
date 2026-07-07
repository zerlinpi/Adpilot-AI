package com.adpilot.modules.user.service;

import com.adpilot.modules.user.entity.User;

import java.util.List;
import java.util.Optional;

public interface UserService {

    /**
     * Get user by ID.
     */
    Optional<User> getUserById(String userId);

    /**
     * Get user by email.
     */
    Optional<User> getUserByEmail(String email);

    /**
     * List all users for an organization.
     */
    List<User> listUsers(String orgId);

    /**
     * Create a new user.
     */
    User createUser(User user);

    /**
     * Update an existing user.
     */
    User updateUser(String userId, User user);

    /**
     * Assign roles to a user.
     */
    void assignRole(String userId, List<String> roleIds);

    /**
     * Get all permissions for a user (aggregated from all roles).
     */
    List<String> getUserPermissions(String userId);

    /**
     * Get primary role name for a user.
     */
    String getUserPrimaryRole(String userId);
}
