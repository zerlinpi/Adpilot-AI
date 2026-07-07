package com.adpilot.modules.apisync.dto;

import lombok.Data;

import java.util.Map;

/**
 * One-step Independent_Site_Connection_Wizard request (platform-workspace-rbac
 * Req 5.2, 5.4). The operator picks an independent-site platform
 * ({@code shopify} / {@code woocommerce} / {@code tiktok_shop}), names the
 * store, and supplies the platform credentials. The backend validates the
 * credentials against the external platform, then — only on success — creates
 * the {@code PlatformConnectionEntity}, binds the resulting Store, and assigns
 * the Store to the independent-site Store_Group system, all within one flow.
 */
@Data
public class IndependentSiteConnectRequest {

    /** Platform key: {@code shopify} | {@code woocommerce} | {@code tiktok_shop}. */
    private String platform;

    /**
     * The Nav_Block Platform_Family scope this connection is created under — the
     * connection entry's {@code ?platform=} value (e.g. {@code amazon} /
     * {@code independent_site} / {@code tiktok}). When present, the backend
     * enforces that {@link #platform} belongs to this family and rejects any
     * cross-family platform key (multistore-ai-ads-operations Req 6.2).
     */
    private String blockFamily;

    /** Display name for the new store (defaults to the platform label). */
    private String storeName;

    /** Platform credential fields keyed by field name (see PlatformConnector.fields). */
    private Map<String, String> config;

    /**
     * Optional target independent-site Store_Group. When omitted, the new Store
     * is assigned to the independent-site default Store_Group (Req 5.4, 10.7).
     */
    private String storeGroupId;
}
