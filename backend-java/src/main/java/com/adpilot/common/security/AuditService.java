package com.adpilot.common.security;

/**
 * Cross-cutting audit facility for authorization decisions.
 *
 * <p>Satisfies Requirement 2.1.5: whenever the permission layer authorizes or
 * rejects a request, the system records the user identity and the requested
 * operation for audit purposes. Both permit and deny decisions are recorded.
 *
 * <p>Records are persisted to the existing {@code audit_logs} table via the
 * {@code AuditLogMapper}; this service does not introduce a parallel store.
 */
public interface AuditService {

    /**
     * Record an authorization decision for the current request's user.
     *
     * @param operation the requested operation (for example, the required
     *                  permission such as {@code "campaign:update"} or a
     *                  method/endpoint identifier)
     * @param permitted {@code true} when the operation was authorized,
     *                  {@code false} when it was rejected
     */
    void recordAuthorizationDecision(String operation, boolean permitted);

    /**
     * Convenience wrapper recording a permitted authorization decision.
     *
     * @param operation the requested operation that was authorized
     */
    default void recordPermit(String operation) {
        recordAuthorizationDecision(operation, true);
    }

    /**
     * Convenience wrapper recording a denied authorization decision.
     *
     * @param operation the requested operation that was rejected
     */
    default void recordDeny(String operation) {
        recordAuthorizationDecision(operation, false);
    }
}
