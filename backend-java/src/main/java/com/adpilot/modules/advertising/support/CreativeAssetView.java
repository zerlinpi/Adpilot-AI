package com.adpilot.modules.advertising.support;

import java.util.List;

/**
 * Immutable, database-free projection of the searchable attributes of a Creative
 * Asset (Requirement 29.3). This is the value type the pure
 * {@link CreativeAssetFilter} predicate operates on, so the search logic can be
 * exercised without a database or file storage. Mirrors the {@link CampaignView}
 * /{@link CampaignFilter} pairing that Property 6 (task 10.2) pins down.
 *
 * <p>Field meanings:
 * <ul>
 *   <li>{@code storeId} — owning store (the active-store scope key).</li>
 *   <li>{@code name} — asset name (素材名称).</li>
 *   <li>{@code assetType} — creative category (lifestyle/scene/hd_group/marketing).</li>
 *   <li>{@code asin} — associated ASIN, {@code null} when unset.</li>
 *   <li>{@code tags} — free-form tags (标签); never {@code null} (use an empty list).</li>
 *   <li>{@code creator} — resolved creator display name (创建人), {@code null} when unknown.</li>
 * </ul>
 */
public record CreativeAssetView(
        String storeId,
        String name,
        String assetType,
        String asin,
        List<String> tags,
        String creator) {
}
