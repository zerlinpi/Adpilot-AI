package com.adpilot.modules.apisync.vo;

import com.adpilot.modules.advertising.operation.OperationResult;
import lombok.Builder;
import lombok.Data;

/**
 * Response view for a manual Google Ads write (platform-workspace-rbac Req 7.1, 7.2).
 *
 * <p>Surfaces the created Operation's identity and resolved lifecycle state so the
 * Google Ads module can show the pending change through the Pending_Overlay
 * (Req 7.6). A successful create/adjust means the Operation was persisted and
 * enqueued for platform submission — NOT that Google Ads has applied it yet.</p>
 */
@Data
@Builder
public class GoogleAdsOperationVo {

    /** The persisted Operation's id. */
    private String operationId;

    /** The logical Operation id, stable across attempts of the same logical change. */
    private String logicalOperationId;

    /** The owning Store. */
    private String storeId;

    /** The execution scope (always {@code PLATFORM_MUTATION} for a Google Ads write). */
    private String operationScope;

    /** The resolved Sync_State at creation ({@code pending} for a write-capable Store). */
    private String syncState;

    /** Target entity type. */
    private String entityType;

    /** Target entity id. */
    private String entityId;

    /** {@code true} when the activation was coalesced into a pre-existing logical Operation. */
    private boolean coalesced;

    /** Build the view from an {@link OperationResult}. */
    public static GoogleAdsOperationVo from(OperationResult result) {
        return GoogleAdsOperationVo.builder()
                .operationId(result.getOperationId() == null ? null : result.getOperationId().toString())
                .logicalOperationId(result.getLogicalOperationId() == null
                        ? null : result.getLogicalOperationId().toString())
                .storeId(result.getStoreId() == null ? null : result.getStoreId().toString())
                .operationScope(result.getOperationScope() == null ? null : result.getOperationScope().name())
                .syncState(result.getSyncState() == null ? null : result.getSyncState().name())
                .entityType(result.getEntityType())
                .entityId(result.getEntityId() == null ? null : result.getEntityId().toString())
                .coalesced(result.isCoalesced())
                .build();
    }
}
