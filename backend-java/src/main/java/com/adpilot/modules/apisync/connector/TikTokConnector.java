package com.adpilot.modules.apisync.connector;

import com.adpilot.common.config.HttpClientFactory;
import com.adpilot.modules.apisync.model.ConnectionContext;
import com.adpilot.modules.apisync.model.ExternalPage;
import com.adpilot.modules.apisync.model.ExternalRecord;
import com.adpilot.modules.apisync.model.PageCursor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * {@link PlatformDataConnector} for TikTok Shop, pulling orders and products via
 * the TikTok Shop Partner (Open) API v202309. Auto-registered as a Spring bean
 * so the sync runner can resolve it for {@code platform = "tiktok_shop"}.
 *
 * <p>Credentials (decrypted on the {@link ConnectionContext}): {@code appKey},
 * {@code appSecret}, {@code accessToken}, and optionally {@code shopId}. The
 * shop cipher required by shop-scoped endpoints is resolved at runtime from the
 * authorized-shops endpoint.</p>
 *
 * <p><strong>VERIFICATION NOTE:</strong> TikTok's request signing and exact
 * endpoint/field shapes are version-specific and cannot be validated offline.
 * The signing here follows TikTok's documented algorithm (HMAC-SHA256 over
 * {@code appSecret + path + sortedParams + body + appSecret}). Verify against
 * live credentials and adjust the API version / field names if TikTok changes
 * them. On any failure the connector throws, and the sync job records the error
 * gracefully (it never crashes the app).</p>
 */
@Slf4j
@Component
public class TikTokConnector extends AbstractRestDataConnector {

    static final String PLATFORM = "tiktok_shop";
    static final String BASE = "https://open-api.tiktokglobalshop.com";
    private static final int PAGE_SIZE = 50;

    public TikTokConnector(ObjectMapper objectMapper, HttpClientFactory httpClientFactory) {
        super(objectMapper, httpClientFactory);
    }

    @Override
    public String platform() {
        return PLATFORM;
    }

    @Override
    public ExternalPage pullOrders(ConnectionContext ctx, Instant since, PageCursor cursor) {
        String shopCipher = resolveShopCipher(ctx);
        String path = "/order/202309/orders/search";
        StringBuilder body = new StringBuilder("{\"page_size\":").append(PAGE_SIZE);
        if (since != null) {
            body.append(",\"update_time_ge\":").append(since.getEpochSecond());
        }
        body.append("}");
        String pageToken = cursor != null && !cursor.isStart() ? cursor.token() : null;

        JsonNode data = post(ctx, path, shopCipher, pageToken, body.toString());
        List<ExternalRecord> records = new ArrayList<>();
        JsonNode arr = data.get("orders");
        if (arr != null && arr.isArray()) {
            for (JsonNode n : arr) {
                records.add(toRecord(n, "order", "id", "update_time", "status"));
            }
        }
        return paged(records, data, "order");
    }

    @Override
    public ExternalPage pullProducts(ConnectionContext ctx, Instant since, PageCursor cursor) {
        String shopCipher = resolveShopCipher(ctx);
        String path = "/product/202309/products/search";
        String body = "{\"page_size\":" + PAGE_SIZE + "}";
        String pageToken = cursor != null && !cursor.isStart() ? cursor.token() : null;

        JsonNode data = post(ctx, path, shopCipher, pageToken, body);
        List<ExternalRecord> records = new ArrayList<>();
        JsonNode arr = data.get("products");
        if (arr != null && arr.isArray()) {
            for (JsonNode n : arr) {
                records.add(toRecord(n, "product", "id", "update_time", "status"));
            }
        }
        return paged(records, data, "product");
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private ExternalPage paged(List<ExternalRecord> records, JsonNode data, String entityType) {
        String next = text(data, "next_page_token");
        boolean hasMore = next != null && !next.isBlank();
        log.info("TikTok pull {} -> {} record(s), hasMore={}", entityType, records.size(), hasMore);
        return hasMore ? new ExternalPage(records, PageCursor.of(next), true) : ExternalPage.last(records);
    }

    private ExternalRecord toRecord(JsonNode node, String entityType, String idField,
                                    String tsField, String statusField) {
        String externalId = text(node, idField);
        Instant changedAt = tsFromEpoch(text(node, tsField));
        Map<String, Object> fields = toFieldMap(node);
        return new ExternalRecord(externalId, entityType, changedAt, text(node, statusField), fields);
    }

    private Instant tsFromEpoch(String v) {
        if (v == null || v.isBlank()) return null;
        try {
            return Instant.ofEpochSecond(Long.parseLong(v.trim()));
        } catch (NumberFormatException e) {
            return parseInstant(v);
        }
    }

    /** Resolve the shop cipher needed by shop-scoped endpoints. */
    private String resolveShopCipher(ConnectionContext ctx) {
        String path = "/authorization/202309/shops";
        TreeMap<String, String> params = baseParams(ctx);
        String sign = sign(ctx.credential("appSecret"), path, params, "");
        String url = BASE + path + "?" + query(params) + "&sign=" + sign;
        try {
            String resp = http.get().uri(url)
                    .header("x-tts-access-token", ctx.credential("accessToken"))
                    .retrieve()
                    .body(String.class);
            JsonNode data = readTree(resp).path("data");
            JsonNode shops = data.get("shops");
            if (shops != null && shops.isArray() && shops.size() > 0) {
                return text(shops.get(0), "cipher");
            }
        } catch (Exception e) {
            throw new IllegalStateException("TikTok: failed to resolve shop cipher: " + e.getMessage(), e);
        }
        throw new IllegalStateException("TikTok: no authorized shop / cipher found");
    }

    private JsonNode post(ConnectionContext ctx, String path, String shopCipher,
                          String pageToken, String body) {
        TreeMap<String, String> params = baseParams(ctx);
        if (shopCipher != null) params.put("shop_cipher", shopCipher);
        if (pageToken != null) params.put("page_token", pageToken);
        String sign = sign(ctx.credential("appSecret"), path, params, body);
        String url = BASE + path + "?" + query(params) + "&sign=" + sign;
        try {
            String resp = http.post().uri(url)
                    .header("x-tts-access-token", ctx.credential("accessToken"))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(String.class);
            return readTree(resp).path("data");
        } catch (Exception e) {
            throw new IllegalStateException("TikTok API call failed (" + path + "): " + e.getMessage(), e);
        }
    }

    private TreeMap<String, String> baseParams(ConnectionContext ctx) {
        TreeMap<String, String> params = new TreeMap<>();
        params.put("app_key", ctx.credential("appKey"));
        params.put("timestamp", String.valueOf(Instant.now().getEpochSecond()));
        return params;
    }

    /**
     * TikTok Open API signature: HMAC-SHA256 with key = appSecret over
     * {@code appSecret + path + (each sorted key+value, excluding sign &
     * access_token) + body + appSecret}, hex-encoded.
     */
    private String sign(String appSecret, String path, TreeMap<String, String> params, String body) {
        StringBuilder base = new StringBuilder(appSecret).append(path);
        for (Map.Entry<String, String> e : params.entrySet()) {
            if ("sign".equals(e.getKey()) || "access_token".equals(e.getKey())) continue;
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

    private String query(TreeMap<String, String> params) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : params.entrySet()) {
            if (sb.length() > 0) sb.append('&');
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.toString();
    }
}
