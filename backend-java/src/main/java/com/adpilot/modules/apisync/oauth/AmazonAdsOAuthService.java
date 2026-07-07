package com.adpilot.modules.apisync.oauth;

import com.adpilot.common.config.HttpClientFactory;
import com.adpilot.common.exception.BusinessException;
import com.adpilot.common.utils.CryptoUtil;
import com.adpilot.modules.apisync.entity.PlatformConnectionEntity;
import com.adpilot.modules.apisync.model.ConnectionStatus;
import com.adpilot.modules.apisync.mapper.PlatformConnectionMapper;
import com.adpilot.modules.apisync.oauth.dto.AmazonAdsAuthUrlVo;
import com.adpilot.modules.apisync.oauth.dto.AmazonAdsBindRequest;
import com.adpilot.modules.apisync.oauth.dto.AmazonAdsCallbackVo;
import com.adpilot.modules.apisync.oauth.dto.AmazonAdsProfileVo;
import com.adpilot.modules.audit.service.AuditLogService;
import com.adpilot.common.utils.SecurityUtils;
import com.adpilot.modules.rbac.PlatformFamily;
import com.adpilot.modules.rbac.dto.CreateStoreGroupCommand;
import com.adpilot.modules.rbac.service.StoreGroupService;
import com.adpilot.modules.rbac.vo.StoreGroupVo;
import com.adpilot.modules.store.entity.StoreEntity;
import com.adpilot.modules.store.mapper.StoreMapper;
import com.adpilot.modules.store.service.StoreService;
import com.adpilot.modules.store.vo.StoreVo;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Implements the Amazon Ads OAuth (Login with Amazon) store-connection flow:
 * builds the authorize URL, exchanges the authorization code for tokens, lists
 * advertising profiles, and binds a selected profile to a store.
 *
 * <h2>Graceful unconfigured behavior</h2>
 * <p>If client-id / client-secret / redirect-uri are not configured, every
 * entry point throws a {@link BusinessException} (HTTP 400) with a clear
 * message — never a 500/NPE.</p>
 *
 * <h2>Security</h2>
 * <p>The client secret and refresh token are secrets: read from config /
 * encrypted at rest via {@link CryptoUtil}, transmitted only to Amazon's
 * official endpoints, and never logged or returned to clients.</p>
 */
@Slf4j
@Service
public class AmazonAdsOAuthService {

    static final String PLATFORM = "amazon_ads";
    private static final String CREDENTIALS_NOT_CONFIGURED =
            "Amazon Ads 凭证未配置，请在配置中填写 client-id/secret/redirect-uri";

    private final AmazonAdsProperties props;
    private final OAuthStateStore stateStore;
    private final PlatformConnectionMapper connectionMapper;
    private final CryptoUtil cryptoUtil;
    private final ObjectMapper objectMapper;
    private final StoreService storeService;
    private final StoreGroupService storeGroupService;
    private final StoreMapper storeMapper;
    private final AuditLogService auditLogService;
    private final SecureRandom secureRandom = new SecureRandom();
    private final RestClient http;

    public AmazonAdsOAuthService(AmazonAdsProperties props,
                                 OAuthStateStore stateStore,
                                 PlatformConnectionMapper connectionMapper,
                                 CryptoUtil cryptoUtil,
                                 ObjectMapper objectMapper,
                                 StoreService storeService,
                                 StoreGroupService storeGroupService,
                                 StoreMapper storeMapper,
                                 AuditLogService auditLogService,
                                 HttpClientFactory httpClientFactory) {
        this.props = props;
        this.stateStore = stateStore;
        this.connectionMapper = connectionMapper;
        this.cryptoUtil = cryptoUtil;
        this.objectMapper = objectMapper;
        this.storeService = storeService;
        this.storeGroupService = storeGroupService;
        this.storeMapper = storeMapper;
        this.auditLogService = auditLogService;
        this.http = httpClientFactory.timeoutRestClientBuilder().build();
    }

    // ── Step 2a: build the authorize URL ──────────────────────────────────────

    public AmazonAdsAuthUrlVo buildAuthorizeUrl(String rawRegion, String storeId) {
        requireConfigured();
        String region = AmazonAdsProperties.normalizeRegion(rawRegion);
        AmazonAdsProperties.Region cfg = props.region(region);
        UUID storeUuid = parseStoreId(storeId);
        validateAmazonStore(storeUuid);
        String effectiveStoreId = storeUuid.toString();
        String scope = props.getScope();

        String state = newState();
        stateStore.save(state, effectiveStoreId, region, props.getStateTtlSeconds());

        String url = cfg.getAuthorizeHost() + "/ap/oa"
                + "?client_id=" + enc(props.getClientId())
                + "&scope=" + enc(scope)
                + "&response_type=code"
                + "&redirect_uri=" + enc(props.getRedirectUri())
                + "&state=" + enc(state);

        log.info("Built Amazon Ads authorize URL for region={} store={} scope={}", region, effectiveStoreId, scope);
        return AmazonAdsAuthUrlVo.builder()
                .authorizeUrl(url)
                .state(state)
                .region(region)
                .storeId(effectiveStoreId)
                .build();
    }

    // ── Step 2b: handle the redirect callback ─────────────────────────────────

    @Transactional
    public AmazonAdsCallbackVo handleCallback(String code, String state) {
        requireConfigured();
        if (code == null || code.isBlank()) {
            throw new BusinessException("INVALID_CALLBACK", "缺少授权码 code");
        }
        OAuthStateStore.StateData data = stateStore.consume(state);
        if (data == null) {
            throw new BusinessException("INVALID_STATE", "授权状态已失效或无效，请重新发起授权");
        }
        String region = AmazonAdsProperties.normalizeRegion(data.region());
        validateAmazonStore(parseStoreId(data.storeId()));
        AmazonAdsProperties.Region cfg = props.region(region);

        TokenResponse tokens = exchangeCodeForTokens(cfg.getTokenUrl(), code);
        if (tokens.refreshToken() == null || tokens.refreshToken().isBlank()) {
            throw new BusinessException("TOKEN_EXCHANGE_FAILED",
                    "Amazon 未返回 refresh_token，请重试授权");
        }

        // Persist the refresh token encrypted against the originating store so a
        // subsequent bind can reuse it (status stays "configured" until bound).
        persistPendingRefreshToken(data.storeId(), region, tokens.refreshToken());

        List<AmazonAdsProfileVo> profiles = fetchProfiles(cfg.getApiHost(), tokens.accessToken());
        log.info("Amazon Ads callback OK: region={} store={} profiles={}",
                region, data.storeId(), profiles.size());

        return AmazonAdsCallbackVo.builder()
                .storeId(data.storeId())
                .region(region)
                .profiles(profiles)
                .build();
    }

    // ── Step 3: bind a selected profile to a store ────────────────────────────

    @Transactional
    public PlatformConnectionEntity bindProfile(AmazonAdsBindRequest req) {
        requireConfigured();
        if (req == null || req.getStoreId() == null || req.getStoreId().isBlank()) {
            throw new BusinessException("INVALID_REQUEST", "缺少 storeId");
        }
        if (req.getProfileId() == null || req.getProfileId().isBlank()) {
            throw new BusinessException("INVALID_REQUEST", "缺少 profileId");
        }
        String region = AmazonAdsProperties.normalizeRegion(req.getRegion());
        UUID storeUuid = parseStoreId(req.getStoreId());
        validateAmazonStore(storeUuid);

        PlatformConnectionEntity entity = findByStoreAndPlatform(storeUuid);
        String refreshToken = resolveRefreshToken(entity);
        if (refreshToken == null || refreshToken.isBlank()) {
            throw new BusinessException("NO_REFRESH_TOKEN",
                    "未找到刷新令牌，请重新完成亚马逊广告授权后再绑定");
        }

        // Config JSON consumed by the existing AmazonAdsConnector sync pipeline.
        Map<String, String> config = new LinkedHashMap<>();
        config.put("clientId", props.getClientId());
        config.put("clientSecret", props.getClientSecret());
        config.put("refreshToken", refreshToken);
        config.put("profileId", req.getProfileId());
        config.put("region", region);
        String configJson = writeConfig(config);

        String connectionName = (req.getAccountName() != null && !req.getAccountName().isBlank())
                ? req.getAccountName()
                : "Amazon Ads " + region.toUpperCase() + " · " + req.getProfileId();

        if (entity == null) {
            entity = PlatformConnectionEntity.builder()
                    .id(UUID.randomUUID())
                    .storeId(storeUuid)
                    .platform(PLATFORM)
                    .connectionName(connectionName)
                    .region(region)
                    .profileId(req.getProfileId())
                    .marketplaceId(req.getMarketplaceId())
                    .sellerAccountId(req.getSellerId())
                    .refreshTokenEncrypted(cryptoUtil.encrypt(refreshToken))
                    .configEncrypted(configJson)
                    .status(ConnectionStatus.CONNECTED)
                    .lastSyncAt(LocalDateTime.now())
                    .build();
            connectionMapper.insert(entity);
        } else {
            entity.setConnectionName(connectionName);
            entity.setRegion(region);
            entity.setProfileId(req.getProfileId());
            entity.setMarketplaceId(req.getMarketplaceId());
            entity.setSellerAccountId(req.getSellerId());
            entity.setRefreshTokenEncrypted(cryptoUtil.encrypt(refreshToken));
            entity.setConfigEncrypted(configJson);
            entity.setStatus(ConnectionStatus.CONNECTED);
            entity.setLastSyncAt(LocalDateTime.now());
            entity.setUpdatedAt(LocalDateTime.now());
            connectionMapper.updateById(entity);
        }
        log.info("Bound Amazon Ads profile to store {} (region={}, profileId={})",
                req.getStoreId(), region, req.getProfileId());

        // Assign the bound store to a selected/created Amazon Store_Group within
        // the same flow (Req 4.5). Failures here surface as BusinessException and
        // roll back the surrounding transaction so a half-wired store is never left.
        assignToAmazonStoreGroup(storeUuid, req);
        auditBind(entity, region);
        return entity;
    }

    /**
     * Write a forensic AUDIT-TRAIL entry for an Amazon Ads OAuth bind. Auditing is
     * additive and best-effort: any failure here is logged and swallowed so it can
     * never break the bind or alter its transaction/return value. Details carry
     * only non-secret metadata (region, profileId, storeId) — never the refresh
     * token, access token, client secret, or any decrypted credential.
     */
    private void auditBind(PlatformConnectionEntity entity, String region) {
        try {
            Map<String, Object> details = new LinkedHashMap<>();
            if (region != null) details.put("region", region);
            if (entity != null && entity.getProfileId() != null) details.put("profileId", entity.getProfileId());
            if (entity != null && entity.getStoreId() != null) details.put("storeId", entity.getStoreId().toString());
            UUID actorId = parseUuidOrNull(SecurityUtils.getCurrentUserIdOrNull());
            UUID orgId = parseUuidOrNull(safeCurrentOrgId());
            UUID entityId = entity != null ? entity.getId() : null;
            auditLogService.createLog(actorId, orgId, "AMAZON_ADS_OAUTH_BIND", "platform_connection", entityId, details);
        } catch (Exception ex) {
            log.warn("Failed to write audit log action=AMAZON_ADS_OAUTH_BIND connectionId={}: {}",
                    entity != null ? entity.getId() : null, ex.getMessage());
        }
    }

    private static String safeCurrentOrgId() {
        try {
            return SecurityUtils.getCurrentOrgId();
        } catch (Exception e) {
            return null;
        }
    }

    private static UUID parseUuidOrNull(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return UUID.fromString(value.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Resolve the target Amazon Store_Group for the just-bound store and assign
     * it, ensuring the store is on the {@code amazon} platform family so the
     * Store_Group platform-family match holds (Req 4.5, 10.6).
     *
     * <p>Resolution order: an explicitly selected {@code storeGroupId}; otherwise
     * a group named {@code storeGroupName} (reused if it already exists, created
     * if not); otherwise the per-family default Amazon group (Req 10.7).</p>
     */
    private void assignToAmazonStoreGroup(UUID storeUuid, AmazonAdsBindRequest req) {
        ensureAmazonPlatformFamily(storeUuid);
        UUID groupId = resolveAmazonStoreGroup(req);
        storeGroupService.assignStore(storeUuid, groupId);
        log.info("Assigned store {} to Amazon store group {}", storeUuid, groupId);
    }

    /**
     * Ensure the store carries the {@code amazon} platform family so the
     * subsequent Store_Group assignment passes the platform-family match. The
     * caller has already validated the store is an Amazon store.
     */
    private void ensureAmazonPlatformFamily(UUID storeUuid) {
        StoreEntity store = storeMapper.selectById(storeUuid);
        if (store == null) {
            throw new BusinessException("STORE_NOT_FOUND", "未找到要绑定的店铺");
        }
        if (!PlatformFamily.AMAZON.getCode().equalsIgnoreCase(store.getPlatformFamily())) {
            store.setPlatformFamily(PlatformFamily.AMAZON.getCode());
            store.setUpdatedAt(LocalDateTime.now());
            storeMapper.updateById(store);
        }
    }

    /**
     * Resolve the Amazon Store_Group id from the bind request: a selected group,
     * a named group (reused or created), or the family default.
     */
    private UUID resolveAmazonStoreGroup(AmazonAdsBindRequest req) {
        if (req.getStoreGroupId() != null && !req.getStoreGroupId().isBlank()) {
            try {
                return UUID.fromString(req.getStoreGroupId().trim());
            } catch (IllegalArgumentException e) {
                throw new BusinessException("INVALID_REQUEST", "storeGroupId 格式无效");
            }
        }
        if (req.getStoreGroupName() != null && !req.getStoreGroupName().isBlank()) {
            String name = req.getStoreGroupName().trim();
            UUID existing = findAmazonGroupByName(name);
            if (existing != null) {
                return existing;
            }
            CreateStoreGroupCommand cmd = new CreateStoreGroupCommand();
            cmd.setName(name);
            cmd.setPlatformFamily(PlatformFamily.AMAZON);
            StoreGroupVo created = storeGroupService.create(cmd);
            return UUID.fromString(created.getId());
        }
        return storeGroupService.defaultGroupFor(PlatformFamily.AMAZON);
    }

    /** Find an existing Amazon Store_Group by name, returning null when absent. */
    private UUID findAmazonGroupByName(String name) {
        return storeGroupService.listByFamily(PlatformFamily.AMAZON).stream()
                .filter(g -> name.equalsIgnoreCase(g.getName()))
                .map(g -> UUID.fromString(g.getId()))
                .findFirst()
                .orElse(null);
    }

    // ── HTTP: token exchange ──────────────────────────────────────────────────

    private TokenResponse exchangeCodeForTokens(String tokenUrl, String code) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "authorization_code");
        form.add("code", code);
        form.add("redirect_uri", props.getRedirectUri());
        form.add("client_id", props.getClientId());
        form.add("client_secret", props.getClientSecret());

        String body;
        try {
            body = http.post().uri(tokenUrl)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(String.class);
        } catch (RestClientResponseException e) {
            // Never echo the response body (may carry sensitive context); use the
            // OAuth error code only.
            String errorCode = extractField(e.getResponseBodyAsString(), "error");
            throw new BusinessException("TOKEN_EXCHANGE_FAILED",
                    "授权码换取令牌失败（" + describe(e.getStatusCode().value(), errorCode) + "）");
        } catch (Exception e) {
            throw new BusinessException("TOKEN_EXCHANGE_FAILED",
                    "连接 Amazon 令牌服务失败，请稍后重试");
        }

        JsonNode node = readTree(body);
        return new TokenResponse(text(node, "access_token"), text(node, "refresh_token"));
    }

    // ── HTTP: list advertising profiles ───────────────────────────────────────

    private List<AmazonAdsProfileVo> fetchProfiles(String apiHost, String accessToken) {
        if (accessToken == null || accessToken.isBlank()) {
            throw new BusinessException("PROFILE_FETCH_FAILED", "未获取到访问令牌，无法读取广告账户");
        }
        String body;
        try {
            body = http.get().uri(apiHost + "/v2/profiles")
                    .header("Authorization", "Bearer " + accessToken)
                    .header("Amazon-Advertising-API-ClientId", props.getClientId())
                    .header("Accept", "application/json")
                    .retrieve()
                    .body(String.class);
        } catch (RestClientResponseException e) {
            throw new BusinessException("PROFILE_FETCH_FAILED",
                    "读取 Amazon 广告账户失败（HTTP " + e.getStatusCode().value() + "）");
        } catch (Exception e) {
            throw new BusinessException("PROFILE_FETCH_FAILED",
                    "连接 Amazon 广告 API 失败，请稍后重试");
        }

        JsonNode root = readTree(body);
        List<AmazonAdsProfileVo> profiles = new ArrayList<>();
        if (root != null && root.isArray()) {
            for (JsonNode p : root) {
                JsonNode accountInfo = p.get("accountInfo");
                profiles.add(AmazonAdsProfileVo.builder()
                        .profileId(asString(p.get("profileId")))
                        .countryCode(text(p, "countryCode"))
                        .currencyCode(text(p, "currencyCode"))
                        .marketplaceId(accountInfo != null ? text(accountInfo, "marketplaceStringId") : null)
                        .accountName(accountInfo != null ? text(accountInfo, "name") : null)
                        .sellerStringId(accountInfo != null ? text(accountInfo, "id") : null)
                        .accountType(accountInfo != null ? text(accountInfo, "type") : null)
                        .build());
            }
        }
        return profiles;
    }

    // ── persistence helpers ───────────────────────────────────────────────────

    private void persistPendingRefreshToken(String storeId, String region, String refreshToken) {
        UUID storeUuid = parseStoreId(storeId);
        PlatformConnectionEntity entity = findByStoreAndPlatform(storeUuid);
        String encrypted = cryptoUtil.encrypt(refreshToken);
        if (entity == null) {
            entity = PlatformConnectionEntity.builder()
                    .id(UUID.randomUUID())
                    .storeId(storeUuid)
                    .platform(PLATFORM)
                    .connectionName("Amazon Ads " + region.toUpperCase())
                    .region(region)
                    .refreshTokenEncrypted(encrypted)
                    .status(ConnectionStatus.CONFIGURED)
                    .build();
            connectionMapper.insert(entity);
        } else {
            entity.setRegion(region);
            entity.setRefreshTokenEncrypted(encrypted);
            if (!ConnectionStatus.CONNECTED.equals(entity.getStatus())) {
                entity.setStatus(ConnectionStatus.CONFIGURED);
            }
            entity.setUpdatedAt(LocalDateTime.now());
            connectionMapper.updateById(entity);
        }
    }

    private PlatformConnectionEntity findByStoreAndPlatform(UUID storeUuid) {
        LambdaQueryWrapper<PlatformConnectionEntity> w = new LambdaQueryWrapper<>();
        w.eq(PlatformConnectionEntity::getStoreId, storeUuid)
                .eq(PlatformConnectionEntity::getPlatform, PLATFORM)
                .last("LIMIT 1");
        return connectionMapper.selectOne(w);
    }

    /** Resolve the refresh token for a bind, decrypting from the canonical column. */
    private String resolveRefreshToken(PlatformConnectionEntity target) {
        if (target != null && target.getRefreshTokenEncrypted() != null
                && !target.getRefreshTokenEncrypted().isBlank()) {
            return cryptoUtil.decrypt(target.getRefreshTokenEncrypted());
        }
        return null;
    }

    /**
     * Serialize a credential map as a JSON object of individually-encrypted
     * values, matching the format read by {@code ApiSyncServiceImpl.readConfig}.
     */
    private String writeConfig(Map<String, String> config) {
        try {
            Map<String, String> encrypted = new LinkedHashMap<>();
            config.forEach((k, v) -> encrypted.put(k, (v == null || v.isEmpty()) ? v : cryptoUtil.encrypt(v)));
            return objectMapper.writeValueAsString(encrypted);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize Amazon Ads config", e);
        }
    }

    // ── misc helpers ──────────────────────────────────────────────────────────

    private void requireConfigured() {
        if (!props.isConfigured()) {
            throw new BusinessException("AMAZON_ADS_NOT_CONFIGURED", CREDENTIALS_NOT_CONFIGURED);
        }
    }

    private String newState() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static UUID parseStoreId(String storeId) {
        if (storeId == null || storeId.isBlank()) {
            throw new BusinessException("INVALID_REQUEST", "缺少 storeId");
        }
        try {
            return UUID.fromString(storeId);
        } catch (IllegalArgumentException e) {
            throw new BusinessException("INVALID_REQUEST", "storeId 格式无效");
        }
    }

    private void validateAmazonStore(UUID storeId) {
        StoreVo store = storeService.getStoreById(storeId.toString());
        if (!"amazon".equalsIgnoreCase(store.getPlatform())) {
            throw new BusinessException("INVALID_STORE_PLATFORM", "Amazon Ads 只能绑定到 Amazon 店铺");
        }
    }

    private static String enc(String v) {
        return URLEncoder.encode(v == null ? "" : v, StandardCharsets.UTF_8);
    }

    private static String describe(int status, String errorCode) {
        return (errorCode != null && !errorCode.isBlank()) ? errorCode : "HTTP " + status;
    }

    private JsonNode readTree(String body) {
        if (body == null || body.isBlank()) {
            return objectMapper.nullNode();
        }
        try {
            return objectMapper.readTree(body);
        } catch (Exception e) {
            return objectMapper.nullNode();
        }
    }

    private String extractField(String body, String field) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            return text(objectMapper.readTree(body), field);
        } catch (Exception e) {
            return null;
        }
    }

    private static String text(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode v = node.get(field);
        return asString(v);
    }

    private static String asString(JsonNode v) {
        if (v == null || v.isNull()) {
            return null;
        }
        String s = v.asText(null);
        return (s == null || s.isBlank()) ? null : s;
    }

    private record TokenResponse(String accessToken, String refreshToken) {}
}
