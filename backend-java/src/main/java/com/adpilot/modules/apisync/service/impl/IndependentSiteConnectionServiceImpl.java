package com.adpilot.modules.apisync.service.impl;

import com.adpilot.common.exception.BusinessException;
import com.adpilot.common.utils.CryptoUtil;
import com.adpilot.common.utils.SecurityUtils;
import com.adpilot.modules.apisync.connector.PlatformConnector;
import com.adpilot.modules.apisync.dto.IndependentSiteConnectRequest;
import com.adpilot.modules.apisync.dto.IndependentSiteGoogleAdsBindRequest;
import com.adpilot.modules.apisync.entity.PlatformConnectionEntity;
import com.adpilot.modules.apisync.model.ConnectionStatus;
import com.adpilot.modules.apisync.mapper.PlatformConnectionMapper;
import com.adpilot.modules.apisync.service.IndependentSiteConnectionService;
import com.adpilot.modules.apisync.vo.PlatformConnectionVo;
import com.adpilot.modules.rbac.PlatformFamily;
import com.adpilot.modules.rbac.service.StoreGroupService;
import com.adpilot.modules.store.entity.MarketplaceEntity;
import com.adpilot.modules.store.entity.StoreEntity;
import com.adpilot.modules.store.mapper.MarketplaceMapper;
import com.adpilot.modules.store.mapper.StoreMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
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
import java.util.Set;
import java.util.UUID;

/**
 * Default {@link IndependentSiteConnectionService} (platform-workspace-rbac
 * Req 5). Validates credentials against the external platform first, so a
 * rejection leaves any existing connection unchanged (Req 5.3); only on success
 * does it create the {@code PlatformConnectionEntity}, bind the Store, and assign
 * the Store to the independent-site Store_Group system (Req 5.2, 5.4).
 *
 * <p>Google Ads is treated as an independent-site advertising account bound to a
 * selected Store, never a standalone ad-only connection (Req 5.5).</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IndependentSiteConnectionServiceImpl implements IndependentSiteConnectionService {

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** The independent-site store platforms this wizard accepts (Req 5.2). */
    static final Set<String> INDEPENDENT_SITE_PLATFORMS = Set.of("shopify", "woocommerce", "tiktok_shop");

    private static final String GOOGLE_ADS = "google_ads";

    private final PlatformConnectionMapper platformConnectionMapper;
    private final StoreMapper storeMapper;
    private final MarketplaceMapper marketplaceMapper;
    private final PlatformConnector platformConnector;
    private final StoreGroupService storeGroupService;
    private final CryptoUtil cryptoUtil;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional
    public PlatformConnectionVo connectStore(IndependentSiteConnectRequest request, String userId) {
        if (request == null || request.getPlatform() == null || request.getPlatform().isBlank()) {
            throw new BusinessException("INVALID_REQUEST", "platform is required");
        }
        String platform = request.getPlatform().trim();
        if (!INDEPENDENT_SITE_PLATFORMS.contains(platform)) {
            throw new BusinessException("UNSUPPORTED_PLATFORM",
                    "独立站连接仅支持 Shopify / WooCommerce / TikTok：" + platform);
        }
        // Enforce that the connection's platform key belongs to the declared
        // Nav_Block platform family; reject any cross-family key (Req 6.2).
        assertPlatformInBlockFamily(platform, request.getBlockFamily());

        Map<String, String> config = cleanConfig(request.getConfig());

        // Validate credentials against the external platform BEFORE any write, so a
        // rejection leaves any existing connection unchanged (Req 5.3).
        PlatformConnector.TestResult result = platformConnector.test(platform, config);
        if (!result.ok()) {
            throw new BusinessException("CREDENTIALS_REJECTED", result.message());
        }

        UUID orgId = requireCurrentOrgId();
        UUID creator = parseUuidOrNull(userId);

        // Create the independent-site Store with its platform family set so the
        // Store↔Store_Group platform-family match holds on assignment (Req 5.4, 10.6).
        String storeName = (request.getStoreName() != null && !request.getStoreName().isBlank())
                ? request.getStoreName().trim() : platformLabel(platform) + " 店铺";
        StoreEntity store = StoreEntity.builder()
                .id(UUID.randomUUID())
                .orgId(orgId)
                .name(storeName)
                .marketplaceId(getOrCreateMarketplace(platform))
                .platformFamily(PlatformFamily.INDEPENDENT_SITE.getCode())
                .status(ConnectionStatus.CONNECTED)
                .createdBy(creator)
                .build();
        storeMapper.insert(store);

        // Assign to the selected independent-site Store_Group, or the family default
        // when none was selected (Req 5.4, 10.7).
        UUID groupId = resolveStoreGroupId(request.getStoreGroupId());
        storeGroupService.assignStore(store.getId(), groupId);

        // Create the connection bound to the new Store in the same flow (Req 5.2).
        PlatformConnectionEntity conn = PlatformConnectionEntity.builder()
                .id(UUID.randomUUID())
                .storeId(store.getId())
                .platform(platform)
                .connectionName(storeName)
                .configEncrypted(writeConfig(config))
                .status(ConnectionStatus.CONNECTED)
                .lastSyncAt(LocalDateTime.now())
                .createdBy(creator)
                .build();
        platformConnectionMapper.insert(conn);

        log.info("Connected independent-site store {} on platform {} (group={})",
                store.getId(), platform, groupId);
        PlatformConnectionVo vo = toConnectionVo(conn);
        vo.setMessage(result.message());
        return vo;
    }

    @Override
    @Transactional
    public PlatformConnectionVo bindGoogleAds(IndependentSiteGoogleAdsBindRequest request, String userId) {
        if (request == null || request.getStoreId() == null || request.getStoreId().isBlank()) {
            throw new BusinessException("INVALID_REQUEST", "缺少 storeId：Google Ads 需绑定到已有独立站店铺");
        }
        UUID storeUuid = parseStoreUuid(request.getStoreId());

        // Google Ads is bound to an existing independent-site Store, never a
        // standalone ad-only connection (Req 5.5).
        StoreEntity store = assertIndependentSiteStore(storeUuid);

        Map<String, String> config = cleanConfig(request.getConfig());

        // Validate credentials first; a rejection leaves any existing connection
        // unchanged (Req 5.3).
        PlatformConnector.TestResult result = platformConnector.test(GOOGLE_ADS, config);
        if (!result.ok()) {
            throw new BusinessException("CREDENTIALS_REJECTED", result.message());
        }

        String connectionName = (request.getConnectionName() != null && !request.getConnectionName().isBlank())
                ? request.getConnectionName().trim()
                : "Google Ads · " + store.getName();

        PlatformConnectionEntity entity = findByStoreAndPlatform(storeUuid, GOOGLE_ADS);
        if (entity == null) {
            entity = PlatformConnectionEntity.builder()
                    .id(UUID.randomUUID())
                    .storeId(storeUuid)
                    .platform(GOOGLE_ADS)
                    .connectionName(connectionName)
                    .configEncrypted(writeConfig(config))
                    .status(ConnectionStatus.CONNECTED)
                    .lastSyncAt(LocalDateTime.now())
                    .createdBy(parseUuidOrNull(userId))
                    .build();
            platformConnectionMapper.insert(entity);
        } else {
            entity.setConnectionName(connectionName);
            entity.setConfigEncrypted(writeConfig(config));
            entity.setStatus(ConnectionStatus.CONNECTED);
            entity.setLastSyncAt(LocalDateTime.now());
            entity.setUpdatedAt(LocalDateTime.now());
            platformConnectionMapper.updateById(entity);
        }

        log.info("Bound Google Ads to independent-site store {}", storeUuid);
        PlatformConnectionVo vo = toConnectionVo(entity);
        vo.setMessage(result.message());
        return vo;
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /**
     * Enforce that the connection's platform key belongs to the declared
     * Nav_Block platform family (Req 6.2). The declared family is the connection
     * entry's {@code ?platform=} scope. A cross-family platform key (e.g. an
     * {@code amazon_*} key submitted under an {@code independent_site} block) is
     * rejected with HTTP 403. When no scope is declared the per-service platform
     * allow-list still applies.
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

    /** Resolve the target group: the selected group when provided, else the family default (Req 5.4, 10.7). */
    private UUID resolveStoreGroupId(String selected) {
        UUID selectedId = parseUuidOrNull(selected);
        if (selectedId != null) {
            return selectedId;
        }
        return storeGroupService.defaultGroupFor(PlatformFamily.INDEPENDENT_SITE);
    }

    private StoreEntity assertIndependentSiteStore(UUID storeId) {
        UUID orgId = requireCurrentOrgId();
        StoreEntity store = storeMapper.selectById(storeId);
        if (store == null || !orgId.equals(store.getOrgId())) {
            throw new BusinessException(404, "STORE_NOT_FOUND", "Store not found: " + storeId);
        }
        if (!PlatformFamily.INDEPENDENT_SITE.getCode().equalsIgnoreCase(store.getPlatformFamily())) {
            throw new BusinessException(400, "INVALID_STORE_PLATFORM",
                    "Google Ads 只能绑定到独立站店铺");
        }
        return store;
    }

    private PlatformConnectionEntity findByStoreAndPlatform(UUID storeId, String platform) {
        LambdaQueryWrapper<PlatformConnectionEntity> w = new LambdaQueryWrapper<>();
        w.eq(PlatformConnectionEntity::getStoreId, storeId)
                .eq(PlatformConnectionEntity::getPlatform, platform)
                .orderByDesc(PlatformConnectionEntity::getUpdatedAt)
                .last("LIMIT 1");
        return platformConnectionMapper.selectOne(w);
    }

    /** Drop blank credential values and trim the rest. */
    private Map<String, String> cleanConfig(Map<String, String> raw) {
        Map<String, String> config = new LinkedHashMap<>();
        if (raw != null) {
            raw.forEach((k, v) -> {
                if (v != null && !v.isBlank()) config.put(k, v.trim());
            });
        }
        return config;
    }

    /** Get-or-create a per-platform marketplace so the store satisfies the NOT NULL FK. */
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
            case "shopify" -> "SHOPIFY";
            case "woocommerce" -> "WOO";
            case "tiktok_shop" -> "TIKTOK";
            default -> platform.toUpperCase();
        };
    }

    private static String platformLabel(String platform) {
        return switch (platform) {
            case "shopify" -> "Shopify";
            case "woocommerce" -> "WooCommerce";
            case "tiktok_shop" -> "TikTok Shop";
            case "google_ads" -> "Google Ads";
            default -> platform;
        };
    }

    /**
     * Serialize a credential map as a JSON object of individually-encrypted
     * values, matching the format read by {@code ApiSyncServiceImpl.readConfig}.
     */
    private String writeConfig(Map<String, String> config) {
        if (config == null || config.isEmpty()) {
            return null;
        }
        try {
            Map<String, String> encrypted = new LinkedHashMap<>();
            config.forEach((k, v) -> encrypted.put(k, (v == null || v.isEmpty()) ? v : cryptoUtil.encrypt(v)));
            return objectMapper.writeValueAsString(encrypted);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize config", e);
        }
    }

    private UUID requireCurrentOrgId() {
        if (!SecurityUtils.isAuthenticated()) {
            throw new BusinessException(401, "AUTH_REQUIRED", "Authentication is required");
        }
        UUID orgId = parseUuidOrNull(SecurityUtils.getCurrentOrgId());
        if (orgId == null) {
            throw new BusinessException(403, "ORG_CONTEXT_REQUIRED", "Organization context is required");
        }
        return orgId;
    }

    private static UUID parseStoreUuid(String storeId) {
        UUID id = parseUuidOrNull(storeId);
        if (id == null) {
            throw new BusinessException("INVALID_REQUEST", "storeId 格式无效");
        }
        return id;
    }

    private static UUID parseUuidOrNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value.trim());
        } catch (IllegalArgumentException ex) {
            return null;
        }
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
}
