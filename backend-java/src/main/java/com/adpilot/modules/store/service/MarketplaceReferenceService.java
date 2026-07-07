package com.adpilot.modules.store.service;

import java.time.ZoneId;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Shared read-only access to the near-static {@code marketplaces} reference
 * table, backed by a short-to-medium TTL in-memory cache.
 *
 * <p>The {@code marketplaces} table changes very rarely, yet several hot paths
 * (the AI dashboard, product insights and the report-sync workers) previously
 * performed a full-table scan on every request/tick to resolve a marketplace's
 * currency or timezone. This service loads the whole table once and serves the
 * derived lookups from a cached snapshot that is reloaded only when it is older
 * than the configured TTL.</p>
 *
 * <p>All methods are thread-safe (the report-sync worker pool calls them
 * concurrently) and fail open: a query failure degrades to an empty map / UTC
 * fallback and is logged, never thrown.</p>
 */
public interface MarketplaceReferenceService {

    /**
     * @return an immutable map of marketplace id -&gt; normalized (trimmed,
     * upper-cased) ISO currency code, for every marketplace that has a currency.
     * Never {@code null}; empty when the reference load fails.
     */
    Map<UUID, String> currencyByMarketplaceId();

    /**
     * @return the normalized currency for the given marketplace id, or
     * {@link Optional#empty()} when the id is {@code null}/unknown.
     */
    Optional<String> currencyForMarketplace(UUID marketplaceId);

    /**
     * Resolve the marketplace timezone, mirroring the legacy null/blank/invalid
     * handling: falls back to {@code UTC} when the marketplace id is {@code null},
     * unknown, or its configured timezone is blank or not a valid zone id.
     *
     * @return the resolved {@link ZoneId}; {@code UTC} on any fallback.
     */
    ZoneId timezoneForMarketplace(UUID marketplaceId);
}
