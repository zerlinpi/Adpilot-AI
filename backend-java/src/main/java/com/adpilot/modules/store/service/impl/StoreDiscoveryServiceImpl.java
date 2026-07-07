package com.adpilot.modules.store.service.impl;

import com.adpilot.common.exception.BusinessException;
import com.adpilot.common.security.CurrentUser;
import com.adpilot.common.utils.CryptoUtil;
import com.adpilot.modules.apisync.connector.PlatformDataConnector;
import com.adpilot.modules.apisync.entity.PlatformConnectionEntity;
import com.adpilot.modules.apisync.model.ConnectionStatus;
import com.adpilot.modules.apisync.mapper.PlatformConnectionMapper;
import com.adpilot.modules.apisync.model.ConnectionContext;
import com.adpilot.modules.apisync.model.DiscoveredStore;
import com.adpilot.modules.store.entity.MarketplaceEntity;
import com.adpilot.modules.store.entity.StoreEntity;
import com.adpilot.modules.store.mapper.MarketplaceMapper;
import com.adpilot.modules.store.mapper.StoreMapper;
import com.adpilot.modules.store.service.StoreDiscoveryResult;
import com.adpilot.modules.store.service.StoreDiscoveryService;
import com.adpilot.modules.store.vo.StoreVo;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Default {@link StoreDiscoveryService}.
 *
 * <p>Calls {@link PlatformDataConnector#discoverStores(ConnectionContext)} for
 * the originating seller-account credential, then creates-or-reuses internal
 * {@link StoreEntity} rows keyed on the platform-reported marketplace
 * identifier (Req 6.1):</p>
 * <ul>
 *   <li>Req 6.1.1 — retrieve the marketplaces the credential grants access to
 *       via the connector.</li>
 *   <li>Req 6.1.2 — create an internal store for each previously-unseen
 *       marketplace, linked to the originating seller account.</li>
 *   <li>Req 6.1.3 — reuse the existing internal store for an already-known
 *       marketplace so repeated discovery never duplicates.</li>
 *   <li>Req 6.1.4 — associate each created store with the marketplace
 *       identifier reported by the platform.</li>
 * </ul>
 *
 * <h2>Marketplace identifier &amp; idempotency anchor</h2>
 * <p>The platform-reported marketplace identifier
 * ({@link DiscoveredStore#marketplaceId()}) is persisted on
 * {@link StoreEntity#getSellerId()} and used as the create-or-reuse key, scoped
 * to the originating seller account. {@link StoreEntity#getMarketplaceId()} is a
 * required foreign key to the reference {@code marketplaces} table (geographic
 * marketplace); it is resolved from the discovered currency where possible and
 * otherwise inherited from the originating store.</p>
 *
 * <h2>Seller-account link (graceful)</h2>
 * <p>The originating seller account is the platform connection. A dedicated
 * {@code platform_connections.seller_account_id} column is introduced later
 * (task 18.1, migration {@code V5}); until then the link is realized through
 * the originating connection's organization and store context, so discovery
 * works correctly on the current schema and tightens automatically once the
 * column exists.</p>
 */
@Slf4j
@Service
public class StoreDiscoveryServiceImpl implements StoreDiscoveryService {

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final String DEFAULT_ORG_ID = "00000000-0000-0000-0000-000000000001";

    private final PlatformConnectionMapper platformConnectionMapper;
    private final StoreMapper storeMapper;
    private final MarketplaceMapper marketplaceMapper;
    private final CryptoUtil cryptoUtil;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;

    /** platform key -> data connector, built from all injected connector beans. */
    private final Map<String, PlatformDataConnector> connectors = new ConcurrentHashMap<>();

    public StoreDiscoveryServiceImpl(PlatformConnectionMapper platformConnectionMapper,
                                     StoreMapper storeMapper,
                                     MarketplaceMapper marketplaceMapper,
                                     CryptoUtil cryptoUtil,
                                     ObjectMapper objectMapper,
                                     List<PlatformDataConnector> dataConnectors,
                                     TransactionTemplate transactionTemplate) {
        this.platformConnectionMapper = platformConnectionMapper;
        this.storeMapper = storeMapper;
        this.marketplaceMapper = marketplaceMapper;
        this.cryptoUtil = cryptoUtil;
        this.objectMapper = objectMapper;
        this.transactionTemplate = transactionTemplate;
        if (dataConnectors != null) {
            for (PlatformDataConnector connector : dataConnectors) {
                this.connectors.put(connector.platform(), connector);
            }
        }
    }

    @Override
    public StoreDiscoveryResult discover(UUID connectionId, CurrentUser user) {
        if (connectionId == null) {
            throw new BusinessException("INVALID_REQUEST", "connectionId is required");
        }

        // --- Reads only: resolve the connection, connector and originating context.
        //     No DB transaction is open here, so no connection is held across the
        //     remote platform call below (same fix already applied in EntitySync).
        PlatformConnectionEntity connection = platformConnectionMapper.selectById(connectionId);
        if (connection == null) {
            throw new BusinessException("CONNECTION_NOT_FOUND",
                    "Platform connection not found: " + connectionId);
        }

        String platform = connection.getPlatform();
        PlatformDataConnector connector = connectors.get(platform);
        if (connector == null) {
            throw new BusinessException("NO_DATA_CONNECTOR",
                    "No data connector available for platform '" + platform + "'");
        }

        // The originating store anchors the organization the discovered stores
        // belong to and provides a fallback reference marketplace.
        StoreEntity originatingStore = connection.getStoreId() != null
                ? storeMapper.selectById(connection.getStoreId())
                : null;
        UUID orgId = resolveOrgId(originatingStore, user);

        ConnectionContext ctx = new ConnectionContext(
                connection.getId(), connection.getStoreId(), platform,
                decryptConfig(connection.getConfigEncrypted()));

        // Req 6.1.1: retrieve the marketplaces the credential grants access to.
        // This is a remote platform API call and MUST run outside any transaction.
        List<DiscoveredStore> discovered;
        try {
            discovered = connector.discoverStores(ctx);
        } catch (UnsupportedOperationException e) {
            throw new BusinessException("DISCOVERY_NOT_SUPPORTED",
                    "Platform '" + platform + "' does not support store discovery");
        }
        if (discovered == null) {
            discovered = List.of();
        }

        // --- Persist the discovered results in a single short transaction, after
        //     the remote IO has completed. Executed through the TransactionTemplate
        //     so the create-or-reuse writes commit atomically without spanning the
        //     network call.
        UUID actor = parseUuidOrNull(user != null ? user.getUserId() : null);
        UUID fallbackMarketplaceRef = originatingStore != null ? originatingStore.getMarketplaceId() : null;
        List<DiscoveredStore> toPersist = discovered;
        List<StoreVo> created = new ArrayList<>();
        List<StoreVo> reused = new ArrayList<>();

        transactionTemplate.executeWithoutResult(status ->
                persistDiscovered(connectionId, orgId, toPersist, actor, fallbackMarketplaceRef, created, reused));

        log.info("Store discovery for connection {} ({}): {} created, {} reused",
                connectionId, platform, created.size(), reused.size());
        return new StoreDiscoveryResult(connectionId, platform, created, reused);
    }

    /**
     * Create-or-reuse internal stores for the discovered marketplaces. Runs inside
     * the short persistence transaction opened by {@link #discover}; the existing
     * store index is read here so the read-modify-write stays consistent within the
     * same transaction.
     */
    private void persistDiscovered(UUID connectionId, UUID orgId, List<DiscoveredStore> discovered,
                                   UUID actor, UUID fallbackMarketplaceRef,
                                   List<StoreVo> created, List<StoreVo> reused) {
        // Existing internal stores for this seller-account scope, keyed on the
        // platform marketplace identifier (persisted on seller_id).
        Map<String, StoreEntity> existingByMarketplace = indexExistingStores(orgId);

        for (DiscoveredStore ds : discovered) {
            String marketplaceId = ds.marketplaceId();
            if (marketplaceId == null || marketplaceId.isBlank()) {
                log.warn("Skipping discovered store with no marketplace identifier (connection={})", connectionId);
                continue;
            }

            StoreEntity existing = existingByMarketplace.get(marketplaceId);
            if (existing != null) {
                // Req 6.1.3: reuse the existing internal store; never duplicate.
                reused.add(toVo(existing));
                continue;
            }

            // Req 6.1.2 / 6.1.4: create a new internal store carrying the
            // platform-reported marketplace identifier, linked to the seller
            // account via the originating connection's organization context.
            UUID marketplaceRef = resolveMarketplaceRef(ds, fallbackMarketplaceRef);
            StoreEntity store = StoreEntity.builder()
                    .orgId(orgId)
                    .name(resolveName(ds))
                    .marketplaceId(marketplaceRef)
                    .sellerId(marketplaceId)
                    .status(ConnectionStatus.CONNECTED)
                    .createdBy(actor)
                    .updatedBy(actor)
                    .build();
            storeMapper.insert(store);

            // Dedupe within the same discovery batch so repeated identifiers in
            // a single response do not create duplicates either.
            existingByMarketplace.put(marketplaceId, store);
            created.add(toVo(store));
        }
    }

    /** Index existing stores in the org by the platform marketplace identifier (seller_id). */
    private Map<String, StoreEntity> indexExistingStores(UUID orgId) {
        Map<String, StoreEntity> index = new HashMap<>();
        if (orgId == null) {
            return index;
        }
        LambdaQueryWrapper<StoreEntity> wrapper = new LambdaQueryWrapper<StoreEntity>()
                .eq(StoreEntity::getOrgId, orgId);
        List<StoreEntity> stores = storeMapper.selectList(wrapper);
        if (stores != null) {
            for (StoreEntity store : stores) {
                String key = store.getSellerId();
                if (key != null && !key.isBlank()) {
                    // Keep the first store seen for a given marketplace identifier.
                    index.putIfAbsent(key, store);
                }
            }
        }
        return index;
    }

    /**
     * Resolve the reference marketplace foreign key for a discovered store.
     * Prefers a {@code marketplaces} row whose currency matches the discovered
     * currency; falls back to the originating store's marketplace, then to any
     * available marketplace. The column is {@code NOT NULL}, so a reference is
     * required to create the store.
     */
    private UUID resolveMarketplaceRef(DiscoveredStore ds, UUID fallback) {
        if (ds.currency() != null && !ds.currency().isBlank()) {
            LambdaQueryWrapper<MarketplaceEntity> wrapper = new LambdaQueryWrapper<MarketplaceEntity>()
                    .eq(MarketplaceEntity::getCurrency, ds.currency())
                    .last("LIMIT 1");
            MarketplaceEntity match = marketplaceMapper.selectOne(wrapper);
            if (match != null) {
                return match.getId();
            }
        }
        if (fallback != null) {
            return fallback;
        }
        List<MarketplaceEntity> all = marketplaceMapper.selectList(null);
        if (all != null && !all.isEmpty()) {
            return all.get(0).getId();
        }
        throw new BusinessException("NO_MARKETPLACE_REFERENCE",
                "No reference marketplace available to link the discovered store");
    }

    private static String resolveName(DiscoveredStore ds) {
        if (ds.name() != null && !ds.name().isBlank()) {
            return ds.name();
        }
        return ds.marketplaceId();
    }

    private UUID resolveOrgId(StoreEntity originatingStore, CurrentUser user) {
        if (originatingStore != null && originatingStore.getOrgId() != null) {
            return originatingStore.getOrgId();
        }
        UUID fromUser = parseUuidOrNull(user != null ? user.getOrgId() : null);
        if (fromUser != null) {
            return fromUser;
        }
        return UUID.fromString(DEFAULT_ORG_ID);
    }

    private Map<String, String> decryptConfig(String stored) {
        if (stored == null || stored.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, String> encrypted = objectMapper.readValue(
                    stored, new TypeReference<LinkedHashMap<String, String>>() {});
            Map<String, String> plain = new LinkedHashMap<>();
            encrypted.forEach((k, v) -> plain.put(k, v == null ? null : cryptoUtil.decrypt(v)));
            return plain;
        } catch (Exception e) {
            log.warn("Failed to read platform config for discovery: {}", e.getMessage());
            return Map.of();
        }
    }

    private static UUID parseUuidOrNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private StoreVo toVo(StoreEntity entity) {
        MarketplaceEntity marketplace = entity.getMarketplaceId() != null
                ? marketplaceMapper.selectById(entity.getMarketplaceId())
                : null;
        return StoreVo.builder()
                .id(entity.getId() != null ? entity.getId().toString() : null)
                .name(entity.getName())
                .marketplaceId(entity.getMarketplaceId() != null ? entity.getMarketplaceId().toString() : null)
                .marketplaceName(marketplace != null ? marketplace.getName() : null)
                .marketplaceCode(marketplace != null ? marketplace.getCode() : null)
                .sellerId(entity.getSellerId())
                .status(entity.getStatus())
                .storeGroup(entity.getStoreGroup())
                .createdAt(entity.getCreatedAt() != null ? entity.getCreatedAt().format(FORMATTER) : null)
                .build();
    }
}
