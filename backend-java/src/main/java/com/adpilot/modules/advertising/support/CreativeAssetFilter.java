package com.adpilot.modules.advertising.support;

import java.util.List;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Pure, side-effect-free predicate over the searchable attributes of a Creative
 * Asset (Requirement 29.3). Given a store scope, an optional asset-type filter,
 * and an optional free-text search term, it decides whether a single
 * {@link CreativeAssetView} is selected, and can refine a list to exactly the
 * matching subset. This mirrors the {@link CampaignFilter} design and shares the
 * soundness/completeness contract of Property 6 (task 10.2): the result contains
 * every item satisfying all active filters and no item that fails any.
 *
 * <p><strong>Semantics.</strong> Filters combine with logical <em>AND</em>. A
 * filter is <em>inactive</em> (imposes no constraint) when its value is
 * {@code null} or blank, so an empty filter selects every asset within the
 * active store. The {@code search} term matches when it is a case-insensitive
 * substring of the asset's name, <em>any</em> of its tags, its ASIN, or its
 * creator name (Req 29.3 — search by name/tag/ASIN/creator).
 *
 * <p>Matching rules:
 * <ul>
 *   <li>{@code storeId} — exact, case-sensitive equality (it is an identifier).</li>
 *   <li>{@code assetType} — case-insensitive equality.</li>
 *   <li>{@code search} — case-insensitive substring match against name, any
 *       tag, ASIN, or creator; a blank search matches everything.</li>
 * </ul>
 *
 * <p>Instances are immutable; build them with {@link #builder()}.
 */
public final class CreativeAssetFilter {

    private final String storeId;
    private final String assetType;
    private final String search;

    private CreativeAssetFilter(Builder b) {
        this.storeId = blankToNull(b.storeId);
        this.assetType = blankToNull(b.assetType);
        this.search = lowerOrNull(b.search);
    }

    /**
     * Decide whether a single asset satisfies every active filter.
     *
     * @param a the asset projection (must not be {@code null})
     * @return {@code true} iff the asset passes all active filters
     */
    public boolean matches(CreativeAssetView a) {
        if (a == null) {
            return false;
        }
        if (storeId != null && !storeId.equals(a.storeId())) {
            return false;
        }
        if (assetType != null && !assetType.equalsIgnoreCase(nz(a.assetType()))) {
            return false;
        }
        if (search != null && !matchesSearch(a)) {
            return false;
        }
        return true;
    }

    /** The search term matches name OR any tag OR ASIN OR creator (case-insensitive). */
    private boolean matchesSearch(CreativeAssetView a) {
        if (contains(a.name())) {
            return true;
        }
        if (contains(a.asin())) {
            return true;
        }
        if (contains(a.creator())) {
            return true;
        }
        if (a.tags() != null) {
            for (String tag : a.tags()) {
                if (contains(tag)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean contains(String value) {
        return value != null && value.toLowerCase().contains(search);
    }

    /** This filter as a {@link Predicate} for use in streams. */
    public Predicate<CreativeAssetView> asPredicate() {
        return this::matches;
    }

    /**
     * Refine a list to exactly the assets that satisfy every active filter,
     * preserving the input order.
     */
    public List<CreativeAssetView> apply(List<CreativeAssetView> assets) {
        return assets.stream().filter(this::matches).collect(Collectors.toList());
    }

    public String getStoreId() {
        return storeId;
    }

    public String getAssetType() {
        return assetType;
    }

    public String getSearch() {
        return search;
    }

    private static String nz(String v) {
        return v == null ? "" : v;
    }

    private static String blankToNull(String v) {
        return (v == null || v.isBlank()) ? null : v.trim();
    }

    private static String lowerOrNull(String v) {
        return (v == null || v.isBlank()) ? null : v.trim().toLowerCase();
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Builder for {@link CreativeAssetFilter}. */
    public static final class Builder {
        private String storeId;
        private String assetType;
        private String search;

        public Builder storeId(String v) {
            this.storeId = v;
            return this;
        }

        public Builder assetType(String v) {
            this.assetType = v;
            return this;
        }

        public Builder search(String v) {
            this.search = v;
            return this;
        }

        public CreativeAssetFilter build() {
            return new CreativeAssetFilter(this);
        }
    }
}
