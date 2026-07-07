package com.adpilot.modules.advertising.hosting;

import lombok.Builder;
import lombok.Data;

import java.util.UUID;

/**
 * Summary of an entity sync execution for a single store.
 */
@Data
@Builder
public class EntitySyncResult {

    private UUID storeId;
    private int campaignsSynced;
    private int adGroupsSynced;
    private int keywordsSynced;
    private int mappingsCreated;
    private int mappingsUpdated;
    private int errors;
    private boolean success;
    private String errorMessage;

    public static EntitySyncResult success(UUID storeId, int campaigns, int adGroups,
                                           int keywords, int created, int updated) {
        return EntitySyncResult.builder()
                .storeId(storeId)
                .campaignsSynced(campaigns)
                .adGroupsSynced(adGroups)
                .keywordsSynced(keywords)
                .mappingsCreated(created)
                .mappingsUpdated(updated)
                .success(true)
                .build();
    }

    public static EntitySyncResult failure(UUID storeId, String error) {
        return EntitySyncResult.builder()
                .storeId(storeId)
                .success(false)
                .errorMessage(error)
                .build();
    }
}
