package com.adpilot.modules.advertising.hosting;

import java.util.UUID;

/**
 * Service interface for synchronizing Amazon Ads entity metadata (campaigns,
 * ad groups, keywords) into local entities and {@code external_entity_mappings}.
 *
 * <p>The entity sync runs before/independently of metric ingestion so that
 * metrics always attach to complete entities (Requirement 14.4). On read-after-
 * write of locally-created entities, the mapping is updated with the Amazon-
 * assigned external id (Requirement 14.5).</p>
 *
 * <p>Validates: Requirements 14.1, 14.4, 14.5.</p>
 */
public interface EntitySyncService {

    /**
     * Synchronize all Amazon Ads entity metadata for a given store.
     *
     * <p>Pulls campaigns, ad groups, and keywords from the Amazon Ads API,
     * upserts complete local entities, and maintains {@code external_entity_mappings}
     * with origin {@code amazon_import}.</p>
     *
     * @param storeId the store to sync entities for
     * @return summary of the sync operation
     */
    EntitySyncResult syncEntities(UUID storeId);

    /**
     * Update the external entity mapping for a locally-created entity after
     * read-after-write verification confirms the Amazon-assigned id.
     *
     * @param storeId           the store owning the entity
     * @param internalEntityType the internal entity type (campaign, ad_group, keyword)
     * @param internalEntityId  the internal UUID of the entity
     * @param externalEntityId  the Amazon-assigned external id
     */
    void updateMappingAfterWrite(UUID storeId, String internalEntityType,
                                 UUID internalEntityId, String externalEntityId);
}
