package com.adpilot.modules.apisync.oauth.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One advertising profile/account returned by the Amazon Ads {@code /v2/profiles}
 * endpoint, projected to the fields the wizard needs to let the user pick which
 * profile(s) to bind. Carries no secrets.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AmazonAdsProfileVo {
    /** Amazon Advertising profile id (used as Amazon-Advertising-API-Scope). */
    private String profileId;
    /** Two-letter marketplace country code (e.g. US, CA, MX, BR, DE, JP). */
    private String countryCode;
    /** Currency code of the profile (e.g. USD). */
    private String currencyCode;
    /** Marketplace string id from accountInfo. */
    private String marketplaceId;
    /** Display name of the advertising account. */
    private String accountName;
    /** Seller string id / MerchantID from accountInfo. */
    private String sellerStringId;
    /** Account type (e.g. seller, vendor, agency). */
    private String accountType;
}
