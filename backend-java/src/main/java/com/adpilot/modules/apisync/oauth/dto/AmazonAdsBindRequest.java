package com.adpilot.modules.apisync.oauth.dto;

import lombok.Data;

/**
 * Request body to bind a selected advertising profile to a store. The refresh
 * token captured during the callback is resolved server-side from the store's
 * pending connection (or the most recent authorization in the same region), so
 * no secret is accepted from the client.
 */
@Data
public class AmazonAdsBindRequest {
    private String storeId;
    private String profileId;
    private String region;
    private String marketplaceId;
    /** Seller string id / MerchantID of the profile's account. */
    private String sellerId;
    /** Optional display name for the connection. */
    private String accountName;

    /**
     * Existing Amazon Store_Group to assign the bound store to, selected during
     * the wizard flow (platform-workspace-rbac Req 4.5). When present it takes
     * precedence over {@link #storeGroupName}.
     */
    private String storeGroupId;

    /**
     * Name of a new Amazon Store_Group to create (or reuse if one with the same
     * name already exists) and assign the bound store to, when no
     * {@link #storeGroupId} is supplied (Req 4.5, 11.1).
     */
    private String storeGroupName;
}
