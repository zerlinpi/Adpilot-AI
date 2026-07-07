package com.adpilot.modules.apisync.dto;

import lombok.Data;

import java.util.Map;

/**
 * Bind a Google Ads account to an existing independent-site Store from within
 * the Independent_Site_Connection_Wizard (platform-workspace-rbac Req 5.5).
 *
 * <p>Google Ads is an independent-site advertising account, never a standalone
 * channel: the resulting {@code PlatformConnectionEntity} is always bound to the
 * selected independent-site Store rather than created as an ad-only connection.</p>
 */
@Data
public class IndependentSiteGoogleAdsBindRequest {

    /** The independent-site Store the Google Ads account is bound to (required). */
    private String storeId;

    /** Display name for the Google Ads connection (defaults to a generated label). */
    private String connectionName;

    /** Google Ads credential fields keyed by field name (see PlatformConnector.fields). */
    private Map<String, String> config;
}
