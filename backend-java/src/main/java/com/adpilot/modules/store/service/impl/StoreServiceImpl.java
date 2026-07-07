package com.adpilot.modules.store.service.impl;

import com.adpilot.common.api.PageResponse;
import com.adpilot.common.exception.BusinessException;
import com.adpilot.common.utils.SecurityUtils;
import com.adpilot.modules.product.entity.ProductEntity;
import com.adpilot.modules.product.mapper.ProductMapper;
import com.adpilot.modules.store.dto.StoreDto;
import com.adpilot.modules.store.entity.MarketplaceEntity;
import com.adpilot.modules.store.entity.StoreEntity;
import com.adpilot.modules.store.entity.UserStoreEntity;
import com.adpilot.modules.store.mapper.MarketplaceMapper;
import com.adpilot.modules.store.mapper.StoreMapper;
import com.adpilot.modules.store.mapper.UserStoreMapper;
import com.adpilot.modules.store.service.StoreService;
import com.adpilot.modules.store.vo.StoreVo;
import com.adpilot.modules.apisync.entity.PlatformConnectionEntity;
import com.adpilot.modules.apisync.model.ConnectionStatus;
import com.adpilot.modules.apisync.mapper.PlatformConnectionMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class StoreServiceImpl implements StoreService {

    private final StoreMapper storeMapper;
    private final MarketplaceMapper marketplaceMapper;
    private final ProductMapper productMapper;
    private final UserStoreMapper userStoreMapper;
    private final PlatformConnectionMapper platformConnectionMapper;

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
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
    public PageResponse<StoreVo> listStores(int page, int pageSize) {
        Page<StoreEntity> pageParam = new Page<>(page, pageSize);
        LambdaQueryWrapper<StoreEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(StoreEntity::getOrgId, requireCurrentOrgId());

        // Store-level scoping: admins see all stores; everyone else sees only the
        // stores assigned to them (or that they created).
        if (!SecurityUtils.isStoreAdmin() && SecurityUtils.isAuthenticated()) {
            String uid = SecurityUtils.getCurrentUserId();
            List<UUID> allowed = resolveAssignedStoreUuids(uid);
            if (allowed.isEmpty()) {
                return PageResponse.of(Collections.emptyList(), 0, page, pageSize);
            }
            wrapper.in(StoreEntity::getId, allowed);
        }
        wrapper.orderByDesc(StoreEntity::getCreatedAt);

        Page<StoreEntity> result = storeMapper.selectPage(pageParam, wrapper);
        List<StoreVo> voList = result.getRecords().stream()
                .map(this::toVo)
                .collect(Collectors.toList());

        return PageResponse.of(voList, result.getTotal(), page, pageSize);
    }

    /** Store UUIDs a user may access: explicit assignments + stores they created. */
    private List<UUID> resolveAssignedStoreUuids(String userId) {
        UUID uid = parseUuidOrNull(userId);
        if (uid == null) return new ArrayList<>();
        LambdaQueryWrapper<UserStoreEntity> w = new LambdaQueryWrapper<>();
        w.eq(UserStoreEntity::getUserId, uid);
        List<UUID> ids = userStoreMapper.selectList(w).stream()
                .map(UserStoreEntity::getStoreId).collect(Collectors.toList());
        // Also include stores created by the user (covers self-created before assignment).
        LambdaQueryWrapper<StoreEntity> sw = new LambdaQueryWrapper<>();
        sw.eq(StoreEntity::getCreatedBy, uid);
        storeMapper.selectList(sw).forEach(s -> {
            if (!ids.contains(s.getId())) ids.add(s.getId());
        });
        return ids;
    }

    @Override
    public StoreVo getStoreById(String id) {
        StoreEntity entity = storeMapper.selectById(UUID.fromString(id));
        assertAccessible(entity, id);
        return toVo(entity);
    }

    @Override
    @Transactional
    public StoreVo createStore(StoreDto dto, String userId) {
        UUID orgId = requireCurrentOrgId();

        StoreEntity entity = StoreEntity.builder()
                .orgId(orgId)
                .name(dto.getName())
                .marketplaceId(UUID.fromString(dto.getMarketplaceId()))
                .sellerId(dto.getSellerId())
                .status(ConnectionStatus.CONNECTED)
                .createdBy(parseUuidOrNull(userId))
                .updatedBy(parseUuidOrNull(userId))
                .build();

        storeMapper.insert(entity);
        // Auto-assign the new store to its creator so operators immediately see
        // (and retain access to) stores they add themselves.
        UUID creator = parseUuidOrNull(userId);
        if (creator != null) {
            upsertAssignment(creator, entity.getId());
        }
        log.info("Store created: id={}, name={}", entity.getId(), entity.getName());
        return toVo(entity);
    }

    @Override
    @Transactional
    public StoreVo updateStore(String id, StoreDto dto, String userId) {
        StoreEntity entity = storeMapper.selectById(UUID.fromString(id));
        assertAccessible(entity, id);

        if (dto.getName() != null) {
            entity.setName(dto.getName());
        }
        if (dto.getMarketplaceId() != null) {
            entity.setMarketplaceId(UUID.fromString(dto.getMarketplaceId()));
        }
        if (dto.getSellerId() != null) {
            entity.setSellerId(dto.getSellerId());
        }
        if (dto.getStatus() != null && !dto.getStatus().isBlank()) {
            entity.setStatus(dto.getStatus());
        }
        entity.setUpdatedBy(parseUuidOrNull(userId));
        entity.setUpdatedAt(LocalDateTime.now());

        storeMapper.updateById(entity);
        log.info("Store updated: id={}", id);
        return toVo(entity);
    }

    @Override
    @Transactional
    public void deleteStore(String id) {
        StoreEntity entity = storeMapper.selectById(UUID.fromString(id));
        assertAccessible(entity, id);
        storeMapper.deleteById(UUID.fromString(id));
        log.info("Store deleted: id={}", id);
    }

    @Override
    public List<MarketplaceEntity> listMarketplaces() {
        return marketplaceMapper.selectList(null);
    }

    @Override
    public List<String> getAssignedStoreIds(String userId) {
        UUID uid = parseUuidOrNull(userId);
        if (uid == null) return new ArrayList<>();
        LambdaQueryWrapper<UserStoreEntity> w = new LambdaQueryWrapper<>();
        w.eq(UserStoreEntity::getUserId, uid);
        return userStoreMapper.selectList(w).stream()
                .map(us -> us.getStoreId().toString())
                .collect(Collectors.toList());
    }

    @Override
    @Transactional
    public void assignStores(String userId, List<String> storeIds) {
        UUID uid = parseUuidOrNull(userId);
        if (uid == null) {
            throw new BusinessException("INVALID_USER", "无效的用户ID");
        }
        // Replace existing assignments with the provided set.
        LambdaQueryWrapper<UserStoreEntity> del = new LambdaQueryWrapper<>();
        del.eq(UserStoreEntity::getUserId, uid);
        userStoreMapper.delete(del);
        if (storeIds != null) {
            for (String sid : storeIds) {
                UUID storeUuid = parseUuidOrNull(sid);
                if (storeUuid != null) {
                    assertStoreInCurrentOrg(storeUuid, sid);
                    upsertAssignment(uid, storeUuid);
                }
            }
        }
        log.info("Assigned {} stores to user {}", storeIds != null ? storeIds.size() : 0, userId);
    }

    private void upsertAssignment(UUID userId, UUID storeId) {
        LambdaQueryWrapper<UserStoreEntity> w = new LambdaQueryWrapper<>();
        w.eq(UserStoreEntity::getUserId, userId).eq(UserStoreEntity::getStoreId, storeId);
        if (userStoreMapper.selectCount(w) == 0) {
            userStoreMapper.insert(UserStoreEntity.builder()
                    .userId(userId).storeId(storeId).build());
        }
    }

    private StoreVo toVo(StoreEntity entity) {
        // Look up marketplace
        MarketplaceEntity marketplace = marketplaceMapper.selectById(entity.getMarketplaceId());
        String marketplaceName = marketplace != null ? marketplace.getName() : null;
        String marketplaceCode = marketplace != null ? marketplace.getCode() : null;

        // Count products for this store
        LambdaQueryWrapper<ProductEntity> productWrapper = new LambdaQueryWrapper<>();
        productWrapper.eq(ProductEntity::getStoreId, entity.getId());
        Long productCount = productMapper.selectCount(productWrapper);
        String platform = resolvePlatform(entity.getId(), marketplaceCode);

        return StoreVo.builder()
                .id(entity.getId().toString())
                .name(entity.getName())
                .marketplaceId(entity.getMarketplaceId().toString())
                .marketplaceName(marketplaceName)
                .marketplaceCode(marketplaceCode)
                .marketplaceCurrency(marketplace != null ? marketplace.getCurrency() : null)
                .marketplaceTimezone(marketplace != null ? marketplace.getTimezone() : null)
                .sellerId(entity.getSellerId())
                .status(entity.getStatus())
                .platform(platform)
                .platformGroup(resolvePlatformGroup(platform))
                .adPlatform(resolveAdPlatform(platform))
                .productCount(productCount != null ? productCount.intValue() : 0)
                .createdAt(entity.getCreatedAt() != null ? entity.getCreatedAt().format(FORMATTER) : null)
                .storeGroup(entity.getStoreGroup())
                .build();
    }

    /**
     * Resolve a normalized commerce platform family for a store. Ad-channel
     * connections such as Google Ads must not overwrite the store's commerce
     * platform, otherwise a Shopify/WooCommerce store would be misclassified
     * after adding its advertising account.
     */
    private String resolvePlatform(UUID storeId, String marketplaceCode) {
        try {
            LambdaQueryWrapper<PlatformConnectionEntity> w = new LambdaQueryWrapper<>();
            w.eq(PlatformConnectionEntity::getStoreId, storeId)
                    .orderByDesc(PlatformConnectionEntity::getUpdatedAt);
            List<PlatformConnectionEntity> connections = platformConnectionMapper.selectList(w);
            if (connections == null || connections.isEmpty()) {
                return isAmazonMarketplace(marketplaceCode) ? "amazon" : "unknown";
            }
            for (PlatformConnectionEntity conn : connections) {
                String family = commercePlatformFamily(conn.getPlatform());
                if (family != null) return family;
            }
            return "unknown";
        } catch (Exception e) {
            log.warn("Failed to resolve commerce platform for store {}: {}", storeId, e.getMessage());
            return "unknown";
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

    private void assertAccessible(StoreEntity entity, String requestedId) {
        if (entity == null || !requireCurrentOrgId().equals(entity.getOrgId())) {
            throw new BusinessException(404, "STORE_NOT_FOUND", "Store not found: " + requestedId);
        }
        if (!SecurityUtils.isStoreAdmin()
                && !resolveAssignedStoreUuids(SecurityUtils.getCurrentUserId()).contains(entity.getId())) {
            throw new BusinessException(403, "STORE_FORBIDDEN", "Store is outside your data scope");
        }
    }

    private void assertStoreInCurrentOrg(UUID storeId, String requestedId) {
        StoreEntity store = storeMapper.selectById(storeId);
        if (store == null || !requireCurrentOrgId().equals(store.getOrgId())) {
            throw new BusinessException(404, "STORE_NOT_FOUND", "Store not found: " + requestedId);
        }
    }

    private static boolean isAmazonMarketplace(String marketplaceCode) {
        if (marketplaceCode == null || marketplaceCode.isBlank()) return false;
        String code = marketplaceCode.trim().toUpperCase();
        return code.equals("AMZ") || java.util.Set.of(
                "US", "CA", "MX", "BR", "UK", "DE", "FR", "IT", "ES", "NL", "SE", "PL",
                "BE", "TR", "AE", "SA", "EG", "IN", "JP", "AU", "SG").contains(code);
    }

    private static String commercePlatformFamily(String platform) {
        if (platform == null || platform.isBlank()) return null;
        String p = platform.trim().toLowerCase();
        if (p.startsWith("amazon")) return "amazon";
        if (p.startsWith("shopify")) return "shopify";
        if (p.startsWith("woocommerce") || p.equals("woo") || p.startsWith("wp") || p.startsWith("wordpress")) {
            return "woocommerce";
        }
        if (p.startsWith("tiktok") || p.equals("tk")) return "tiktok";
        return null;
    }

    private static String resolvePlatformGroup(String platform) {
        String p = platform == null ? "" : platform.trim().toLowerCase();
        if ("shopify".equals(p) || "woocommerce".equals(p)) return "independent_site";
        if ("amazon".equals(p) || "tiktok".equals(p)) return "marketplace";
        return "unknown";
    }

    private static String resolveAdPlatform(String platform) {
        String p = platform == null ? "" : platform.trim().toLowerCase();
        if ("amazon".equals(p)) return "amazon_ads";
        if ("shopify".equals(p) || "woocommerce".equals(p)) return "google_ads";
        if ("tiktok".equals(p)) return "tiktok_ads";
        return "none";
    }
}
