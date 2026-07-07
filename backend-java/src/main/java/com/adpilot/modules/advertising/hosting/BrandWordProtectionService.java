package com.adpilot.modules.advertising.hosting;

import java.util.UUID;

/**
 * Brand word protection service for the V3 Keyword Engine (Requirement 5.4, 22.3, 22.4).
 *
 * <p>Checks every proposed negative keyword against the store's configured brand word
 * list. If the search term matches any brand word (using the configured match_type),
 * the proposal is hard-rejected with reason {@code BRAND_PROTECTED}.</p>
 *
 * <p>Match modes:</p>
 * <ul>
 *   <li><b>exact</b>: The search term must exactly match a brand word (case-insensitive)</li>
 *   <li><b>contains</b>: The search term contains a brand word as a substring (case-insensitive)</li>
 * </ul>
 *
 * <p>The service caches the brand word list per store with a configurable TTL to avoid
 * repeated database queries during optimization runs.</p>
 */
public interface BrandWordProtectionService {

    /**
     * Check whether a proposed negative keyword candidate is brand-protected for
     * the given store.
     *
     * @param storeId    the store whose brand word list to check against
     * @param searchTerm the search term being proposed as a negative keyword
     * @return a {@link BrandProtectionResult} indicating rejection or allowance
     */
    BrandProtectionResult checkNegativeCandidate(UUID storeId, String searchTerm);

    /**
     * Invalidate the cached brand word list for a store. Called when brand words
     * are added or removed via the CRUD API.
     *
     * @param storeId the store whose cache to invalidate
     */
    void invalidateCache(UUID storeId);
}
