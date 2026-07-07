package com.adpilot.modules.advertising.support;

import lombok.Value;

import java.util.List;
import java.util.UUID;

/**
 * Outcome of propagating a Goal's whitelisted fields (target ACoS + optimization goal) to its
 * associated Campaigns (Requirement 20.2 / 20.3).
 *
 * <p>It records exactly which Campaigns were changed and which were left untouched because they
 * carry a Campaign-level override for the propagated field, so the propagation is auditable and the
 * "override always wins" rule is observable.</p>
 *
 * <p>Validates: Requirements 20.2, 20.3.</p>
 */
@Value
public class GoalPropagationResult {

    /** Ids of Campaigns that received at least one propagated value. */
    List<UUID> updatedCampaignIds;

    /**
     * Ids of Campaigns that were skipped for at least one field because they hold a Campaign-level
     * override that must win over the propagated Goal value (Req 20.3).
     */
    List<UUID> skippedOverrideCampaignIds;

    public int updatedCount() {
        return updatedCampaignIds.size();
    }
}
