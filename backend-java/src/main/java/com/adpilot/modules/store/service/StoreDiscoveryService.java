package com.adpilot.modules.store.service;

import com.adpilot.common.security.CurrentUser;

import java.util.UUID;

/**
 * Discovers the marketplaces a single seller-account credential grants access
 * to and reflects them as internal stores (Capability Area 6, Req 6.1).
 *
 * <p>Discovery is idempotent and keyed on the platform-reported marketplace
 * identifier: previously-unseen marketplaces become new internal stores linked
 * to the originating seller account, while already-known marketplaces reuse the
 * existing internal store rather than create a duplicate.</p>
 */
public interface StoreDiscoveryService {

    /**
     * Discover stores for the seller-account credential behind {@code connectionId}.
     *
     * @param connectionId originating platform connection (the seller-account credential)
     * @param user         the administrator initiating discovery
     * @return the created and reused internal stores for this run
     */
    StoreDiscoveryResult discover(UUID connectionId, CurrentUser user);
}
