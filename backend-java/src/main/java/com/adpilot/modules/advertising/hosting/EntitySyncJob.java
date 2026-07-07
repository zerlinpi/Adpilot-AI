package com.adpilot.modules.advertising.hosting;

import com.adpilot.modules.apisync.entity.PlatformConnectionEntity;
import com.adpilot.modules.apisync.model.ConnectionStatus;
import com.adpilot.modules.apisync.mapper.PlatformConnectionMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * Scheduled job that synchronizes Amazon Ads entity metadata (campaigns, ad groups,
 * keywords) for all stores with active Amazon Ads connections.
 *
 * <p>The entity sync runs <b>before and independently of</b> metric ingestion
 * (Requirement 14.4) so that metrics always attach to complete entities. It pulls
 * entity metadata from the Amazon Ads API, upserts complete local entities, and
 * maintains {@code external_entity_mappings} with origin {@code amazon_import}.</p>
 *
 * <p>On read-after-write of locally-created entities, the mapping is updated with
 * the Amazon-assigned external id (Requirement 14.5).</p>
 *
 * <p>Validates: Requirements 14.1, 14.4, 14.5.</p>
 */
@Slf4j
@Component
public class EntitySyncJob {

    private static final String PLATFORM = "amazon_ads";

    private final EntitySyncService entitySyncService;
    private final PlatformConnectionMapper connectionMapper;

    /** Whether entity sync is enabled (can be disabled in environments without Amazon Ads access). */
    @Value("${adpilot.hosting.entity-sync.enabled:true}")
    private boolean enabled;

    public EntitySyncJob(EntitySyncService entitySyncService,
                         PlatformConnectionMapper connectionMapper) {
        this.entitySyncService = entitySyncService;
        this.connectionMapper = connectionMapper;
    }

    /**
     * Scheduled entry point. Fires on the configured fixed delay (default 30 minutes)
     * and syncs entities for all stores with active Amazon Ads connections.
     *
     * <p>Runs independently of the report sync and optimization tick, ensuring entity
     * metadata is available before metrics are ingested (Req 14.4).</p>
     */
    @Scheduled(fixedDelayString = "${adpilot.hosting.entity-sync.interval-ms:1800000}")
    public void syncAll() {
        if (!enabled) {
            log.debug("Entity sync is disabled");
            return;
        }

        try {
            List<UUID> storeIds = findStoresWithAmazonAdsConnection();
            if (storeIds.isEmpty()) {
                log.debug("No stores with active Amazon Ads connections for entity sync");
                return;
            }

            int successCount = 0;
            int failCount = 0;

            for (UUID storeId : storeIds) {
                try {
                    EntitySyncResult result = entitySyncService.syncEntities(storeId);
                    if (result.isSuccess()) {
                        successCount++;
                    } else {
                        failCount++;
                        log.warn("Entity sync failed for store {}: {}",
                                storeId, result.getErrorMessage());
                    }
                } catch (Exception e) {
                    failCount++;
                    log.warn("Entity sync failed for store {}: {}", storeId, e.getMessage());
                }
            }

            if (successCount > 0 || failCount > 0) {
                log.info("Entity sync completed: {} stores synced, {} failed",
                        successCount, failCount);
            }
        } catch (Exception e) {
            log.error("Entity sync job failed unexpectedly", e);
        }
    }

    /**
     * Trigger a sync for a specific store (for manual/on-demand use).
     *
     * @param storeId the store to sync
     * @return sync result
     */
    public EntitySyncResult syncStore(UUID storeId) {
        return entitySyncService.syncEntities(storeId);
    }

    private List<UUID> findStoresWithAmazonAdsConnection() {
        LambdaQueryWrapper<PlatformConnectionEntity> query = new LambdaQueryWrapper<>();
        query.eq(PlatformConnectionEntity::getPlatform, PLATFORM)
                .eq(PlatformConnectionEntity::getStatus, ConnectionStatus.CONNECTED)
                .select(PlatformConnectionEntity::getStoreId);
        return connectionMapper.selectList(query).stream()
                .map(PlatformConnectionEntity::getStoreId)
                .distinct()
                .toList();
    }
}
