package com.adpilot.modules.apisync.oauth;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Configuration for the Amazon Ads OAuth (Login with Amazon) store-connection
 * flow, bound from {@code adpilot.amazon-ads.*} in application.yml and fully
 * overridable by environment variables.
 *
 * <p>SECURITY: {@link #clientSecret} is a secret. It is read only from config,
 * transmitted only to Amazon's official token endpoint, and is never logged or
 * returned to clients. {@link #clientId}, {@link #clientSecret} and
 * {@link #redirectUri} are intentionally BLANK by default so the application
 * starts cleanly; callers must check {@link #isConfigured()} and fail gracefully
 * with a 4xx (never a 500/NPE) when credentials are missing.</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "adpilot.amazon-ads")
public class AmazonAdsProperties {

    /** LWA client id (e.g. {@code amzn1.application-oa2-client...}). Blank until configured. */
    private String clientId = "";

    /** LWA client secret (SECRET). Blank until configured. */
    private String clientSecret = "";

    /**
     * Registered Allowed Return URL. Must match exactly here and in the Amazon
     * LWA app. Blank until configured.
     */
    private String redirectUri = "";

    /** OAuth scope requested on the authorize URL (ad-account management). */
    private String scope = "advertising::campaign_management";

    /** How long a generated CSRF state stays valid (seconds). */
    private long stateTtlSeconds = 900;

    /** Per-region hosts, keyed by region code ({@code na}/{@code eu}/{@code fe}). */
    private Map<String, Region> regions = new LinkedHashMap<>();

    /** Per-region LWA + Ads API hosts. */
    @Data
    public static class Region {
        /** LWA authorize host (e.g. {@code https://www.amazon.com}). */
        private String authorizeHost;
        /** LWA token endpoint (e.g. {@code https://api.amazon.com/auth/o2/token}). */
        private String tokenUrl;
        /** Amazon Ads API host (e.g. {@code https://advertising-api.amazon.com}). */
        private String apiHost;
    }

    /** True only when all three required credentials are present. */
    public boolean isConfigured() {
        return notBlank(clientId) && notBlank(clientSecret) && notBlank(redirectUri);
    }

    /**
     * Resolve the {@link Region} config for a region key, merging any configured
     * values over built-in defaults so the flow works even if a region block is
     * partially or entirely absent from config. Unknown regions fall back to NA.
     */
    public Region region(String rawKey) {
        String key = normalizeRegion(rawKey);
        Region defaults = builtinDefault(key);
        Region configured = regions != null ? regions.get(key) : null;
        if (configured == null) {
            return defaults;
        }
        Region merged = new Region();
        merged.setAuthorizeHost(firstNonBlank(configured.getAuthorizeHost(), defaults.getAuthorizeHost()));
        merged.setTokenUrl(firstNonBlank(configured.getTokenUrl(), defaults.getTokenUrl()));
        merged.setApiHost(firstNonBlank(configured.getApiHost(), defaults.getApiHost()));
        return merged;
    }

    /** Normalize a region key to {@code na}/{@code eu}/{@code fe}, defaulting to {@code na}. */
    public static String normalizeRegion(String raw) {
        if (raw == null || raw.isBlank()) {
            return "na";
        }
        String k = raw.trim().toLowerCase();
        return switch (k) {
            case "eu" -> "eu";
            case "fe", "fp", "apac" -> "fe";
            default -> "na";
        };
    }

    private static Region builtinDefault(String key) {
        Region r = new Region();
        switch (key) {
            case "eu" -> {
                r.setAuthorizeHost("https://eu.account.amazon.com");
                r.setTokenUrl("https://api.amazon.co.uk/auth/o2/token");
                r.setApiHost("https://advertising-api-eu.amazon.com");
            }
            case "fe" -> {
                r.setAuthorizeHost("https://apac.account.amazon.com");
                r.setTokenUrl("https://api.amazon.co.jp/auth/o2/token");
                r.setApiHost("https://advertising-api-fe.amazon.com");
            }
            default -> {
                r.setAuthorizeHost("https://www.amazon.com");
                r.setTokenUrl("https://api.amazon.com/auth/o2/token");
                r.setApiHost("https://advertising-api.amazon.com");
            }
        }
        return r;
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static String firstNonBlank(String a, String b) {
        return notBlank(a) ? a : b;
    }
}
