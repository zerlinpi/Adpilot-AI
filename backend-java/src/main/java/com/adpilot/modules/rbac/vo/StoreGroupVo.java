package com.adpilot.modules.rbac.vo;

import lombok.Builder;
import lombok.Data;

/**
 * View model for a Store_Group returned to the frontend (platform-workspace-rbac
 * Req 10, 11). UUID identifiers are surfaced as strings to match the existing
 * VO convention (see {@code StoreVo}).
 */
@Data
@Builder
public class StoreGroupVo {

    private String id;
    private String orgId;
    private String name;
    /** Platform family code: {@code amazon} | {@code independent_site}. */
    private String platformFamily;
    /** Whether this is the per-family default fallback group (Req 10.7). */
    private Boolean isDefault;
    private String createdBy;
    private String createdAt;
}
