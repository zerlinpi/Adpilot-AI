package com.adpilot.modules.apisync.connector;

import com.adpilot.common.config.HttpClientFactory;
import com.adpilot.modules.apisync.model.ConnectionContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * Performs real connectivity / credential-validation calls against each
 * supported third-party platform, and declares the credential fields each
 * platform needs (used by the UI to render the config form and to mask secrets).
 *
 * <p>This is the shared credential-test capability reused by the per-platform
 * {@link PlatformDataConnector} implementations (WooCommerce/Shopify in P0,
 * Amazon in P2): the data connectors retrieve records, while this component
 * keeps validating credentials exactly as before. The
 * {@link #test(ConnectionContext)} overload lets callers that already hold a
 * decrypted {@link ConnectionContext} validate credentials without re-reading
 * the platform key and config separately.</p>
 *
 * <p>SECURITY: credentials are decrypted on demand via {@code CryptoUtil} by
 * the caller that builds the {@link ConnectionContext}; here they are
 * transmitted only to the official platform endpoints to validate them and are
 * never logged. {@link ConnectionContext#toString()} redacts credential
 * values, and the logging in this class records only platform keys and
 * outcome messages, never secret values.</p>
 */
@Slf4j
@Component
public class PlatformConnector {

    private final RestClient http;

    public PlatformConnector(HttpClientFactory httpClientFactory) {
        // Bounded connect/read timeouts (via the shared factory) so a slow platform
        // endpoint never pins the calling thread indefinitely.
        this.http = httpClientFactory.timeoutRestClientBuilder()
                // Decode String response bodies as UTF-8 even when the platform omits
                // a charset on its Content-Type (the default converter would fall back
                // to ISO-8859-1 and garble non-ASCII content).
                .messageConverters(converters -> {
                    converters.removeIf(c -> c instanceof StringHttpMessageConverter);
                    converters.add(0, new StringHttpMessageConverter(StandardCharsets.UTF_8));
                })
                .build();
    }

    /** A credential field definition for a platform's config form. */
    public record FieldSpec(String key, String label, boolean secret, boolean required, String placeholder) {}

    /** Result of a connectivity test. */
    public record TestResult(boolean ok, String message) {
        public static TestResult ok(String m) { return new TestResult(true, m); }
        public static TestResult fail(String m) { return new TestResult(false, m); }
    }

    public static final List<String> SUPPORTED = List.of(
            "amazon_ads", "amazon_sp_api", "google_ads", "shopify", "tiktok_shop", "woocommerce");

    /** Field schema per platform, consumed by the frontend config form. */
    public List<FieldSpec> fields(String platform) {
        return switch (platform) {
            case "amazon_ads" -> List.of(
                    new FieldSpec("clientId", "Client ID", false, true, "amzn1.application-oa2-client...."),
                    new FieldSpec("clientSecret", "Client Secret", true, true, ""),
                    new FieldSpec("refreshToken", "Refresh Token", true, true, "Atzr|..."),
                    new FieldSpec("profileId", "Profile ID", false, false, "广告 Profile ID"),
                    new FieldSpec("region", "区域", false, false, "NA / EU / FE"));
            case "amazon_sp_api" -> List.of(
                    new FieldSpec("clientId", "LWA Client ID", false, true, "amzn1.application-oa2-client...."),
                    new FieldSpec("clientSecret", "LWA Client Secret", true, true, ""),
                    new FieldSpec("refreshToken", "Refresh Token", true, true, "Atzr|..."),
                    new FieldSpec("region", "区域", false, false, "na / eu / fe"));
            case "google_ads" -> List.of(
                    new FieldSpec("clientId", "OAuth Client ID", false, true, "xxxx.apps.googleusercontent.com"),
                    new FieldSpec("clientSecret", "OAuth Client Secret", true, true, ""),
                    new FieldSpec("refreshToken", "Refresh Token", true, true, "1//..."),
                    new FieldSpec("developerToken", "Developer Token", true, true, ""),
                    new FieldSpec("customerId", "Customer ID", false, false, "1234567890"));
            case "shopify" -> List.of(
                    new FieldSpec("shopDomain", "店铺域名", false, true, "your-shop.myshopify.com"),
                    new FieldSpec("accessToken", "Admin API Access Token", true, true, "shpat_..."));
            case "tiktok_shop" -> List.of(
                    new FieldSpec("appKey", "App Key", false, true, ""),
                    new FieldSpec("appSecret", "App Secret", true, true, ""),
                    new FieldSpec("accessToken", "Access Token", true, true, ""),
                    new FieldSpec("shopId", "Shop ID", false, false, ""));
            case "woocommerce" -> List.of(
                    new FieldSpec("siteUrl", "WordPress 站点地址", false, true, "https://yourstore.com"),
                    new FieldSpec("consumerKey", "Consumer Key", false, true, "ck_..."),
                    new FieldSpec("consumerSecret", "Consumer Secret", true, true, "cs_..."));
            default -> List.of();
        };
    }

    /** Whether all required fields for the platform are present in config. */
    public boolean isComplete(String platform, Map<String, String> config) {
        if (config == null) return false;
        for (FieldSpec f : fields(platform)) {
            if (f.required()) {
                String v = config.get(f.key());
                if (v == null || v.isBlank()) return false;
            }
        }
        return !fields(platform).isEmpty();
    }

    /**
     * Run a real connectivity/credential check for an already-resolved
     * {@link ConnectionContext} whose credentials were decrypted on demand via
     * {@code CryptoUtil}. Bridges the existing credential-test behavior to the
     * {@link PlatformDataConnector} SPI so per-platform connectors can validate
     * credentials before pulling data. Secrets are never logged.
     */
    public TestResult test(ConnectionContext ctx) {
        if (ctx == null) {
            return TestResult.fail("缺少连接上下文");
        }
        return test(ctx.platform(), ctx.credentials());
    }

    /** Run a real connectivity/credential check. */
    public TestResult test(String platform, Map<String, String> config) {
        if (config == null) config = Map.of();
        if (!isComplete(platform, config)) {
            return TestResult.fail("配置不完整：请填写所有必填凭证字段");
        }
        try {
            return switch (platform) {
                case "woocommerce" -> testWooCommerce(config);
                case "shopify" -> testShopify(config);
                case "amazon_ads", "amazon_sp_api" -> testAmazonLwa(config);
                case "google_ads" -> testGoogleAds(config);
                case "tiktok_shop" -> testTikTok(config);
                default -> TestResult.fail("不支持的平台：" + platform);
            };
        } catch (Exception e) {
            log.warn("Connectivity test failed for {}: {}", platform, e.getMessage());
            return TestResult.fail("连接失败：" + rootMessage(e));
        }
    }

    // ── WooCommerce: GET /wp-json/wc/v3/system_status with key/secret basic auth ──
    private TestResult testWooCommerce(Map<String, String> c) {
        String site = stripTrailingSlash(c.get("siteUrl"));
        String url = site + "/wp-json/wc/v3/products?per_page=1";
        String basic = Base64.getEncoder().encodeToString(
                (c.get("consumerKey") + ":" + c.get("consumerSecret")).getBytes(StandardCharsets.UTF_8));
        http.get().uri(url)
                .header("Authorization", "Basic " + basic)
                .retrieve()
                .body(String.class);
        return TestResult.ok("WooCommerce 连接成功");
    }

    // ── Shopify: GET /admin/api/2024-01/shop.json with X-Shopify-Access-Token ──
    private TestResult testShopify(Map<String, String> c) {
        String shop = stripTrailingSlash(c.get("shopDomain")).replaceFirst("^https?://", "");
        String url = "https://" + shop + "/admin/api/2024-01/shop.json";
        http.get().uri(url)
                .header("X-Shopify-Access-Token", c.get("accessToken"))
                .retrieve()
                .body(String.class);
        return TestResult.ok("Shopify 连接成功");
    }

    // ── Amazon (Ads & SP-API both use LWA): refresh the access token ──
    private TestResult testAmazonLwa(Map<String, String> c) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "refresh_token");
        form.add("refresh_token", c.get("refreshToken"));
        form.add("client_id", c.get("clientId"));
        form.add("client_secret", c.get("clientSecret"));
        String resp = http.post().uri("https://api.amazon.com/auth/o2/token")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(String.class);
        if (resp != null && resp.contains("access_token")) {
            return TestResult.ok("Amazon 凭证有效（LWA 令牌刷新成功）");
        }
        return TestResult.fail("Amazon 凭证校验失败：未返回 access_token");
    }

    // ── Google Ads: refresh OAuth access token. Ads API calls additionally use
    // developerToken/customerId, but a token refresh is the safest real credential
    // validation that does not read or mutate account data.
    private TestResult testGoogleAds(Map<String, String> c) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "refresh_token");
        form.add("refresh_token", c.get("refreshToken"));
        form.add("client_id", c.get("clientId"));
        form.add("client_secret", c.get("clientSecret"));
        String resp = http.post().uri("https://oauth2.googleapis.com/token")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(String.class);
        if (resp != null && resp.contains("access_token")) {
            return TestResult.ok("Google Ads 凭证有效（OAuth 令牌刷新成功）");
        }
        return TestResult.fail("Google Ads 凭证校验失败：未返回 access_token");
    }

    // ── TikTok Shop: full request signing is complex; validate token endpoint best-effort ──
    private TestResult testTikTok(Map<String, String> c) {
        // A real data call requires HMAC request signing per TikTok's spec. Do
        // not mark the connection as valid unless the platform actually accepts
        // the request; otherwise the UI would show a connected channel that the
        // sync runner cannot reliably use.
        try {
            http.get().uri("https://open-api.tiktokglobalshop.com/api/shop/get_authorized_shop?app_key="
                            + c.get("appKey"))
                    .header("x-tts-access-token", c.get("accessToken"))
                    .retrieve()
                    .body(String.class);
            return TestResult.ok("TikTok Shop 连接成功");
        } catch (Exception e) {
            return TestResult.fail("TikTok Shop 凭证校验失败：需要完整签名鉴权后才能标记为已连接");
        }
    }

    private static String stripTrailingSlash(String s) {
        if (s == null) return "";
        String t = s.trim();
        while (t.endsWith("/")) t = t.substring(0, t.length() - 1);
        return t;
    }

    private static String rootMessage(Throwable e) {
        Throwable cur = e;
        while (cur.getCause() != null && cur.getCause() != cur) cur = cur.getCause();
        String m = cur.getMessage();
        return m != null ? m : cur.getClass().getSimpleName();
    }
}
