package com.adpilot.common.security;

/**
 * Loads the full security principal for a user (effective permission set, orgId,
 * departmentId, roles) so the JWT principal can be populated per request.
 *
 * <p>The effective permission set is cached in Redis keyed by user id with a short
 * TTL. {@link #invalidate(String)} clears that cache and must be invoked whenever a
 * user's roles or permissions change so the change takes effect without re-login
 * (Req 3.1.5).
 */
public interface UserContextService {

    /**
     * Build a fully populated {@link CurrentUser} for the given user id, loading
     * permissions, orgId, departmentId, roles, name, and email.
     *
     * @param userId the user id (UUID string)
     * @return the populated principal, or {@code null} when the user cannot be resolved
     */
    CurrentUser load(String userId);

    /**
     * Invalidate the cached permission set for a user. Invoked on role/permission
     * change so subsequent requests reload the effective permissions (Req 3.1.5).
     *
     * @param userId the user id (UUID string)
     */
    void invalidate(String userId);
}
