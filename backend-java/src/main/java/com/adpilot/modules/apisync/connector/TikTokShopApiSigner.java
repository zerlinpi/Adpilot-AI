package com.adpilot.modules.apisync.connector;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.TreeMap;

/**
 * Shared TikTok Shop Open API request signer and endpoint/URL builder.
 *
 * <p>Extracted so the single, canonical HMAC-SHA256 signing algorithm, the
 * pinned API version/base URL, and the product-create path are reused by both
 * the write-back connector ({@link TikTokShopWriteConnector}, the
 * Operation/Outbox path) and the product direct-publish service
 * ({@code IndependentSiteProductPublishService}, the product-upload path)
 * <strong>without forking the signature logic</strong>. There must be exactly
 * one TikTok signing implementation in the codebase; both callers delegate
 * here.</p>
 *
 * <p>The algorithm mirrors {@code TikTokConnector}'s documented read-side
 * signing: HMAC-SHA256 with key = {@code appSecret} over
 * {@code appSecret + path + (each sorted key+value, excluding sign &
 * access_token) + body + appSecret}, hex-encoded (lowercase).</p>
 *
 * <p><strong>VERIFICATION NOTE:</strong> targets TikTok Shop Open API
 * <strong>{@value #API_VERSION}</strong>. The version path, the
 * HMAC-SHA256 signing, and the {@code shop_cipher}/{@code shop_id} requirement
 * are version-specific and cannot be validated offline; verify against live
 * credentials and adjust if TikTok changes them.</p>
 */
public final class TikTokShopApiSigner {

    /** TikTok Shop Open API version targeted by the shared signer. */
    public static final String API_VERSION = "202309";

    /** TikTok Shop Open API base URL (matches {@code TikTokConnector}). */
    public static final String BASE = "https://open-api.tiktokglobalshop.com";

    private TikTokShopApiSigner() {
    }

    /** {@code POST /product/{ver}/products}. */
    public static String productCreatePath() {
        return "/product/" + API_VERSION + "/products";
    }

    /**
     * TikTok Open API signature: HMAC-SHA256 with key = appSecret over
     * {@code appSecret + path + (each sorted key+value, excluding sign &
     * access_token) + body + appSecret}, hex-encoded.
     */
    public static String sign(String appSecret, String path, TreeMap<String, String> params, String body) {
        StringBuilder base = new StringBuilder(appSecret).append(path);
        for (Map.Entry<String, String> e : params.entrySet()) {
            if ("sign".equals(e.getKey()) || "access_token".equals(e.getKey())) {
                continue;
            }
            base.append(e.getKey()).append(e.getValue());
        }
        if (body != null && !body.isBlank()) {
            base.append(body);
        }
        base.append(appSecret);
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(appSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] raw = mac.doFinal(base.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(raw.length * 2);
            for (byte b : raw) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException("TikTok signature computation failed", e);
        }
    }

    /**
     * Build the fully-signed request URL: common params ({@code app_key},
     * {@code timestamp}, optional {@code shop_cipher}/{@code shop_id}) plus the
     * HMAC-SHA256 {@code sign}.
     */
    public static String buildSignedUrl(String appKey, String appSecret, String path,
                                        String shopCipher, String shopId, String body) {
        TreeMap<String, String> params = new TreeMap<>();
        params.put("app_key", appKey);
        params.put("timestamp", String.valueOf(Instant.now().getEpochSecond()));
        if (shopCipher != null) {
            params.put("shop_cipher", shopCipher);
        }
        if (shopId != null) {
            params.put("shop_id", shopId);
        }
        String sign = sign(appSecret, path, params, body);
        return BASE + path + "?" + query(params) + "&sign=" + sign;
    }

    static String query(TreeMap<String, String> params) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : params.entrySet()) {
            if (sb.length() > 0) {
                sb.append('&');
            }
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.toString();
    }
}
