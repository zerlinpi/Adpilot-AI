package com.adpilot.modules.apisync.service.impl;

import com.adpilot.common.api.PageResponse;
import com.adpilot.common.exception.BusinessException;
import com.adpilot.common.utils.CryptoUtil;
import com.adpilot.common.utils.SecurityUtils;
import com.adpilot.modules.apisync.connector.PlatformConnector;
import com.adpilot.modules.apisync.dto.ConnectStoreRequest;
import com.adpilot.modules.apisync.dto.PlatformConnectionDto;
import com.adpilot.modules.apisync.dto.StoreSyncRequest;
import com.adpilot.modules.apisync.entity.ApiSyncJobEntity;
import com.adpilot.modules.apisync.entity.ApiSyncLogEntity;
import com.adpilot.modules.apisync.entity.PlatformConnectionEntity;
import com.adpilot.modules.apisync.model.ConnectionStatus;
import com.adpilot.modules.apisync.mapper.ApiSyncJobMapper;
import com.adpilot.modules.apisync.mapper.ApiSyncLogMapper;
import com.adpilot.modules.apisync.mapper.PlatformConnectionMapper;
import com.adpilot.modules.apisync.service.ApiSyncService;
import com.adpilot.modules.apisync.service.SyncJobRunner;
import com.adpilot.modules.audit.service.AuditLogService;
import com.adpilot.modules.apisync.vo.ApiSyncJobVo;
import com.adpilot.modules.apisync.vo.ApiSyncLogVo;
import com.adpilot.modules.apisync.vo.PlatformConnectionVo;
import com.adpilot.modules.rbac.PlatformFamily;
import com.adpilot.modules.store.entity.MarketplaceEntity;
import com.adpilot.modules.store.entity.StoreEntity;
import com.adpilot.modules.store.entity.UserStoreEntity;
import com.adpilot.modules.store.mapper.MarketplaceMapper;
import com.adpilot.modules.store.mapper.StoreMapper;
import com.adpilot.modules.store.mapper.UserStoreMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ApiSyncServiceImpl implements ApiSyncService {

    private final PlatformConnectionMapper platformConnectionMapper;
    private final ApiSyncJobMapper apiSyncJobMapper;
    private final ApiSyncLogMapper apiSyncLogMapper;
    private final CryptoUtil cryptoUtil;
    private final PlatformConnector platformConnector;
    private final ObjectMapper objectMapper;
    private final SyncJobRunner syncJobRunner;
    private final StoreMapper storeMapper;
    private final MarketplaceMapper marketplaceMapper;
    private final UserStoreMapper userStoreMapper;
    private final AuditLogService auditLogService;

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** Hard upper bound on a connectivity test, per Req 13.1/13.5. */
    private static final long TEST_TIMEOUT_SECONDS = 30L;

    @Override
    @Transactional
    public PlatformConnectionVo connectStore(ConnectStoreRequest request, String userId) {
        if (request == null || request.getPlatform() == null || request.getPlatform().isBlank()) {
            throw new BusinessException("INVALID_REQUEST", "platform is required");
        }
        String platform = request.getPlatform();
        if (!PlatformConnector.SUPPORTED.contains(platform)) {
            throw new BusinessException("UNSUPPORTED_PLATFORM", "不支持的平台：" + platform);
        }
        // Enforce that the connection's platform key belongs to the declared
        // Nav_Block platform family; reject any cross-family key (Req 6.2).
        assertPlatformInBlockFamily(platform, request.getBlockFamily());
        if ("google_ads".equals(platform)) {
            throw new BusinessException("AD_CONNECTION_REQUIRES_STORE",
                    "Google Ads 是独立站广告账号，请绑定到已有 Shopify 或 WooCommerce 店铺。");
        }

        // Clean credentials (drop blanks).
        Map<String, String> config = new LinkedHashMap<>();
        if (request.getConfig() != null) {
            request.getConfig().forEach((k, v) -> {
                if (v != null && !v.isBlank()) config.put(k, v.trim());
            });
        }

        // Resolve org from the logged-in user, falling back to the default org.
        UUID orgUuid = requireCurrentOrgId();

        // Create the store with a per-platform marketplace (satisfies the NOT NULL FK).
        String storeName = (request.getStoreName() != null && !request.getStoreName().isBlank())
                ? request.getStoreName().trim() : platformLabel(platform) + " 店铺";
        StoreEntity store = StoreEntity.builder()
                .id(UUID.randomUUID())
                .orgId(orgUuid)
                .name(storeName)
                .marketplaceId(getOrCreateMarketplace(platform))
                .status(ConnectionStatus.CONNECTED)
                .createdBy(parseUuidOrNull(userId))
                .build();
        storeMapper.insert(store);

        // Create the platform connection bound to the new store.
        boolean complete = !config.isEmpty() && platformConnector.isComplete(platform, config);
        String status = config.isEmpty() ? ConnectionStatus.DISCONNECTED : (complete ? ConnectionStatus.CONFIGURED : ConnectionStatus.CONFIG_ERROR);
        PlatformConnectionEntity conn = PlatformConnectionEntity.builder()
                .id(UUID.randomUUID())
                .storeId(store.getId())
                .platform(platform)
                .connectionName(storeName)
                .configEncrypted(config.isEmpty() ? null : writeConfig(config))
                .status(status)
                .createdBy(parseUuidOrNull(userId))
                .build();
        platformConnectionMapper.insert(conn);

        log.info("Connected new store {} on platform {} (status={})", store.getId(), platform, status);
        auditConnection("PLATFORM_STORE_CONNECT", conn);
        return toConnectionVo(conn);
    }

    private String safeCurrentOrgId() {
        try {
            return SecurityUtils.getCurrentOrgId();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Write a forensic AUDIT-TRAIL entry for a platform credential / integration
     * lifecycle event. Auditing is additive and best-effort: any failure here is
     * logged and swallowed so it can never break the business operation or alter
     * its transaction/return value. The details map carries only non-secret
     * metadata (platform, storeId, connectionId) — never credential values,
     * tokens, or decrypted config.
     */
    private void auditConnection(String action, PlatformConnectionEntity entity) {
        try {
            Map<String, Object> details = new LinkedHashMap<>();
            if (entity != null) {
                if (entity.getPlatform() != null) details.put("platform", entity.getPlatform());
                if (entity.getStoreId() != null) details.put("storeId", entity.getStoreId().toString());
                if (entity.getId() != null) details.put("connectionId", entity.getId().toString());
            }
            UUID actorId = parseUuidOrNull(SecurityUtils.getCurrentUserIdOrNull());
            UUID orgId = parseUuidOrNull(safeCurrentOrgId());
            UUID entityId = entity != null ? entity.getId() : null;
            auditLogService.createLog(actorId, orgId, action, "platform_connection", entityId, details);
        } catch (Exception ex) {
            log.warn("Failed to write audit log action={} connectionId={}: {}", action,
                    entity != null ? entity.getId() : null, ex.getMessage());
        }
    }

    /** Get-or-create a per-platform marketplace so non-Amazon stores satisfy the FK. */
    private UUID getOrCreateMarketplace(String platform) {
        String code = marketplaceCode(platform);
        LambdaQueryWrapper<MarketplaceEntity> w = new LambdaQueryWrapper<>();
        w.eq(MarketplaceEntity::getCode, code).last("LIMIT 1");
        MarketplaceEntity m = marketplaceMapper.selectOne(w);
        if (m != null) {
            return m.getId();
        }
        m = MarketplaceEntity.builder()
                .id(UUID.randomUUID())
                .code(code)
                .name(platformLabel(platform))
                .currency("USD")
                .vatApplicable(false)
                .build();
        marketplaceMapper.insert(m);
        return m.getId();
    }

    private static String marketplaceCode(String platform) {
        return switch (platform) {
            case "amazon_ads", "amazon_sp_api" -> "AMZ";
            case "google_ads" -> "GOOGLEADS";
            case "shopify" -> "SHOPIFY";
            case "woocommerce" -> "WOO";
            case "tiktok_shop" -> "TIKTOK";
            default -> platform.toUpperCase().substring(0, Math.min(10, platform.length()));
        };
    }

    private static String platformLabel(String platform) {
        return switch (platform) {
            case "amazon_ads" -> "Amazon Ads";
            case "amazon_sp_api" -> "Amazon SP-API";
            case "google_ads" -> "Google Ads";
            case "shopify" -> "Shopify";
            case "woocommerce" -> "WooCommerce";
            case "tiktok_shop" -> "TikTok Shop";
            default -> platform;
        };
    }

    /**
     * Enforce that the connection's platform key belongs to the declared
     * Nav_Block platform family (Req 6.2). The declared family is the connection
     * entry's {@code ?platform=} scope. A cross-family platform key (e.g. a
     * {@code tiktok_shop} key submitted under an {@code amazon} block) is rejected
     * with HTTP 403. When no scope is declared the {@code SUPPORTED} allow-list
     * still applies.
     */
    private void assertPlatformInBlockFamily(String platform, String blockFamily) {
        if (blockFamily == null || blockFamily.isBlank()) {
            return;
        }
        PlatformFamily declared;
        try {
            declared = PlatformFamily.fromCode(blockFamily);
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(400, "INVALID_PLATFORM_SCOPE",
                    "无效的平台族范围：" + blockFamily);
        }
        PlatformFamily actual;
        try {
            actual = PlatformFamily.ofPlatformKey(platform);
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(400, "UNSUPPORTED_PLATFORM", "不支持的平台：" + platform);
        }
        if (actual != declared) {
            throw new BusinessException(403, "CROSS_FAMILY_CONNECTION",
                    "禁止跨平台族连接：平台 " + platform + " 属于 " + actual.getCode()
                            + " 族，不能在 " + declared.getCode() + " 块内连接");
        }
    }

    @Override
    public PageResponse<PlatformConnectionVo> listPlatformConnections(int page, int pageSize) {
        Page<PlatformConnectionEntity> pageParam = new Page<>(page, pageSize);
        LambdaQueryWrapper<PlatformConnectionEntity> wrapper = new LambdaQueryWrapper<>();
        if (!scopeConnections(wrapper)) {
            return PageResponse.of(List.of(), 0, page, pageSize);
        }
        wrapper.orderByDesc(PlatformConnectionEntity::getCreatedAt);

        Page<PlatformConnectionEntity> result = platformConnectionMapper.selectPage(pageParam, wrapper);
        List<PlatformConnectionVo> voList = result.getRecords().stream()
                .map(this::toConnectionVo)
                .collect(Collectors.toList());

        return PageResponse.of(voList, result.getTotal(), page, pageSize);
    }

    @Override
    @Transactional
    public PlatformConnectionVo createPlatformConnection(PlatformConnectionDto dto, String userId) {
        if (dto == null || dto.getPlatform() == null || dto.getPlatform().isBlank()) {
            throw new BusinessException("INVALID_REQUEST", "platform is required");
        }
        if (!PlatformConnector.SUPPORTED.contains(dto.getPlatform())) {
            throw new BusinessException("UNSUPPORTED_PLATFORM", "不支持的平台：" + dto.getPlatform());
        }
        String encrypted = dto.getConfig() != null && !dto.getConfig().isEmpty()
                ? writeConfig(new LinkedHashMap<>(dto.getConfig()))
                : dto.getConfigEncrypted();
        Map<String, String> config = dto.getConfig() != null ? new LinkedHashMap<>(dto.getConfig()) : Map.of();
        boolean complete = !config.isEmpty() && platformConnector.isComplete(dto.getPlatform(), config);
        String status = config.isEmpty() ? ConnectionStatus.DISCONNECTED : (complete ? ConnectionStatus.CONFIGURED : ConnectionStatus.CONFIG_ERROR);
        if (dto.getStoreId() == null || dto.getStoreId().isBlank()) {
            throw new BusinessException("STORE_REQUIRED", "A platform connection must belong to a store");
        }
        UUID storeUuid = UUID.fromString(dto.getStoreId());
        assertStoreAccessible(storeUuid);
        PlatformConnectionEntity entity = PlatformConnectionEntity.builder()
                .storeId(storeUuid)
                .platform(dto.getPlatform())
                .connectionName(dto.getConnectionName())
                .configEncrypted(encrypted)
                .status(status)
                .createdBy(parseUuidOrNull(userId))
                .build();

        platformConnectionMapper.insert(entity);
        log.info("Platform connection created: id={}, platform={}", entity.getId(), entity.getPlatform());
        auditConnection("PLATFORM_CONNECTION_CREATE", entity);
        return toConnectionVo(entity);
    }

    @Override
    @Transactional
    public PlatformConnectionVo updatePlatformConnection(String id, PlatformConnectionDto dto, String userId) {
        PlatformConnectionEntity entity = platformConnectionMapper.selectById(UUID.fromString(id));
        if (entity == null) {
            throw new BusinessException("CONNECTION_NOT_FOUND", "Platform connection not found: " + id);
        }
        assertConnectionAccessible(entity);

        if (dto.getPlatform() != null) {
            if (!PlatformConnector.SUPPORTED.contains(dto.getPlatform())) {
                throw new BusinessException("UNSUPPORTED_PLATFORM", "不支持的平台：" + dto.getPlatform());
            }
            entity.setPlatform(dto.getPlatform());
        }
        if (dto.getConnectionName() != null) {
            entity.setConnectionName(dto.getConnectionName());
        }
        if (dto.getConfig() != null && !dto.getConfig().isEmpty()) {
            entity.setConfigEncrypted(writeConfig(new LinkedHashMap<>(dto.getConfig())));
        } else if (dto.getConfigEncrypted() != null) {
            entity.setConfigEncrypted(dto.getConfigEncrypted());
        }
        if (dto.getStoreId() != null) {
            UUID targetStoreId = UUID.fromString(dto.getStoreId());
            assertStoreAccessible(targetStoreId);
            entity.setStoreId(targetStoreId);
        }

        entity.setUpdatedAt(LocalDateTime.now());
        platformConnectionMapper.updateById(entity);
        log.info("Platform connection updated: id={}", id);
        auditConnection("PLATFORM_CONNECTION_UPDATE", entity);
        return toConnectionVo(entity);
    }

    @Override
    public PlatformConnector.TestResult testPlatformConnection(String id) {
        if (id == null || id.isBlank()) {
            throw new BusinessException("CONNECTION_NOT_FOUND", "Platform connection not found: " + id);
        }

        // Both entry points converge here (Req 13.1): a UUID resolves the
        // connection by id, anything else is treated as a platform key so the
        // key-based frontend call and an explicit connection id both run the
        // same real connector test.
        UUID connectionId = parseUuidOrNull(id);
        PlatformConnectionEntity entity = (connectionId != null)
                ? platformConnectionMapper.selectById(connectionId)
                : findByPlatform(id);

        // Unknown key/id -> not-found (Req 13.4; not-found path handled here).
        if (entity == null) {
            throw new BusinessException("CONNECTION_NOT_FOUND", "Platform connection not found: " + id);
        }

        assertConnectionAccessible(entity);

        // Decrypt the stored config and validate it against the real platform.
        // This is read-only: a test never mutates the stored credentials
        // (Req 13.3, 13.5 — credentials left unchanged on failure/timeout).
        Map<String, String> config = readConfig(entity.getConfigEncrypted());
        PlatformConnector.TestResult result = runTestWithinBound(entity.getPlatform(), config);
        log.info("Tested platform connection: id={}, platform={}, ok={}",
                entity.getId(), entity.getPlatform(), result.ok());
        return result;
    }

    /**
     * Run the connector test under a hard 30-second bound (Req 13.1, 13.5).
     * If the connector cannot be reached or does not return within the limit,
     * a failure result is returned indicating the platform is unreachable; the
     * stored credentials are never touched, so they remain unchanged.
     */
    private PlatformConnector.TestResult runTestWithinBound(String platform, Map<String, String> config) {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<PlatformConnector.TestResult> future =
                executor.submit(() -> platformConnector.test(platform, config));
        try {
            return future.get(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            log.warn("Platform connection test timed out for {} after {}s", platform, TEST_TIMEOUT_SECONDS);
            return PlatformConnector.TestResult.fail(
                    "连接失败：平台在 " + TEST_TIMEOUT_SECONDS + " 秒内无响应（平台不可达），请稍后重试");
        } catch (Exception e) {
            log.warn("Platform connection test failed for {}: {}", platform, e.getMessage());
            return PlatformConnector.TestResult.fail("连接失败：平台不可达");
        } finally {
            executor.shutdownNow();
        }
    }

    private PlatformConnectionEntity findByPlatform(String platform) {
        LambdaQueryWrapper<PlatformConnectionEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(PlatformConnectionEntity::getPlatform, platform);
        if (!scopeConnections(wrapper)) return null;
        wrapper.orderByDesc(PlatformConnectionEntity::getUpdatedAt).last("LIMIT 1");
        return platformConnectionMapper.selectOne(wrapper);
    }

    private PlatformConnectionEntity findByStoreAndPlatform(UUID storeId, String platform) {
        LambdaQueryWrapper<PlatformConnectionEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(PlatformConnectionEntity::getStoreId, storeId)
                .eq(PlatformConnectionEntity::getPlatform, platform)
                .orderByDesc(PlatformConnectionEntity::getUpdatedAt)
                .last("LIMIT 1");
        return platformConnectionMapper.selectOne(wrapper);
    }

    private List<UUID> accessibleStoreIds() {
        if (!SecurityUtils.isAuthenticated()) return null;
        UUID orgId = requireCurrentOrgId();
        LambdaQueryWrapper<StoreEntity> storeQuery = new LambdaQueryWrapper<>();
        storeQuery.eq(StoreEntity::getOrgId, orgId);
        List<StoreEntity> orgStores = storeMapper.selectList(storeQuery);
        if (SecurityUtils.isStoreAdmin()) {
            return orgStores.stream().map(StoreEntity::getId).collect(Collectors.toList());
        }

        UUID userId = parseUuidOrNull(SecurityUtils.getCurrentUserId());
        if (userId == null) return List.of();
        LambdaQueryWrapper<UserStoreEntity> assignmentQuery = new LambdaQueryWrapper<>();
        assignmentQuery.eq(UserStoreEntity::getUserId, userId);
        java.util.Set<UUID> assigned = userStoreMapper.selectList(assignmentQuery).stream()
                .map(UserStoreEntity::getStoreId)
                .collect(Collectors.toSet());
        return orgStores.stream()
                .filter(store -> assigned.contains(store.getId()) || userId.equals(store.getCreatedBy()))
                .map(StoreEntity::getId)
                .collect(Collectors.toList());
    }

    private boolean scopeConnections(LambdaQueryWrapper<PlatformConnectionEntity> wrapper) {
        List<UUID> storeIds = accessibleStoreIds();
        if (storeIds == null) return true;
        if (storeIds.isEmpty()) return false;
        wrapper.in(PlatformConnectionEntity::getStoreId, storeIds);
        return true;
    }

    private UUID requireCurrentOrgId() {
        UUID orgId = parseUuidOrNull(safeCurrentOrgId());
        if (orgId == null) {
            throw new BusinessException(403, "ORG_CONTEXT_REQUIRED", "Organization context is required");
        }
        return orgId;
    }

    private StoreEntity assertStoreAccessible(UUID storeId) {
        StoreEntity store = storeMapper.selectById(storeId);
        if (!SecurityUtils.isAuthenticated()) return store;
        List<UUID> allowed = accessibleStoreIds();
        if (store == null || allowed == null || !allowed.contains(storeId)) {
            throw new BusinessException(404, "STORE_NOT_FOUND", "Store not found: " + storeId);
        }
        return store;
    }

    private void assertConnectionAccessible(PlatformConnectionEntity entity) {
        if (entity == null) return;
        assertStoreAccessible(entity.getStoreId());
    }

    private PlatformConnectionEntity findByIdOrPlatform(String idOrPlatform) {
        UUID connectionId = parseUuidOrNull(idOrPlatform);
        if (connectionId == null) return findByPlatform(idOrPlatform);
        PlatformConnectionEntity entity = platformConnectionMapper.selectById(connectionId);
        if (entity != null) assertConnectionAccessible(entity);
        return entity;
    }

    @Override
    @Transactional
    public PlatformConnectionVo connectPlatform(String platform, String userId) {
        PlatformConnectionEntity entity = findByIdOrPlatform(platform);
        if (entity == null) {
            throw new BusinessException("CONNECTION_NOT_FOUND", "Platform connection not found: " + platform);
        }
        String resolvedPlatform = entity != null ? entity.getPlatform() : platform;
        Map<String, String> config = entity != null ? readConfig(entity.getConfigEncrypted()) : Map.of();

        // Validate credentials against the real platform before declaring "connected".
        PlatformConnector.TestResult result = platformConnector.test(resolvedPlatform, config);
        String newStatus = result.ok() ? ConnectionStatus.CONNECTED : ConnectionStatus.CONFIG_ERROR;

        entity.setStatus(newStatus);
        if (result.ok()) {
            entity.setLastSyncAt(LocalDateTime.now());
        }
        entity.setUpdatedAt(LocalDateTime.now());
        platformConnectionMapper.updateById(entity);
        log.info("Connect platform {}: status={}", resolvedPlatform, newStatus);
        auditConnection("PLATFORM_CONNECT", entity);
        PlatformConnectionVo vo = toConnectionVo(entity);
        vo.setMessage(result.message());
        return vo;
    }

    @Override
    @Transactional
    public PlatformConnectionVo disconnectPlatform(String platform) {
        PlatformConnectionEntity entity = findByIdOrPlatform(platform);
        if (entity == null) {
            throw new BusinessException("CONNECTION_NOT_FOUND", "Platform connection not found: " + platform);
        }
        entity.setStatus(ConnectionStatus.DISCONNECTED);
        entity.setUpdatedAt(LocalDateTime.now());
        platformConnectionMapper.updateById(entity);
        log.info("Disconnect platform {}", platform);
        auditConnection("PLATFORM_DISCONNECT", entity);
        return toConnectionVo(entity);
    }

    @Override
    public String testPlatformByKey(String platform) {
        PlatformConnectionEntity entity = findByPlatform(platform);
        if (entity == null) {
            return "未连接：尚未配置 " + platform;
        }
        Map<String, String> config = readConfig(entity.getConfigEncrypted());
        PlatformConnector.TestResult result = platformConnector.test(platform, config);
        return result.message();
    }

    @Override
    @Transactional
    public PlatformConnectionVo saveConfig(String platform, PlatformConnectionDto dto, String userId) {
        if (!PlatformConnector.SUPPORTED.contains(platform)) {
            throw new BusinessException("UNSUPPORTED_PLATFORM", "不支持的平台：" + platform);
        }
        if (dto == null || dto.getStoreId() == null || dto.getStoreId().isBlank()) {
            throw new BusinessException("STORE_REQUIRED", "A platform connection must belong to a store");
        }
        UUID storeUuid = UUID.fromString(dto.getStoreId());
        assertStoreAccessible(storeUuid);
        PlatformConnectionEntity entity = findByStoreAndPlatform(storeUuid, platform);

        Map<String, String> merged = new LinkedHashMap<>(
                entity != null ? readConfig(entity.getConfigEncrypted()) : Map.of());
        if (dto.getConfig() != null) {
            dto.getConfig().forEach((k, v) -> {
                if (v != null && !v.isBlank()) merged.put(k, v);
            });
        }
        String encrypted = writeConfig(merged);
        boolean complete = platformConnector.isComplete(platform, merged);
        String status = complete ? ConnectionStatus.CONFIGURED : ConnectionStatus.CONFIG_ERROR;

        if (entity == null) {
            entity = PlatformConnectionEntity.builder()
                    .id(UUID.randomUUID())
                    .storeId(storeUuid)
                    .platform(platform)
                    .connectionName(dto.getConnectionName() != null ? dto.getConnectionName() : platform)
                    .configEncrypted(encrypted)
                    .status(status)
                    .createdBy(parseUuidOrNull(userId))
                    .build();
            platformConnectionMapper.insert(entity);
        } else {
            if (dto.getConnectionName() != null) entity.setConnectionName(dto.getConnectionName());
            entity.setConfigEncrypted(encrypted);
            if (!ConnectionStatus.CONNECTED.equals(entity.getStatus()) || !complete) {
                entity.setStatus(status);
            }
            entity.setUpdatedAt(LocalDateTime.now());
            platformConnectionMapper.updateById(entity);
        }
        log.info("Saved config for platform {} on store {} (complete={})", platform, storeUuid, complete);
        auditConnection("PLATFORM_CONFIG_SAVE", entity);
        return toConnectionVo(entity);
    }

    @Override
    public PlatformConnectionVo getConfig(String platform) {
        PlatformConnectionEntity entity = findByPlatform(platform);
        if (entity == null) {
            return PlatformConnectionVo.builder()
                    .platform(platform)
                    .platformId(platform)
                    .status(ConnectionStatus.DISCONNECTED)
                    .hasConfig(false)
                    .configMasked(Map.of())
                    .build();
        }
        Map<String, String> config = readConfig(entity.getConfigEncrypted());
        PlatformConnectionVo vo = toConnectionVo(entity);
        vo.setHasConfig(!config.isEmpty());
        vo.setConfigMasked(maskConfig(platform, config));
        return vo;
    }

    // ── credential (de)serialization + masking helpers ──
    // The DB column `config` is of type JSON, so we store a valid JSON object whose
    // VALUES are individually AES-encrypted. This keeps the column valid JSON while
    // protecting every secret at rest.

    @Override
    public java.util.List<String> getConfiguredPlatforms() {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (String p : PlatformConnector.SUPPORTED) {
            PlatformConnectionEntity e = findByPlatform(p);
            if (e != null && !readConfig(e.getConfigEncrypted()).isEmpty()) {
                out.add(p);
            }
        }
        return out;
    }

    @Override
    @Transactional
    public PlatformConnectionVo bindStore(String storeId, String platform, String userId) {
        if (!PlatformConnector.SUPPORTED.contains(platform)) {
            throw new BusinessException("UNSUPPORTED_PLATFORM", "不支持的平台：" + platform);
        }
        // Reuse the admin-configured credentials for this platform.
        PlatformConnectionEntity source = findByPlatform(platform);
        Map<String, String> config = source != null ? readConfig(source.getConfigEncrypted()) : Map.of();
        if (config.isEmpty()) {
            throw new BusinessException("NO_CREDENTIALS",
                    "该平台尚未由管理员配置凭证，无法一键绑定。请先在「API 连接」中配置 " + platform + " 凭证。");
        }
        UUID storeUuid = UUID.fromString(storeId);
        assertStoreAccessible(storeUuid);

        // Find an existing connection for this exact store+platform, else create one.
        LambdaQueryWrapper<PlatformConnectionEntity> w = new LambdaQueryWrapper<>();
        w.eq(PlatformConnectionEntity::getStoreId, storeUuid)
                .eq(PlatformConnectionEntity::getPlatform, platform).last("LIMIT 1");
        PlatformConnectionEntity entity = platformConnectionMapper.selectOne(w);

        PlatformConnector.TestResult result = platformConnector.test(platform, config);
        String status = result.ok() ? ConnectionStatus.CONNECTED : ConnectionStatus.CONFIG_ERROR;

        if (entity == null) {
            entity = PlatformConnectionEntity.builder()
                    .id(UUID.randomUUID())
                    .storeId(storeUuid)
                    .platform(platform)
                    .connectionName(platform)
                    .configEncrypted(writeConfig(config))
                    .status(status)
                    .createdBy(parseUuidOrNull(userId))
                    .build();
            if (result.ok()) entity.setLastSyncAt(LocalDateTime.now());
            platformConnectionMapper.insert(entity);
        } else {
            entity.setConfigEncrypted(writeConfig(config));
            entity.setStatus(status);
            if (result.ok()) entity.setLastSyncAt(LocalDateTime.now());
            entity.setUpdatedAt(LocalDateTime.now());
            platformConnectionMapper.updateById(entity);
        }
        log.info("Bind store {} to {}: status={}", storeId, platform, status);
        auditConnection("PLATFORM_STORE_BIND", entity);
        PlatformConnectionVo vo = toConnectionVo(entity);
        vo.setMessage(result.message());
        return vo;
    }

    // ── private serialization helpers ──

    private Map<String, String> readConfig(String stored) {
        if (stored == null || stored.isBlank()) return new LinkedHashMap<>();
        try {
            Map<String, String> encrypted = objectMapper.readValue(
                    stored, new TypeReference<LinkedHashMap<String, String>>() {});
            Map<String, String> plain = new LinkedHashMap<>();
            encrypted.forEach((k, v) -> plain.put(k, v == null ? null : cryptoUtil.decrypt(v)));
            return plain;
        } catch (Exception e) {
            log.warn("Failed to read platform config: {}", e.getMessage());
            return new LinkedHashMap<>();
        }
    }

    private String writeConfig(Map<String, String> config) {
        try {
            Map<String, String> encrypted = new LinkedHashMap<>();
            config.forEach((k, v) -> encrypted.put(k, (v == null || v.isEmpty()) ? v : cryptoUtil.encrypt(v)));
            return objectMapper.writeValueAsString(encrypted);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize config", e);
        }
    }

    private Map<String, String> maskConfig(String platform, Map<String, String> config) {
        Map<String, String> masked = new LinkedHashMap<>();
        var secretKeys = platformConnector.fields(platform).stream()
                .filter(PlatformConnector.FieldSpec::secret)
                .map(PlatformConnector.FieldSpec::key)
                .collect(Collectors.toSet());
        config.forEach((k, v) -> {
            if (v == null || v.isEmpty()) {
                masked.put(k, "");
            } else if (secretKeys.contains(k)) {
                String tail = v.length() > 4 ? v.substring(v.length() - 4) : v;
                masked.put(k, "••••" + tail);
            } else {
                masked.put(k, v);
            }
        });
        return masked;
    }

    private List<UUID> accessibleConnectionIds() {
        List<UUID> storeIds = accessibleStoreIds();
        if (storeIds == null) return null;
        if (storeIds.isEmpty()) return List.of();
        LambdaQueryWrapper<PlatformConnectionEntity> query = new LambdaQueryWrapper<>();
        query.in(PlatformConnectionEntity::getStoreId, storeIds)
                .select(PlatformConnectionEntity::getId);
        return platformConnectionMapper.selectList(query).stream()
                .map(PlatformConnectionEntity::getId)
                .collect(Collectors.toList());
    }

    private void assertJobAccessible(ApiSyncJobEntity job, String requestedId) {
        if (job == null) {
            throw new BusinessException("SYNC_JOB_NOT_FOUND", "Sync job not found: " + requestedId);
        }
        PlatformConnectionEntity connection = platformConnectionMapper.selectById(job.getConnectionId());
        if (connection == null) {
            throw new BusinessException("CONNECTION_NOT_FOUND", "Platform connection not found for sync job");
        }
        assertConnectionAccessible(connection);
    }

    /** Safely parse a UUID, returning null for null/blank/non-UUID values. */
    private static UUID parseUuidOrNull(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    @Override
    public PageResponse<ApiSyncJobVo> listApiSyncJobs(int page, int pageSize,
                                                      String platform,
                                                      String status,
                                                      String syncType) {
        Page<ApiSyncJobEntity> pageParam = new Page<>(page, pageSize);
        LambdaQueryWrapper<ApiSyncJobEntity> wrapper = new LambdaQueryWrapper<>();
        List<UUID> visibleConnectionIds = accessibleConnectionIds();
        if (visibleConnectionIds != null) {
            if (visibleConnectionIds.isEmpty()) return PageResponse.of(List.of(), 0, page, pageSize);
            wrapper.in(ApiSyncJobEntity::getConnectionId, visibleConnectionIds);
        }
        if (status != null && !status.isBlank()) {
            wrapper.eq(ApiSyncJobEntity::getStatus, status.trim());
        }
        if (syncType != null && !syncType.isBlank()) {
            wrapper.eq(ApiSyncJobEntity::getSyncType, syncType.trim());
        }
        if (platform != null && !platform.isBlank()) {
            LambdaQueryWrapper<PlatformConnectionEntity> connectionWrapper = new LambdaQueryWrapper<>();
            connectionWrapper.eq(PlatformConnectionEntity::getPlatform, platform.trim());
            if (!scopeConnections(connectionWrapper)) {
                return PageResponse.of(List.of(), 0, page, pageSize);
            }
            List<UUID> connectionIds = platformConnectionMapper.selectList(connectionWrapper).stream()
                    .map(PlatformConnectionEntity::getId)
                    .collect(Collectors.toList());
            if (connectionIds.isEmpty()) {
                return PageResponse.of(List.of(), 0, page, pageSize);
            }
            wrapper.in(ApiSyncJobEntity::getConnectionId, connectionIds);
        }
        wrapper.orderByDesc(ApiSyncJobEntity::getCreatedAt);

        Page<ApiSyncJobEntity> result = apiSyncJobMapper.selectPage(pageParam, wrapper);
        List<ApiSyncJobVo> voList = result.getRecords().stream()
                .map(this::toJobVo)
                .collect(Collectors.toList());

        return PageResponse.of(voList, result.getTotal(), page, pageSize);
    }

    @Override
    @Transactional
    public ApiSyncJobVo createApiSyncJob(String connectionId, String syncType) {
        PlatformConnectionEntity connection = platformConnectionMapper.selectById(UUID.fromString(connectionId));
        if (connection == null) {
            throw new BusinessException("CONNECTION_NOT_FOUND", "Platform connection not found: " + connectionId);
        }
        assertConnectionAccessible(connection);

        ApiSyncJobEntity entity = ApiSyncJobEntity.builder()
                .connectionId(UUID.fromString(connectionId))
                .syncType(syncType)
                .status("pending")
                .totalRecords(0)
                .recordsProcessed(0)
                .failedRecords(0)
                .startedAt(LocalDateTime.now())
                .build();

        apiSyncJobMapper.insert(entity);
        log.info("API sync job created: id={}, connectionId={}, syncType={}", entity.getId(), connectionId, syncType);
        return toJobVo(entity);
    }

    @Override
    @Transactional
    public ApiSyncJobVo retryApiSyncJob(String jobId, String userId) {
        ApiSyncJobEntity source = apiSyncJobMapper.selectById(UUID.fromString(jobId));
        assertJobAccessible(source, jobId);
        if ("running".equalsIgnoreCase(source.getStatus())) {
            throw new BusinessException(409, "SYNC_JOB_RUNNING", "Cannot retry a running sync job");
        }
        if (source.getEntityType() == null || source.getEntityType().isBlank()) {
            throw new BusinessException("INVALID_SYNC_JOB", "Sync job has no entity type to retry");
        }

        boolean fullResync = "full".equalsIgnoreCase(source.getSyncType());
        ApiSyncJobVo retried = syncJobRunner.startSync(
                source.getConnectionId(),
                source.getEntityType(),
                fullResync,
                parseUuidOrNull(userId));
        log.info("Retried sync job {} as {}", jobId, retried.getId());
        return retried;
    }

    @Override
    @Transactional
    public ApiSyncJobVo cancelApiSyncJob(String jobId) {
        UUID jobUuid = UUID.fromString(jobId);
        ApiSyncJobEntity job = apiSyncJobMapper.selectById(jobUuid);
        assertJobAccessible(job, jobId);
        String status = job.getStatus();
        if ("completed".equalsIgnoreCase(status) || "failed".equalsIgnoreCase(status)) {
            throw new BusinessException("SYNC_JOB_TERMINAL", "Cannot cancel a terminal sync job");
        }
        if (!"cancelled".equalsIgnoreCase(status)) {
            job.setStatus("cancelled");
            job.setErrorMessage("Cancelled by user");
            job.setCompletedAt(LocalDateTime.now());
            apiSyncJobMapper.updateById(job);
            log.info("Cancelled sync job {}", jobId);
        }
        return toJobVo(job);
    }

    @Override
    public PageResponse<ApiSyncLogVo> listApiSyncLogs(int page, int pageSize,
                                                      String level,
                                                      String jobId,
                                                      String search) {
        Page<ApiSyncLogEntity> pageParam = new Page<>(page, pageSize);
        LambdaQueryWrapper<ApiSyncLogEntity> wrapper = new LambdaQueryWrapper<>();
        List<UUID> visibleConnectionIds = accessibleConnectionIds();
        if (visibleConnectionIds != null) {
            if (visibleConnectionIds.isEmpty()) return PageResponse.of(List.of(), 0, page, pageSize);
            LambdaQueryWrapper<ApiSyncJobEntity> visibleJobQuery = new LambdaQueryWrapper<>();
            visibleJobQuery.in(ApiSyncJobEntity::getConnectionId, visibleConnectionIds)
                    .select(ApiSyncJobEntity::getId);
            List<UUID> visibleJobIds = apiSyncJobMapper.selectList(visibleJobQuery).stream()
                    .map(ApiSyncJobEntity::getId).collect(Collectors.toList());
            if (visibleJobIds.isEmpty()) return PageResponse.of(List.of(), 0, page, pageSize);
            wrapper.in(ApiSyncLogEntity::getJobId, visibleJobIds);
        }
        if (level != null && !level.isBlank()) {
            wrapper.eq(ApiSyncLogEntity::getLevel, level.trim());
        }
        UUID jobUuid = parseUuidOrNull(jobId);
        if (jobUuid != null) {
            assertJobAccessible(apiSyncJobMapper.selectById(jobUuid), jobId);
            wrapper.eq(ApiSyncLogEntity::getJobId, jobUuid);
        } else if (jobId != null && !jobId.isBlank()) {
            return PageResponse.of(List.of(), 0, page, pageSize);
        }
        if (search != null && !search.isBlank()) {
            wrapper.like(ApiSyncLogEntity::getMessage, search.trim());
        }
        wrapper.orderByDesc(ApiSyncLogEntity::getCreatedAt);

        Page<ApiSyncLogEntity> result = apiSyncLogMapper.selectPage(pageParam, wrapper);
        List<ApiSyncLogVo> voList = result.getRecords().stream()
                .map(this::toSyncLogVo)
                .collect(Collectors.toList());

        return PageResponse.of(voList, result.getTotal(), page, pageSize);
    }

    @Override
    public ApiSyncJobVo startStoreSync(String storeId, StoreSyncRequest request, String userId) {
        if (storeId == null || storeId.isBlank()) {
            throw new BusinessException("INVALID_REQUEST", "storeId is required");
        }
        if (request == null || request.getEntityType() == null || request.getEntityType().isBlank()) {
            throw new BusinessException("INVALID_REQUEST", "entityType is required");
        }
        UUID storeUuid = UUID.fromString(storeId);
        assertStoreAccessible(storeUuid);
        String entityType = request.getEntityType();
        boolean fullResync = Boolean.TRUE.equals(request.getFullResync());

        UUID connectionId = resolveConnectionId(storeUuid, request);
        UUID triggeredBy = parseUuidOrNull(userId);
        return syncJobRunner.startSync(connectionId, entityType, fullResync, triggeredBy);
    }

    /**
     * Resolve the Platform Connection to sync for a store (Req 1.1.1/1.1.2).
     * Honors an explicit {@code connectionId}, then an explicit {@code platform},
     * otherwise prefers a connection in the {@code connected} state, falling back
     * to the most recently created connection for the store.
     */
    private UUID resolveConnectionId(UUID storeUuid, StoreSyncRequest request) {
        if (request.getConnectionId() != null && !request.getConnectionId().isBlank()) {
            UUID connId = UUID.fromString(request.getConnectionId());
            PlatformConnectionEntity conn = platformConnectionMapper.selectById(connId);
            if (conn == null || !storeUuid.equals(conn.getStoreId())) {
                throw new BusinessException("CONNECTION_NOT_FOUND",
                        "Platform connection not found for store: " + request.getConnectionId());
            }
            return connId;
        }

        LambdaQueryWrapper<PlatformConnectionEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(PlatformConnectionEntity::getStoreId, storeUuid);
        if (request.getPlatform() != null && !request.getPlatform().isBlank()) {
            wrapper.eq(PlatformConnectionEntity::getPlatform, request.getPlatform());
        }
        wrapper.orderByDesc(PlatformConnectionEntity::getCreatedAt);
        List<PlatformConnectionEntity> connections = platformConnectionMapper.selectList(wrapper);
        if (connections.isEmpty()) {
            throw new BusinessException("NO_ACTIVE_CONNECTION",
                    "No platform connection found for store " + storeUuid + " to sync.");
        }
        return connections.stream()
                .filter(c -> ConnectionStatus.CONNECTED.equals(c.getStatus()))
                .map(PlatformConnectionEntity::getId)
                .findFirst()
                .orElse(connections.get(0).getId());
    }

    @Override
    public PageResponse<ApiSyncJobVo> listStoreSyncJobs(String storeId, int page, int pageSize, String status) {
        UUID storeUuid = UUID.fromString(storeId);
        assertStoreAccessible(storeUuid);

        // Resolve the store's connections; jobs are keyed by connection.
        LambdaQueryWrapper<PlatformConnectionEntity> connWrapper = new LambdaQueryWrapper<>();
        connWrapper.eq(PlatformConnectionEntity::getStoreId, storeUuid)
                .select(PlatformConnectionEntity::getId);
        List<UUID> connectionIds = platformConnectionMapper.selectList(connWrapper).stream()
                .map(PlatformConnectionEntity::getId)
                .collect(Collectors.toList());
        if (connectionIds.isEmpty()) {
            return PageResponse.of(List.of(), 0, page, pageSize);
        }

        Page<ApiSyncJobEntity> pageParam = new Page<>(page, pageSize);
        LambdaQueryWrapper<ApiSyncJobEntity> wrapper = new LambdaQueryWrapper<>();
        List<UUID> visibleConnectionIds = accessibleConnectionIds();
        if (visibleConnectionIds != null) {
            if (visibleConnectionIds.isEmpty()) return PageResponse.of(List.of(), 0, page, pageSize);
            wrapper.in(ApiSyncJobEntity::getConnectionId, visibleConnectionIds);
        }
        if (status != null && !status.isBlank()) {
            wrapper.eq(ApiSyncJobEntity::getStatus, status.trim());
        }
        // Req 1.3.5: most-recent-first. Canonical ordering is started_at DESC
        // (see SyncHistoryOrdering); created_at is the tiebreaker for jobs that
        // have not yet started so the ordering stays total.
        wrapper.in(ApiSyncJobEntity::getConnectionId, connectionIds)
                .orderByDesc(ApiSyncJobEntity::getStartedAt)
                .orderByDesc(ApiSyncJobEntity::getCreatedAt);

        Page<ApiSyncJobEntity> result = apiSyncJobMapper.selectPage(pageParam, wrapper);
        List<ApiSyncJobVo> voList = result.getRecords().stream()
                .map(this::toJobVo)
                .collect(Collectors.toList());

        return PageResponse.of(voList, result.getTotal(), page, pageSize);
    }

    @Override
    public PageResponse<ApiSyncLogVo> listJobLogs(String jobId, int page, int pageSize) {
        UUID jobUuid = UUID.fromString(jobId);
        assertJobAccessible(apiSyncJobMapper.selectById(jobUuid), jobId);
        Page<ApiSyncLogEntity> pageParam = new Page<>(page, pageSize);
        LambdaQueryWrapper<ApiSyncLogEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ApiSyncLogEntity::getJobId, jobUuid)
                .orderByDesc(ApiSyncLogEntity::getCreatedAt);

        Page<ApiSyncLogEntity> result = apiSyncLogMapper.selectPage(pageParam, wrapper);
        List<ApiSyncLogVo> voList = result.getRecords().stream()
                .map(this::toSyncLogVo)
                .collect(Collectors.toList());

        return PageResponse.of(voList, result.getTotal(), page, pageSize);
    }

    private ApiSyncLogVo toSyncLogVo(ApiSyncLogEntity entity) {
        String createdAt = entity.getCreatedAt() != null ? entity.getCreatedAt().format(FORMATTER) : null;
        return ApiSyncLogVo.builder()
                .id(entity.getId() != null ? entity.getId().toString() : null)
                .jobId(entity.getJobId() != null ? entity.getJobId().toString() : null)
                .level(entity.getLevel())
                .message(entity.getMessage())
                .details(entity.getDetails())
                .createdAt(createdAt)
                .timestamp(createdAt)
                .build();
    }

    private PlatformConnectionVo toConnectionVo(PlatformConnectionEntity entity) {
        String lastSync = entity.getLastSyncAt() != null ? entity.getLastSyncAt().format(FORMATTER) : null;
        boolean hasConfig = entity.getConfigEncrypted() != null && !entity.getConfigEncrypted().isBlank();
        return PlatformConnectionVo.builder()
                .id(entity.getId() != null ? entity.getId().toString() : null)
                .storeId(entity.getStoreId() != null ? entity.getStoreId().toString() : null)
                .platform(entity.getPlatform())
                .platformId(entity.getPlatform())
                .connectionName(entity.getConnectionName())
                .status(entity.getStatus())
                .hasConfig(hasConfig)
                .lastSyncAt(lastSync)
                .lastSyncTime(lastSync)
                .createdAt(entity.getCreatedAt() != null ? entity.getCreatedAt().format(FORMATTER) : null)
                .updatedAt(entity.getUpdatedAt() != null ? entity.getUpdatedAt().format(FORMATTER) : null)
                .build();
    }

    private ApiSyncJobVo toJobVo(ApiSyncJobEntity entity) {
        return ApiSyncJobVo.builder()
                .id(entity.getId().toString())
                .connectionId(entity.getConnectionId().toString())
                .syncType(entity.getSyncType())
                .entityType(entity.getEntityType())
                .status(entity.getStatus())
                .totalRecords(entity.getTotalRecords())
                .recordsProcessed(entity.getRecordsProcessed())
                .failedRecords(entity.getFailedRecords())
                .errorMessage(entity.getErrorMessage())
                .startedAt(entity.getStartedAt() != null ? entity.getStartedAt().format(FORMATTER) : null)
                .completedAt(entity.getCompletedAt() != null ? entity.getCompletedAt().format(FORMATTER) : null)
                .createdAt(entity.getCreatedAt() != null ? entity.getCreatedAt().format(FORMATTER) : null)
                .build();
    }

}
