package com.adpilot.modules.audit.service;

import com.adpilot.common.api.PageResponse;
import com.adpilot.modules.audit.vo.AuditLogVo;

import java.util.UUID;

public interface AuditLogService {

    /**
     * Create an audit log entry.
     *
     * @param userId     the user performing the action
     * @param orgId      the organization
     * @param action     the action performed (e.g., "CREATE", "UPDATE", "DELETE")
     * @param entityType the type of entity affected
     * @param entityId   the ID of the entity affected
     * @param details    additional details (will be serialized to JSON)
     */
    void createLog(UUID userId, UUID orgId, String action, String entityType, UUID entityId, Object details);

    /**
     * List audit logs with pagination, optionally filtered by action and/or
     * entity type (Requirement 11.6). Filter matching is case-insensitive; a
     * {@code null} or blank filter is ignored.
     *
     * @param action     optional action code filter (e.g. "create"), or null for all
     * @param entityType optional entity-type code filter (e.g. "user"), or null for all
     */
    PageResponse<AuditLogVo> listAuditLogs(String action, String entityType, int page, int pageSize);
}
