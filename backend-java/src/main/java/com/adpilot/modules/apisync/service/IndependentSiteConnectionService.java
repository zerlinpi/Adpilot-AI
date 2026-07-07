package com.adpilot.modules.apisync.service;

import com.adpilot.modules.apisync.dto.IndependentSiteConnectRequest;
import com.adpilot.modules.apisync.dto.IndependentSiteGoogleAdsBindRequest;
import com.adpilot.modules.apisync.vo.PlatformConnectionVo;

/**
 * Independent_Site_Connection_Wizard backend (platform-workspace-rbac Req 5).
 *
 * <p>A single entry that accepts Shopify / WooCommerce / TikTok credentials,
 * creates the {@code PlatformConnectionEntity}, binds the resulting Store, and
 * assigns the Store to the independent-site Store_Group system — all in one
 * flow (Req 5.2, 5.4). Google Ads is bound to a selected independent-site Store
 * rather than created as a standalone ad-only connection (Req 5.5).</p>
 *
 * <p>Credentials are validated against the external platform first; if they are
 * rejected, no Store or connection is created or mutated and the rejection
 * reason is surfaced (Req 5.3).</p>
 */
public interface IndependentSiteConnectionService {

    /**
     * Connect an independent-site Store in one flow: validate the submitted
     * credentials against the external platform, then create the Store and its
     * {@code PlatformConnectionEntity} and assign the Store to the independent-site
     * Store_Group system (Req 5.2, 5.4).
     *
     * @throws com.adpilot.common.exception.BusinessException when the platform is
     *     not an independent-site platform, the credentials are incomplete, or the
     *     external platform rejects the credentials — leaving any existing
     *     connection unchanged (Req 5.3)
     */
    PlatformConnectionVo connectStore(IndependentSiteConnectRequest request, String userId);

    /**
     * Bind a Google Ads account to an existing independent-site Store (Req 5.5).
     * Validates the Google Ads credentials, then associates the Google Ads
     * connection with the selected Store rather than creating a standalone
     * ad-only connection.
     *
     * @throws com.adpilot.common.exception.BusinessException when the Store is not
     *     an independent-site Store, the credentials are incomplete, or Google Ads
     *     rejects the credentials — leaving any existing connection unchanged (Req 5.3)
     */
    PlatformConnectionVo bindGoogleAds(IndependentSiteGoogleAdsBindRequest request, String userId);
}
